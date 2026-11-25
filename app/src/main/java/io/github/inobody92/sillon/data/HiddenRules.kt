package io.github.inobody92.sillon.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

enum class RuleKind(val id: String) { ARTIST("artist"), ALBUM("album");
    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } }
}

/**
 * « Ne plus jamais écouter cet artiste / cet album » : une règle, pas une liste de titres. Elle vaut donc aussi
 * pour les titres ajoutés plus tard. [on] faux = règle retirée, gardée pour que le retrait se propage entre appareils.
 */
data class HideRule(
    val kind: RuleKind,
    val key: String,
    val name: String,
    /** Pour un album : son artiste, pour l'affichage. */
    val artist: String,
    val on: Boolean,
    val updatedAt: Long,
) {
    val id: String get() = "${kind.id}:$key"
}

/** Clés de correspondance : mêmes accents/casse ignorés que pour les goûts, et l'artiste de l'album l'emporte sur celui du titre. */
fun albumKey(albumName: String, artistName: String): String = normalizeKey(albumName) + "|" + normalizeKey(artistName)
fun songAlbumKey(song: Song): String = albumKey(song.album, song.albumArtist.ifBlank { song.artist })

fun artistRule(name: String, now: Long = System.currentTimeMillis(), on: Boolean = true) =
    HideRule(RuleKind.ARTIST, normalizeKey(name), name.trim(), "", on, now)

fun albumRule(album: Album, now: Long = System.currentTimeMillis(), on: Boolean = true) =
    HideRule(RuleKind.ALBUM, albumKey(album.name, album.displayArtist), album.name.trim(), album.displayArtist.trim(), on, now)

/** Vrai si une règle active vise ce titre (par son artiste ou son album). */
fun matchesRule(rules: Collection<HideRule>, artist: String, albumArtist: String, album: String): Boolean {
    if (rules.isEmpty()) return false
    val artistKey = normalizeKey(albumArtist.ifBlank { artist })
    val albumKey = albumKey(album, albumArtist.ifBlank { artist })
    return rules.any { it.on && ((it.kind == RuleKind.ARTIST && it.key == artistKey) || (it.kind == RuleKind.ALBUM && it.key == albumKey)) }
}

/** Fusion de deux jeux de règles : pour chaque règle, la modification la plus récente l'emporte. */
fun mergeRules(local: Map<String, HideRule>, remote: Map<String, HideRule>): Map<String, HideRule> {
    val out = HashMap(local)
    remote.forEach { (id, r) ->
        val l = out[id]
        if (l == null || r.updatedAt > l.updatedAt) out[id] = r
    }
    return out
}

/** Règles de masquage de ce compte. Gardées sur le téléphone et sauvegardées sur le NAS avec le profil ([NasProfile]). */
object HiddenRules {

    private val _state = MutableStateFlow<Map<String, HideRule>>(emptyMap())
    val state: StateFlow<Map<String, HideRule>> = _state.asStateFlow()
    private lateinit var file: File

    /** Règles actives. */
    val active: List<HideRule> get() = _state.value.values.filter { it.on }

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.applicationContext.filesDir, "hidden_rules.json")
        runCatching {
            if (!file.exists()) return@runCatching
            val arr = JSONArray(file.readText())
            _state.value = buildMap { for (i in 0 until arr.length()) decode(arr.getJSONArray(i))?.let { put(it.id, it) } }
        }
    }

    fun matches(artist: String, albumArtist: String, album: String) = matchesRule(active, artist, albumArtist, album)

    fun matches(song: Song) = matches(song.artist, song.albumArtist, song.album)

    fun isArtistHidden(name: String) = _state.value["${RuleKind.ARTIST.id}:${normalizeKey(name)}"]?.on == true
    fun isAlbumHidden(album: Album) = _state.value["${RuleKind.ALBUM.id}:${albumKey(album.name, album.displayArtist)}"]?.on == true

    fun hideArtist(name: String) = put(artistRule(name))
    fun unhideArtist(name: String) = put(artistRule(name, on = false))
    fun hideAlbum(album: Album) = put(albumRule(album))
    fun unhideAlbum(album: Album) = put(albumRule(album, on = false))
    fun remove(rule: HideRule) = put(rule.copy(on = false, updatedAt = System.currentTimeMillis()))

    private fun put(rule: HideRule) {
        _state.value = _state.value + (rule.id to rule)
        save()
        NasProfile.requestPush()
    }

    /** Reprend les règles sauvegardées sur le NAS. Vrai si quelque chose a changé ici. */
    fun mergeFrom(remote: Map<String, HideRule>): Boolean {
        val merged = mergeRules(_state.value, remote)
        if (merged == _state.value) return false
        _state.value = merged
        save()
        return true
    }

    fun snapshot(): Map<String, HideRule> = _state.value

    internal fun encode(r: HideRule) = JSONArray(listOf(r.kind.id, r.key, r.name, r.artist, r.on, r.updatedAt))

    internal fun decode(a: JSONArray): HideRule? = runCatching {
        HideRule(RuleKind.of(a.getString(0)) ?: return null, a.getString(1), a.getString(2), a.getString(3), a.getBoolean(4), a.getLong(5))
    }.getOrNull()

    private fun save() {
        runCatching {
            val arr = JSONArray()
            _state.value.values.forEach { arr.put(encode(it)) }
            file.writeText(arr.toString())
        }
    }
}
