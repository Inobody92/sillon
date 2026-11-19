package io.github.inobody92.sillon.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Une version publiée sur GitHub. [version] sans le « v » du tag (« 1.4.2 »). */
data class AppRelease(val version: String, val pageUrl: String, val apkUrl: String?, val notes: String)

/**
 * Cherche, au lancement, s'il existe une version plus récente publiée dans les « Releases » du dépôt GitHub.
 * C'est la seule requête que Sillon envoie ailleurs que vers ton NAS : anonyme (aucun identifiant, aucun compte),
 * au plus une fois toutes les 12 h, et désactivable dans les réglages.
 * Sillon ne s'installe pas tout seul : il propose d'ouvrir le téléchargement, et Android demande ta confirmation.
 */
object UpdateChecker {

    const val REPO = "Inobody92/sillon"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val TRUSTED_PREFIX = "https://github.com/$REPO/"
    private const val MIN_INTERVAL_MS = 12L * 3600 * 1000

    private val _available = MutableStateFlow<AppRelease?>(null)
    /** Version à proposer à l'utilisateur, ou null. */
    val available: StateFlow<AppRelease?> = _available.asStateFlow()

    /** Vérification automatique : respecte le réglage, l'intervalle minimal et la version déjà ignorée. Silencieuse en cas d'échec. */
    suspend fun checkAtLaunch(context: Context, now: Long = System.currentTimeMillis()) {
        if (!AppSettings.checkUpdates.value) return
        if (now - AppSettings.lastUpdateCheck() < MIN_INTERVAL_MS) return
        val release = try { fetch() } catch (_: IOException) { return }
        AppSettings.markUpdateChecked(now)
        if (release != null && isNewer(installedVersion(context), release.version) && release.version != AppSettings.ignoredUpdate()) {
            _available.value = release
        }
    }

    /** Vérification manuelle (bouton des réglages) : ignore l'intervalle et la version ignorée. */
    suspend fun checkNow(context: Context): Result {
        val release = try { fetch() } catch (e: IOException) { return Result.Failed }
        AppSettings.markUpdateChecked(System.currentTimeMillis())
        return if (release != null && isNewer(installedVersion(context), release.version)) {
            _available.value = release
            Result.Available(release)
        } else {
            Result.UpToDate
        }
    }

    sealed interface Result {
        data object UpToDate : Result
        data object Failed : Result
        data class Available(val release: AppRelease) : Result
    }

    fun dismiss() { _available.value = null }

    fun ignore(release: AppRelease) {
        AppSettings.ignoreUpdate(release.version)
        _available.value = null
    }

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** Dernière release publiée, ou null s'il n'y en a pas / si la réponse est inutilisable. Lève [IOException] si le réseau échoue. */
    suspend fun fetch(url: String = LATEST_URL, http: OkHttpClient = defaultHttp): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Sillon")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@use null // aucune release publiée
            if (!response.isSuccessful) throw IOException("GitHub : HTTP ${response.code}")
            parse(response.body?.string().orEmpty())
        }
    }

    private val defaultHttp by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    }

    /**
     * Lit la réponse de l'API GitHub. Ignore les brouillons et les préversions, et ne garde que des adresses du dépôt
     * (une réponse altérée ne peut pas faire ouvrir un autre site).
     */
    fun parse(json: String): AppRelease? {
        val o = try { JSONObject(json) } catch (_: Exception) { return null }
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val version = o.optString("tag_name").removePrefix("v").removePrefix("V").trim()
        if (!Regex("""\d+(\.\d+)*""").matches(version)) return null
        val page = o.optString("html_url").takeIf { it.startsWith(TRUSTED_PREFIX) } ?: return null
        var apk: String? = null
        o.optJSONArray("assets")?.let { assets ->
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val link = asset.optString("browser_download_url")
                if (link.endsWith(".apk") && link.startsWith(TRUSTED_PREFIX)) { apk = link; break }
            }
        }
        return AppRelease(version, page, apk, o.optString("body").trim())
    }

    /** `candidate` est-elle strictement plus récente que `current` ? Comparaison numérique (1.4.10 > 1.4.9). */
    fun isNewer(current: String, candidate: String): Boolean {
        val a = numbers(current) ?: return false
        val b = numbers(candidate) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (y != x) return y > x
        }
        return false
    }

    private fun numbers(version: String): List<Int>? {
        val parts = version.trim().removePrefix("v").split(".")
        return parts.map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }
    }
}
