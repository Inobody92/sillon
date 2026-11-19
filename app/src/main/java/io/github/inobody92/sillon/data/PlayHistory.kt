package io.github.inobody92.sillon.data

import android.content.Context
import org.json.JSONObject

/**
 * Historique d'écoute local (le NAS n'expose pas ce qu'on a écouté). Sert à ne pas reproposer
 * trop vite les mêmes titres dans le mix du jour et la radio. Conservé sur l'appareil uniquement.
 */
object PlayHistory {

    private const val MAX_ENTRIES = 3000
    private const val FILE = "play_history.json"

    private val lock = Any()
    private var cache: MutableMap<String, Long>? = null

    fun record(context: Context, songId: String, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val map = load(context)
            map[songId] = now
            if (map.size > MAX_ENTRIES) {
                // On oublie les plus anciens.
                map.entries.sortedBy { it.value }.take(map.size - MAX_ENTRIES).forEach { map.remove(it.key) }
            }
            save(context, map)
        }
    }

    /** Identifiants écoutés depuis moins de [days] jours. */
    fun recent(context: Context, days: Int, now: Long = System.currentTimeMillis()): Set<String> =
        synchronized(lock) {
            val limit = now - days * 24L * 3600_000L
            load(context).filterValues { it >= limit }.keys.toSet()
        }

    fun clear(context: Context) = synchronized(lock) {
        cache = mutableMapOf()
        context.applicationContext.deleteFile(FILE)
    }

    private fun load(context: Context): MutableMap<String, Long> {
        cache?.let { return it }
        val map = mutableMapOf<String, Long>()
        try {
            val f = context.applicationContext.getFileStreamPath(FILE)
            if (f.exists()) {
                val json = JSONObject(f.readText())
                json.keys().forEach { map[it] = json.getLong(it) }
            }
        } catch (_: Exception) {
            // Fichier illisible : on repart d'un historique vide plutôt que de planter.
        }
        cache = map
        return map
    }

    private fun save(context: Context, map: Map<String, Long>) {
        try {
            context.applicationContext.openFileOutput(FILE, Context.MODE_PRIVATE).use {
                it.write(JSONObject(map as Map<*, *>).toString().toByteArray())
            }
        } catch (_: Exception) {
        }
    }
}
