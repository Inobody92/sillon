package io.github.inobody92.sillon.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.inobody92.sillon.data.ApiException
import io.github.inobody92.sillon.data.CertificateInfo
import io.github.inobody92.sillon.data.Credentials
import io.github.inobody92.sillon.data.OfflineStore
import io.github.inobody92.sillon.data.OtpRequiredException
import io.github.inobody92.sillon.data.QuickConnect
import io.github.inobody92.sillon.data.SecureStore
import io.github.inobody92.sillon.data.SessionHolder
import io.github.inobody92.sillon.data.SynoClient
import io.github.inobody92.sillon.data.probeCertificate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.net.ssl.SSLException

/** [previousSha256] : empreinte déjà acceptée pour ce NAS, si elle existe (et qui a donc changé). */
data class CertificateRequest(val info: CertificateInfo, val previousSha256: String?)

sealed interface AuthState {
    data object Loading : AuthState
    data class LoggedOut(
        val error: String? = null,
        val otpRequired: Boolean = false,
        /** Certificat auto-signé à confirmer par l'utilisateur avant de continuer. */
        val certificate: CertificateRequest? = null,
    ) : AuthState

    /** NAS injoignable alors que des titres sont téléchargés : on propose l'écoute hors-ligne. */
    data object Offline : AuthState
    data class LoggedIn(val client: SynoClient) : AuthState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SecureStore(app)

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    val lastUrl get() = store.lastUrl()
    val lastAccount get() = store.lastAccount()
    val lastInsecure get() = store.lastInsecure()

    init {
        OfflineStore.init(app)
        val saved = store.load()
        if (saved == null) {
            _state.value = AuthState.LoggedOut()
        } else {
            connect(saved, otp = null, silent = true)
        }
    }

    private var pendingCertificate: Triple<Credentials, String?, CertificateRequest>? = null

    /**
     * [url] est une adresse, ou un ID QuickConnect (un mot sans point ni deux-points). Pour un NAS à certificat
     * auto-signé, la connexion attend que l'utilisateur ait confirmé l'empreinte du certificat.
     */
    fun login(url: String, account: String, password: String, insecure: Boolean, otp: String?) {
        val input = url.trim()
        _state.value = AuthState.Loading
        viewModelScope.launch {
            var quickConnectId: String? = null
            val base = if (QuickConnect.looksLikeId(input)) {
                quickConnectId = input
                QuickConnect.resolve(input) ?: run {
                    _state.value = AuthState.LoggedOut(
                        error = "QuickConnect : « $input » est introuvable ou injoignable. Vérifie l'ID, ou utilise l'adresse du NAS.",
                    )
                    return@launch
                }
            } else {
                try {
                    SynoClient.normalizeUrl(input)
                } catch (e: IllegalArgumentException) {
                    _state.value = AuthState.LoggedOut(error = e.message)
                    return@launch
                }
            }
            // On réutilise le device de confiance enregistré pour ce même compte et ce même NAS.
            val previous = store.load()
            val deviceId = previous?.takeIf { it.url == base && it.account == account }?.deviceId
            // QuickConnect fournit un certificat valide : l'option « auto-signé » ne s'applique qu'à une adresse directe.
            val creds = Credentials(base, account, password, insecure && quickConnectId == null, deviceId, null, quickConnectId)
            if (creds.insecure) askToTrustCertificate(creds, otp?.ifBlank { null }) else connect(creds, otp?.ifBlank { null }, silent = false)
        }
    }

    private suspend fun askToTrustCertificate(creds: Credentials, otp: String?) {
        val info = try {
            probeCertificate(creds.url)
        } catch (e: Exception) {
            _state.value = AuthState.LoggedOut(error = "Impossible de lire le certificat du NAS : ${e.message ?: "erreur réseau"}")
            return
        }
        val known = store.load()?.takeIf { it.url == creds.url }?.pinnedSha256
        if (known != null && known.equals(info.sha256, ignoreCase = true)) {
            // Même certificat que celui déjà accepté : inutile de reposer la question.
            connect(creds.copy(pinnedSha256 = known), otp, silent = false)
            return
        }
        val request = CertificateRequest(info, previousSha256 = known)
        pendingCertificate = Triple(creds, otp, request)
        _state.value = AuthState.LoggedOut(certificate = request)
    }

