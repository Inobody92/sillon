package io.github.inobody92.sillon.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

/**
 * Serveur de téléchargement personnel (facultatif) : un petit service HTTPS, installé à côté du NAS, qui cherche de la
 * musique et la dépose dans la bibliothèque. Le contrat est décrit dans `docs/DOWNLOAD_SERVER.md`.
 */
data class DownloadServerConfig(val url: String, val token: String, val pinnedSha256: String? = null) {
    val host: String get() = url.removePrefix("https://")
}

data class RemoteSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val cover: String?,
    val durationSec: Int?,
    val fans: Long?,
)

data class RemoteArtist(
    val id: String,
    val name: String,
    val realName: String?,
    val isGroup: Boolean,
    val albums: Int?,
    val tracks: Int?,
    val fans: Long?,
    val photo: String?,
)

data class RemoteTask(
    val number: Int,
    val name: String,
    val state: String,
    val running: Boolean,
    /** Position dans la file d'attente (0 = prochain) ; null pour la tâche en cours. */
    val position: Int?,
    val percent: Float,
    val total: Int,
    val done: Int,
    val currentTrack: String?,
    val speedBps: Long?,
    val etaSec: Int?,
    val errors: Int,
)

data class RemoteFinished(
    val number: Int,
    val name: String,
    /** `termine`, `annule` ou `echec`. */
    val status: String,
    val downloaded: Int,
    val errors: Int,
    val total: Int,
    val error: String?,
    val finishedAt: Long,
)

data class RemoteQueue(
    val pausedUntil: Long?,
    val tasks: List<RemoteTask>,
    val finished: List<RemoteFinished>,
)

data class QueuedTask(val number: Int, val name: String, val position: Int)

/** [kind] permet à l'écran de réagir : proposer d'épingler un certificat, redemander le jeton… */
class DownloadServerException(
    message: String,
    val kind: Kind,
    cause: Throwable? = null,
) : IOException(message, cause) {
    enum class Kind { NETWORK, CERTIFICATE, AUTH, SERVER }
}

class DownloadServerClient(baseUrl: String, private val token: String, private val http: OkHttpClient) {

    private val base = baseUrl.trimEnd('/').toHttpUrl()

    suspend fun health(): Boolean = obj("GET", "api/sante").optBoolean("ok", false)

    suspend fun searchSongs(query: String): List<RemoteSong> =
        obj("GET", "api/chansons", query = query).getJSONArray("resultats").mapObjects { c ->
            RemoteSong(
                id = c.getString("id"), title = c.getString("titre"), artist = c.getString("artiste"),
                album = c.optString("album"), cover = c.optStringOrNull("pochette"),
                durationSec = c.optIntOrNull("duree"), fans = c.optLongOrNull("fans"),
            )
        }

    suspend fun searchArtists(query: String): List<RemoteArtist> =
        obj("GET", "api/artistes", query = query).getJSONArray("resultats").mapObjects { a ->
            RemoteArtist(
                id = a.getString("id"), name = a.getString("nom"), realName = a.optStringOrNull("vrai_nom"),
                isGroup = a.optBoolean("groupe"), albums = a.optIntOrNull("albums"), tracks = a.optIntOrNull("titres"),
                fans = a.optLongOrNull("fans"), photo = a.optStringOrNull("photo"),
            )
        }

    suspend fun downloadSong(id: String): QueuedTask = queued(obj("POST", "api/chansons", JSONObject().put("id", id)))

    /** [count] = 0 : toute la discographie ; sinon les [count] titres les plus populaires. */
    suspend fun downloadArtist(id: String, count: Int): QueuedTask =
        queued(obj("POST", "api/artistes", JSONObject().put("id", id).put("nombre", count)))

    suspend fun downloadLink(url: String): QueuedTask = queued(obj("POST", "api/liens", JSONObject().put("url", url)))

