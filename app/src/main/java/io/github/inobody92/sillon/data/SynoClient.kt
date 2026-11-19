package io.github.inobody92.sillon.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class Credentials(
    val url: String,
    val account: String,
    val password: String,
    val insecure: Boolean,
    val deviceId: String? = null,
)

data class ApiInfo(val path: String, val minVersion: Int, val maxVersion: Int)

class ApiException(val code: Int, message: String) : Exception(message)

class OtpRequiredException : Exception("Code OTP requis")

data class Album(val name: String, val albumArtist: String, val artist: String, val year: Int) {
    val displayArtist: String get() = albumArtist.ifBlank { artist }
}

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val track: Int,
    val disc: Int,
    val durationSec: Int,
    val ext: String,
)

/** Session active, partagée avec le service de lecture (qui vit hors de l'interface). */
object SessionHolder {
    @Volatile
    var client: SynoClient? = null
}

/**
 * Client minimal pour l'API web Audio Station (voir docs/API.md).
 * Les chemins des APIs sont découverts via query.cgi, jamais codés en dur.
 */
class SynoClient(val creds: Credentials) {

    val http: OkHttpClient = buildHttp(creds.insecure)

    private var apis: Map<String, ApiInfo> = emptyMap()

    @Volatile
    var sid: String? = null
        private set

    /** Identifiant de device de confiance, renvoyé par le NAS après un login avec OTP. */
    @Volatile
    var deviceId: String? = creds.deviceId
        private set

    suspend fun connect(otp: String? = null) = withContext(Dispatchers.IO) {
        discover()
        login(otp)
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        try {
            post("SYNO.API.Auth", "logout", params = mapOf("session" to "AudioStation"))
        } catch (_: Exception) {
            // Déconnexion au mieux : on oublie la session quoi qu'il arrive.
        }
        sid = null
    }

    /** Appel d'API authentifié. Se reconnecte une fois si la session a expiré. */
    suspend fun call(
        api: String,
        method: String,
        version: Int? = null,
        params: Map<String, String> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        try {
            post(api, method, version, params)
        } catch (e: ApiException) {
            if (e.code in SESSION_ERRORS) {
                login(null)
                post(api, method, version, params)
            } else {
                throw e
            }
        }
    }

