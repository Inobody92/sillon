package io.github.inobody92.sillon.data

import android.content.Context
import org.json.JSONArray
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
                val songs = runCatching { songsFromJson(JSONArray(saved)) }.getOrDefault(emptyList())
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
                .putString("songs", chosen.toJsonArray().toString())
                .remove("ids")
                .apply()
        }
        return chosen
    }
}
