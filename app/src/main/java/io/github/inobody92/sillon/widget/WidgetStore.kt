package io.github.inobody92.sillon.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Ce que le widget affiche. Écrit par le service de lecture, lu par le widget (même processus, mais le widget peut être redessiné sans service). */
data class WidgetSnapshot(
    val hasItem: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    /** Identifie la pochette actuellement enregistrée, pour ne pas la recharger à chaque événement. */
    val coverKey: String? = null,
)

object WidgetStore {

    private const val PREFS = "sillon_widget"
    private const val COVER_FILE = "widget_cover.png"

    // Le widget lit ces flux en continu : une session Glance reste ouverte un moment après un clic, et une valeur lue
    // une seule fois au début y resterait périmée (le bouton « Pause » ne repasserait pas à « Lecture »).
    private val _snapshot = MutableStateFlow(WidgetSnapshot())
    private val _cover = MutableStateFlow<Bitmap?>(null)
    val snapshot: StateFlow<WidgetSnapshot> = _snapshot.asStateFlow()
    val cover: StateFlow<Bitmap?> = _cover.asStateFlow()
    @Volatile private var loaded = false

    /** Charge l'état enregistré (une fois par processus) pour que le widget ait quelque chose à afficher dès le départ. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _snapshot.value = load(context)
            _cover.value = loadCover(context)
            loaded = true
        }
    }

    fun load(context: Context): WidgetSnapshot {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return WidgetSnapshot(
            hasItem = p.getBoolean("hasItem", false),
            title = p.getString("title", "").orEmpty(),
            artist = p.getString("artist", "").orEmpty(),
            playing = p.getBoolean("playing", false),
            coverKey = p.getString("coverKey", null),
        )
    }

    fun save(context: Context, s: WidgetSnapshot) {
        ensureLoaded(context)
        _snapshot.value = s
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("hasItem", s.hasItem)
            .putString("title", s.title)
            .putString("artist", s.artist)
            .putBoolean("playing", s.playing)
            .putString("coverKey", s.coverKey)
            .apply()
    }

    /** Enregistre la pochette (déjà réduite) ; null la supprime. */
    fun saveCover(context: Context, bitmap: Bitmap?) {
        ensureLoaded(context)
        _cover.value = bitmap
        val file = File(context.applicationContext.filesDir, COVER_FILE)
        if (bitmap == null) {
            file.delete()
            return
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun loadCover(context: Context): Bitmap? {
        val file = File(context.applicationContext.filesDir, COVER_FILE)
        return if (file.exists()) BitmapFactory.decodeFile(file.path) else null
    }
}
