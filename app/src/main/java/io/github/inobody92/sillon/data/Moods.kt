package io.github.inobody92.sillon.data

import android.content.Context

/**
 * Une humeur est une sélection de genres. Le NAS ne connaît ni l'ambiance ni le tempo d'un morceau :
 * on s'appuie sur les genres de la bibliothèque, que l'utilisateur peut corriger.
 */
enum class Mood(val id: String, val label: String, val emoji: String, private val keywords: List<String>) {
    ENERGY("energy", "Énergie", "⚡", listOf("rock", "metal", "punk", "hard", "dance", "electro", "techno", "hip", "rap", "drum")),
    CHILL("chill", "Chill", "🌙", listOf("acoust", "jazz", "soul", "r&b", "lounge", "folk", "classi", "ambient", "chill", "bossa", "blues", "easy")),
    PARTY("party", "Fête", "🎉", listOf("pop", "dance", "disco", "funk", "latin", "reggae", "house", "club", "electro")),
    NOSTALGIA("nostalgia", "Nostalgie", "📼", listOf("chanson", "variét", "oldies", "80", "90", "rétro", "retro", "classic", "disco", "golden"));

    /** Genres de la bibliothèque qui correspondent par défaut à cette humeur. */
    fun defaultGenres(libraryGenres: List<String>): List<String> =
        libraryGenres.filter { g -> keywords.any { g.lowercase().contains(it) } }
}

/** Genres choisis par l'utilisateur pour chaque humeur (absent = réglage par défaut). */
object MoodStore {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("sillon_moods", Context.MODE_PRIVATE)

    fun custom(context: Context, mood: Mood): Set<String>? =
        prefs(context).getStringSet("mood_${mood.id}", null)

    fun save(context: Context, mood: Mood, genres: Set<String>) {
        prefs(context).edit().putStringSet("mood_${mood.id}", genres).apply()
    }

    fun reset(context: Context, mood: Mood) {
        prefs(context).edit().remove("mood_${mood.id}").apply()
    }

    /** Genres effectifs : choix de l'utilisateur, sinon correspondance par défaut avec la bibliothèque. */
    fun genres(context: Context, mood: Mood, libraryGenres: List<String>): List<String> {
        val chosen = custom(context, mood)
        return if (chosen != null) libraryGenres.filter { it in chosen } else mood.defaultGenres(libraryGenres)
    }
}
