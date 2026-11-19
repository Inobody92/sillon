package io.github.inobody92.sillon.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.inobody92.sillon.data.ApiException
import io.github.inobody92.sillon.data.Credentials
import io.github.inobody92.sillon.data.OfflineStore
import io.github.inobody92.sillon.data.OtpRequiredException
import io.github.inobody92.sillon.data.SecureStore
import io.github.inobody92.sillon.data.SessionHolder
import io.github.inobody92.sillon.data.SynoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.net.ssl.SSLException

sealed interface AuthState {
    data object Loading : AuthState
    data class LoggedOut(val error: String? = null, val otpRequired: Boolean = false) : AuthState

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

    fun login(url: String, account: String, password: String, insecure: Boolean, otp: String?) {
        val normalized = try {
            SynoClient.normalizeUrl(url)
        } catch (e: IllegalArgumentException) {
            _state.value = AuthState.LoggedOut(error = e.message)
            return
        }
        // On réutilise le device de confiance enregistré pour ce même compte et ce même NAS.
        val previous = store.load()
        val deviceId = previous?.takeIf { it.url == normalized && it.account == account }?.deviceId
        connect(Credentials(normalized, account, password, insecure, deviceId), otp?.ifBlank { null }, silent = false)
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

    private fun connect(creds: Credentials, otp: String?, silent: Boolean) {
        _state.value = AuthState.Loading
        viewModelScope.launch {
            val client = SynoClient(creds)
            try {
                client.connect(otp)
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
                    AuthState.LoggedOut(error = if (silent) null else describe(e))
                }
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is ApiException -> when (e.code) {
            400 -> "Identifiants incorrects"
            401 -> "Ce compte est désactivé"
            402 -> "Permission refusée"
            404 -> "Code OTP incorrect"
            406 -> "L'authentification en deux étapes est obligatoire sur ce compte"
            else -> e.message ?: "Erreur ${e.code}"
        }
        is SSLException -> "Certificat non reconnu. S'il est auto-signé, active l'option dédiée."
        is IOException -> "Impossible de joindre le NAS : vérifie l'adresse, le port et ta connexion"
        else -> e.message ?: "Erreur inconnue"
    }
}