    suspend fun queue(): RemoteQueue {
        val json = obj("GET", "api/file")
        val tasks = json.getJSONArray("taches").mapObjects { t ->
            RemoteTask(
                number = t.getInt("numero"), name = t.getString("nom"), state = t.optString("etat"),
                running = t.optBoolean("en_cours"), position = t.optIntOrNull("position"),
                percent = t.optDouble("pourcentage", 0.0).toFloat(), total = t.optInt("total"), done = t.optInt("faites"),
                currentTrack = t.optStringOrNull("morceau"), speedBps = t.optLongOrNull("vitesse"),
                etaSec = t.optIntOrNull("eta"), errors = t.optInt("erreurs"),
            )
        }
        val finished = json.getJSONArray("terminees").mapObjects { f ->
            RemoteFinished(
                number = f.getInt("numero"), name = f.getString("nom"), status = f.getString("statut"),
                downloaded = f.optInt("telechargees"), errors = f.optInt("erreurs"), total = f.optInt("total"),
                error = f.optStringOrNull("erreur"), finishedAt = f.optLong("fin"),
            )
        }
        return RemoteQueue(json.optLongOrNull("pause_jusqua"), tasks, finished)
    }

    /** Renvoie le message du serveur (« Annulation demandée… »). */
    suspend fun cancel(number: Int): String = obj("DELETE", "api/taches/$number").optString("message")

    // --- HTTP -------------------------------------------------------------------------------

    private fun queued(json: JSONObject) = QueuedTask(json.getInt("numero"), json.getString("nom"), json.optInt("position"))

    private suspend fun obj(method: String, path: String, body: JSONObject? = null, query: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val url = base.newBuilder().addPathSegments(path).apply { if (query != null) addQueryParameter("q", query) }.build()
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .method(method, body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    if (!response.isSuccessful) {
                        val message = when {
                            response.code == 401 -> "Jeton refusé par le serveur"
                            json?.has("erreur") == true -> json.getString("erreur")
                            else -> "Erreur ${response.code} du serveur"
                        }
                        throw DownloadServerException(
                            message,
                            if (response.code == 401) DownloadServerException.Kind.AUTH else DownloadServerException.Kind.SERVER,
                        )
                    }
                    json ?: throw DownloadServerException("Réponse inattendue du serveur", DownloadServerException.Kind.SERVER)
                }
            } catch (e: DownloadServerException) {
                throw e
            } catch (e: SSLException) {
                throw DownloadServerException("Certificat du serveur non reconnu", DownloadServerException.Kind.CERTIFICATE, e)
            } catch (e: IOException) {
                throw DownloadServerException("Serveur injoignable", DownloadServerException.Kind.NETWORK, e)
            }
        }

    companion object {
        /** Accepte `https://hôte[:port][/chemin]` ou `hôte[:port]` ; refuse le HTTP clair (le jeton y passerait en clair). */
        fun normalizeUrl(input: String): String = SynoClient.normalizeUrl(input)

        fun create(config: DownloadServerConfig): DownloadServerClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS) // une recherche passe par plusieurs services distants
            config.pinnedSha256?.let { pin ->
                // Certificat auto-signé : on n'accepte que celui dont l'empreinte a été confirmée.
                val trust = PinnedTrustManager(pin)
                val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
                builder.sslSocketFactory(ctx.socketFactory, trust).hostnameVerifier { _, _ -> true }
            }
            return DownloadServerClient(config.url, config.token, builder.build())
        }
    }
}

private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    List(length()) { transform(getJSONObject(it)) }

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }
private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)
private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)

/** Réglage du serveur de téléchargement, partagé par les écrans. Le jeton est chiffré (Keystore), comme le mot de passe. */
object DownloadServerStore {

    private lateinit var secure: SecureStore
    private val _config = MutableStateFlow<DownloadServerConfig?>(null)
    val config: StateFlow<DownloadServerConfig?> = _config.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::secure.isInitialized) return
        secure = SecureStore(context)
        _config.value = secure.loadDownloadServer()
    }

    fun save(config: DownloadServerConfig) {
        secure.saveDownloadServer(config)
        _config.value = config
    }

    fun clear() {
        secure.clearDownloadServer()
        _config.value = null
    }
}