    fun confirmCertificate() {
        val (creds, otp, request) = pendingCertificate ?: return
        pendingCertificate = null
        connect(creds.copy(pinnedSha256 = request.info.sha256), otp, silent = false)
    }

    fun rejectCertificate() {
        pendingCertificate = null
        _state.value = AuthState.LoggedOut()
    }

    /** Depuis l'écran hors-ligne : retente la connexion avec les identifiants enregistrés. */
    fun retryConnection() {
        store.load()?.let { connect(it, otp = null, silent = true) } ?: run { _state.value = AuthState.LoggedOut() }
    }

    fun logout() {
        val current = (_state.value as? AuthState.LoggedIn)?.client
        store.forgetPassword()
        SessionHolder.client = null
        _state.value = AuthState.LoggedOut()
        if (current != null) viewModelScope.launch { current.logout() }
    }

    private fun connect(initial: Credentials, otp: String?, silent: Boolean) {
        // Réglage hérité d'une ancienne version (confiance aveugle) : on exige de confirmer le certificat.
        if (initial.insecure && initial.pinnedSha256 == null) {
            _state.value = AuthState.LoggedOut(
                error = "Ce NAS utilise un certificat auto-signé : reconnecte-toi pour en confirmer l'empreinte.",
            )
            return
        }
        _state.value = AuthState.Loading
        viewModelScope.launch {
            var creds = initial
            try {
                var client = SynoClient(creds)
                try {
                    client.connect(otp)
                } catch (e: IOException) {
                    // QuickConnect : l'adresse du NAS a pu changer (IP, port). On la retrouve une fois, puis on réessaie.
                    val id = creds.quickConnectId
                    if (id == null || e is SSLException) throw e
                    val fresh = QuickConnect.resolve(id)
                    if (fresh == null || fresh == creds.url) throw e
                    creds = creds.copy(url = fresh)
                    client = SynoClient(creds)
                    client.connect(otp)
                }
                store.save(creds.copy(deviceId = client.deviceId))
                SessionHolder.client = client
                _state.value = AuthState.LoggedIn(client)
            } catch (_: OtpRequiredException) {
                _state.value = AuthState.LoggedOut(otpRequired = true)
            } catch (e: Exception) {
                // Reconnexion automatique impossible faute de réseau : si des titres sont sur le téléphone, on les propose.
                val unreachable = e is IOException && e !is SSLException
                _state.value = if (silent && unreachable && OfflineStore.state.value.songs.isNotEmpty()) {
                    AuthState.Offline
                } else {
                    // Un certificat qui change est un événement de sécurité : on l'annonce même en reconnexion automatique.
                    AuthState.LoggedOut(error = if (silent && !certificateChanged(e)) null else describe(e))
                }
            }
        }
    }

    private fun certificateChanged(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it.message?.contains("a changé") == true }

    private fun describe(e: Exception): String = when (e) {
        is ApiException -> when (e.code) {
            400 -> "Identifiants incorrects"
            401 -> "Ce compte est désactivé"
            402 -> "Permission refusée"
            404 -> "Code OTP incorrect"
            406 -> "L'authentification en deux étapes est obligatoire sur ce compte"
            else -> e.message ?: "Erreur ${e.code}"
        }
        is SSLException ->
            if (certificateChanged(e)) {
                "Le certificat du NAS n'est plus celui que tu avais accepté. Si ce changement est normal, reconnecte-toi pour le confirmer."
            } else {
                "Certificat non reconnu. S'il est auto-signé, active l'option dédiée."
            }
        is IOException -> "Impossible de joindre le NAS : vérifie l'adresse, le port et ta connexion"
        else -> e.message ?: "Erreur inconnue"
    }
}