    suspend fun albums(offset: Int, limit: Int): List<Album> {
        val data = call(
            "SYNO.AudioStation.Album", "list",
            params = mapOf("offset" to "$offset", "limit" to "$limit", "library" to "all"),
        ).getJSONObject("data")
        val arr = data.optJSONArray("albums") ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Album(
                name = o.optString("name"),
                albumArtist = o.optString("album_artist"),
                artist = o.optString("artist"),
                year = o.optInt("year"),
            )
        }
    }

    suspend fun songs(album: Album): List<Song> {
        val params = mutableMapOf(
            "offset" to "0",
            "limit" to "1000",
            "library" to "all",
            "album" to album.name,
            "additional" to "song_tag,song_audio",
        )
        if (album.albumArtist.isNotBlank()) params["album_artist"] = album.albumArtist
        val data = call("SYNO.AudioStation.Song", "list", params = params).getJSONObject("data")
        val arr = data.optJSONArray("songs") ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val tag = o.optJSONObject("additional")?.optJSONObject("song_tag")
            val audio = o.optJSONObject("additional")?.optJSONObject("song_audio")
            Song(
                id = o.getString("id"),
                title = o.optString("title"),
                artist = tag?.optString("artist").orEmpty(),
                album = tag?.optString("album").orEmpty(),
                albumArtist = tag?.optString("album_artist").orEmpty(),
                track = tag?.optInt("track") ?: 0,
                disc = tag?.optInt("disc", 1) ?: 1,
                durationSec = audio?.optInt("duration") ?: 0,
                ext = o.optString("path").substringAfterLast('.', "").lowercase(),
            )
        }.sortedWith(compareBy({ it.disc }, { it.track }, { it.title }))
    }

    /** URL de lecture directe d'un morceau, avec le sid courant. */
    fun streamUrl(songId: String, ext: String): String? {
        val info = apis["SYNO.AudioStation.Stream"] ?: return null
        return webapiUrl(info.path).newBuilder()
            .addQueryParameter("api", "SYNO.AudioStation.Stream")
            .addQueryParameter("method", "stream")
            .addQueryParameter("version", "1")
            .addQueryParameter("id", songId)
            .addQueryParameter("_sid", sid.orEmpty())
            .addQueryParameter("ext", ".$ext")
            .build()
            .toString()
    }

    /** URL de pochette. Le sid est ajouté à part pour ne pas polluer la clé de cache d'image. */
    fun coverUrl(album: Album, withSid: Boolean): String? {
        val info = apis["SYNO.AudioStation.Cover"] ?: return null
        val builder = webapiUrl(info.path).newBuilder()
            .addQueryParameter("api", "SYNO.AudioStation.Cover")
            .addQueryParameter("method", "getcover")
            .addQueryParameter("version", "1")
            .addQueryParameter("album_name", album.name)
            .addQueryParameter("album_artist_name", album.displayArtist)
        if (withSid) sid?.let { builder.addQueryParameter("_sid", it) }
        return builder.build().toString()
    }

    // --- interne ---------------------------------------------------------------------------

    private fun webapiUrl(path: String): HttpUrl = "${creds.url}/webapi/$path".toHttpUrl()

    private fun discover() {
        val url = webapiUrl("query.cgi").newBuilder()
            .addQueryParameter("api", "SYNO.API.Info")
            .addQueryParameter("method", "query")
            .addQueryParameter("version", "1")
            .addQueryParameter("query", "all")
            .build()
        val json = execute(Request.Builder().url(url).build())
        if (!json.optBoolean("success")) throw ApiException(errorCode(json), "Découverte des APIs impossible")
        val data = json.getJSONObject("data")
        apis = data.keys().asSequence().associateWith { key ->
            val o = data.getJSONObject(key)
            ApiInfo(o.getString("path"), o.optInt("minVersion", 1), o.optInt("maxVersion", 1))
        }
        if (apis.keys.none { it.startsWith("SYNO.AudioStation.") }) {
            throw ApiException(-1, "Audio Station n'est pas installé ou activé sur ce NAS")
        }
    }

    private fun login(otp: String?) {
        val params = mutableMapOf(
            "account" to creds.account,
            "passwd" to creds.password,
            "session" to "AudioStation",
            "format" to "sid",
        )
        if (otp != null) {
            params["otp_code"] = otp
            params["enable_device_token"] = "yes"
            params["device_name"] = "Sillon"
        }
        deviceId?.let { params["device_id"] = it }
        val json = post("SYNO.API.Auth", "login", params = params, withSid = false, raise = false)
        if (!json.optBoolean("success")) {
            val code = errorCode(json)
            if (code == ERROR_OTP_REQUIRED) throw OtpRequiredException()
            throw ApiException(code, "Échec de connexion (code $code)")
        }
        val data = json.getJSONObject("data")
        sid = data.getString("sid")
        data.optString("did").takeIf { it.isNotBlank() }?.let { deviceId = it }
    }

    private fun post(
        api: String,
        method: String,
        version: Int? = null,
        params: Map<String, String> = emptyMap(),
        withSid: Boolean = true,
        raise: Boolean = true,
    ): JSONObject {
        val info = apis[api] ?: throw ApiException(-1, "API absente du NAS : $api")
        val body = FormBody.Builder()
            .add("api", api)
            .add("method", method)
            .add("version", (version ?: info.maxVersion).toString())
        params.forEach { (k, v) -> body.add(k, v) }
        if (withSid) sid?.let { body.add("_sid", it) }
        val json = execute(Request.Builder().url(webapiUrl(info.path)).post(body.build()).build())
        if (raise && !json.optBoolean("success")) throw ApiException(errorCode(json), "Erreur API $api.$method")
        return json
    }

    private fun execute(request: Request): JSONObject =
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            JSONObject(resp.body?.string() ?: throw IOException("Réponse vide"))
        }

    private fun errorCode(json: JSONObject): Int = json.optJSONObject("error")?.optInt("code", -1) ?: -1

    companion object {
        private val SESSION_ERRORS = setOf(106, 107, 119)
        private const val ERROR_OTP_REQUIRED = 403

        /** Clé de cache stable pour une URL : on retire le sid, qui change à chaque connexion. */
        fun stableKey(url: String): String =
            url.toHttpUrl().newBuilder().removeAllQueryParameters("_sid").build().toString()

        /** Accepte https://hôte[:port] ou hôte[:port] ; refuse le HTTP clair. */
        fun normalizeUrl(input: String): String {
            val t = input.trim().trimEnd('/')
            require(t.isNotEmpty()) { "Adresse vide" }
            require(!t.startsWith("http://", ignoreCase = true)) { "Seul HTTPS est accepté" }
            return if (t.startsWith("https://", ignoreCase = true)) t else "https://$t"
        }

        @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
        private fun buildHttp(insecure: Boolean): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
            if (insecure) {
                // Uniquement pour un NAS à certificat auto-signé, activé explicitement par l'utilisateur.
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
                builder.sslSocketFactory(ctx.socketFactory, trustAll).hostnameVerifier { _, _ -> true }
            }
            return builder.build()
        }
    }
}
