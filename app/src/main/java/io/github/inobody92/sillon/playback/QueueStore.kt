package io.github.inobody92.sillon.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.inobody92.sillon.data.Song
import io.github.inobody92.sillon.data.songFromJson
import io.github.inobody92.sillon.data.toJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedItem(val song: Song, val parent: String?, val radio: String?)

data class SavedQueue(
    val items: List<SavedItem>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
)

/**
 * File d'attente mémorisée par le service : elle survit à la fermeture de l'application et au redémarrage du
 * téléphone. On ne garde que les titres (les adresses d'images et de lecture sont recalculées à la reprise,
 * car elles contiennent un sid de session qui expire).
 */
object QueueStore {

    private const val FILE = "queue.json"
    private const val MAX_ITEMS = 500
    private const val KEEP_BEFORE = 50

    /** Fabrique le JSON à écrire. À appeler depuis le thread du lecteur ; l'écriture disque peut se faire ailleurs. */
    fun snapshot(player: Player): String? {
        val count = player.mediaItemCount
        if (count == 0) return null
        // File très longue (radio qui tourne depuis des heures) : on garde une fenêtre autour du titre en cours.
        val current = player.currentMediaItemIndex
        val from = (current - KEEP_BEFORE).coerceAtLeast(0)
        val to = minOf(count, from + MAX_ITEMS)
        val items = JSONArray()
        for (i in from until to) {
            val saved = savedItemOf(player.getMediaItemAt(i)) ?: continue
            items.put(
                saved.song.toJson()
                    .put("parent", saved.parent ?: JSONObject.NULL)
                    .put("radio", saved.radio ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("items", items)
            .put("index", current - from)
            .put("position", player.currentPosition)
            .put("shuffle", player.shuffleModeEnabled)
            .put("repeat", player.repeatMode)
            .toString()
    }

    fun write(context: Context, json: String) {
        try {
            val dir = context.applicationContext.filesDir
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(json)
            tmp.renameTo(File(dir, FILE))
        } catch (_: Exception) {
        }
    }

    fun load(context: Context): SavedQueue? = try {
        val f = File(context.applicationContext.filesDir, FILE)
        if (!f.exists()) {
            null
        } else {
            val json = JSONObject(f.readText())
            val arr = json.getJSONArray("items")
            val items = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                SavedItem(
                    song = songFromJson(o),
                    parent = o.optString("parent").takeIf { !o.isNull("parent") && it.isNotBlank() },
                    radio = o.optString("radio").takeIf { !o.isNull("radio") && it.isNotBlank() },
                )
            }
            if (items.isEmpty()) {
                null
            } else {
                SavedQueue(
                    items = items,
                    index = json.optInt("index").coerceIn(0, items.size - 1),
                    positionMs = json.optLong("position").coerceAtLeast(0L),
                    shuffle = json.optBoolean("shuffle"),
                    repeatMode = json.optInt("repeat"),
                )
            }
        }
    } catch (_: Exception) {
        null
    }

    fun clear(context: Context) {
        File(context.applicationContext.filesDir, FILE).delete()
    }

    private fun savedItemOf(item: MediaItem): SavedItem? {
        val md = item.mediaMetadata
        val uri = item.localConfiguration?.uri ?: return null
        val song = Song(
            id = item.mediaId,
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            albumArtist = md.albumArtist?.toString().orEmpty(),
            track = md.trackNumber ?: 0,
            disc = 1,
            durationSec = ((md.durationMs ?: 0L) / 1000).toInt(),
            ext = uri.lastPathSegment.orEmpty().substringAfterLast('.', ""),
        )
        return SavedItem(
            song,
            parent = md.extras?.getString(MediaItems.EXTRA_PARENT),
            radio = md.extras?.getString(MediaItems.EXTRA_RADIO),
        )
    }
}
