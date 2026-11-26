package io.github.inobody92.sillon.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Une demande envoyée au serveur de téléchargement, gardée pour pouvoir la relancer : le serveur ne conserve pas
 * ce qui a été demandé dans l'historique, seulement le nom et le résultat.
 */
sealed interface DownloadRequest {
    data class Song(val id: String) : DownloadRequest
    data class Album(val id: String) : DownloadRequest
    data class Artist(val id: String, val count: Int) : DownloadRequest
    data class Link(val url: String) : DownloadRequest
}

internal fun DownloadRequest.toJson(): JSONObject = when (this) {
    is DownloadRequest.Song -> JSONObject().put("type", "song").put("id", id)
    is DownloadRequest.Album -> JSONObject().put("type", "album").put("id", id)
    is DownloadRequest.Artist -> JSONObject().put("type", "artist").put("id", id).put("count", count)
    is DownloadRequest.Link -> JSONObject().put("type", "link").put("url", url)
}

internal fun downloadRequestFromJson(json: JSONObject): DownloadRequest? = when (json.optString("type")) {
    "song" -> json.optString("id").ifBlank { null }?.let { DownloadRequest.Song(it) }
    "album" -> json.optString("id").ifBlank { null }?.let { DownloadRequest.Album(it) }
    "artist" -> json.optString("id").ifBlank { null }?.let { DownloadRequest.Artist(it, json.optInt("count")) }
    "link" -> json.optString("url").ifBlank { null }?.let { DownloadRequest.Link(it) }
    else -> null
}

/**
 * Mémoire des dernières demandes, par numéro de tâche du serveur. Le nom est conservé aussi : le serveur
 * recommence ses numéros à chaque redémarrage, un numéro seul pourrait désigner une autre demande.
 */
class DownloadRequestLog(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    @Synchronized
    fun remember(number: Int, name: String, request: DownloadRequest) {
        val all = LinkedHashMap(load())
        all.remove(number) // réinséré en dernier : l'ordre est celui des demandes, pas des numéros (qui repartent de zéro)
        all[number] = name to request
        // Les plus anciennes sortent : le serveur ne garde de toute façon que ses 30 dernières demandes terminées.
        while (all.size > MAX) all.remove(all.keys.first())
        save(all)
    }

    @Synchronized
    fun find(number: Int, name: String): DownloadRequest? =
        load()[number]?.takeIf { it.first == name }?.second

    @Synchronized
    fun forget(number: Int) = save(load().filterKeys { it != number })

    private fun load(): Map<Int, Pair<String, DownloadRequest>> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        val array = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<Int, Pair<String, DownloadRequest>>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val request = downloadRequestFromJson(o.optJSONObject("request") ?: continue) ?: continue
            out[o.optInt("number")] = o.optString("name") to request
        }
        return out
    }

    private fun save(all: Map<Int, Pair<String, DownloadRequest>>) {
        val array = org.json.JSONArray()
        all.forEach { (number, v) ->
            array.put(JSONObject().put("number", number).put("name", v.first).put("request", v.second.toJson()))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val PREFS = "download_requests"
        const val KEY = "requests"
        const val MAX = 40
    }
}
