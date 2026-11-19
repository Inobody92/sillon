package io.github.inobody92.sillon.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.random.Random

/**
 * Sélection du jour : une trentaine de titres tirés dans toute la bibliothèque, en privilégiant ceux
 * qu'on n'a pas écoutés récemment. La sélection est mémorisée pour la journée : elle ne change pas
 * à chaque ouverture de l'app (ni entre l'app et la voiture), sauf demande explicite.
 */
object DailyMix {

    const val SIZE = 30
    private const val RECENT_DAYS = 14

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("sillon_mix", Context.MODE_PRIVATE)

    suspend fun get(context: Context, client: SynoClient, regenerate: Boolean = false): List<Song> {
        val prefs = prefs(context)
        val today = LocalDate.now().toString()
        val owner = client.creds.url + "|" + client.creds.account

        if (!regenerate && prefs.getString("owner", null) == owner && prefs.getString("date", null) == today) {
            val saved = prefs.getString("songs", null)
            if (saved != null) {
                val songs = runCatching { fromJson(saved) }.getOrDefault(emptyList())
                if (songs.isNotEmpty()) return songs
            }
        }

        // Graine = jour + compte : même sélection toute la journée, différente demain.
        val seed = if (regenerate) System.nanoTime() else (today + owner).hashCode().toLong()
        val recent = PlayHistory.recent(context, RECENT_DAYS)
        val pool = client.randomSongs(SIZE * 2, null, Random(seed))
        val chosen = (pool.filter { it.id !in recent } + pool.filter { it.id in recent }).take(SIZE)

        if (chosen.isNotEmpty()) {
            prefs.edit()
                .putString("owner", owner)
                .putString("date", today)
                .putString("songs", toJson(chosen))
                .remove("ids")
                .apply()
        }
        return chosen
    }

    // Les titres sont mémorisés en entier : relire le mix ne demande aucun appel au NAS.
    private fun toJson(songs: List<Song>): String = JSONArray().apply {
        songs.forEach { s ->
            put(
                JSONObject()
                    .put("id", s.id).put("title", s.title).put("artist", s.artist).put("album", s.album)
                    .put("albumArtist", s.albumArtist).put("track", s.track).put("disc", s.disc)
                    .put("duration", s.durationSec).put("ext", s.ext),
            )
        }
    }.toString()

    private fun fromJson(json: String): List<Song> {
        val arr = JSONArray(json)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Song(
                id = o.getString("id"), title = o.optString("title"), artist = o.optString("artist"),
                album = o.optString("album"), albumArtist = o.optString("albumArtist"),
                track = o.optInt("track"), disc = o.optInt("disc", 1),
                durationSec = o.optInt("duration"), ext = o.optString("ext"),
            )
        }
    }
}
