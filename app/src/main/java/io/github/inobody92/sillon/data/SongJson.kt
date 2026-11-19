package io.github.inobody92.sillon.data

import org.json.JSONArray
import org.json.JSONObject

/** Sérialisation d'un titre, partagée par le mix du jour et l'index des téléchargements. */
fun Song.toJson(): JSONObject = JSONObject()
    .put("id", id).put("title", title).put("artist", artist).put("album", album)
    .put("albumArtist", albumArtist).put("track", track).put("disc", disc)
    .put("duration", durationSec).put("ext", ext).put("genre", genre).put("year", year)

fun songFromJson(o: JSONObject): Song = Song(
    id = o.getString("id"),
    title = o.optString("title"),
    artist = o.optString("artist"),
    album = o.optString("album"),
    albumArtist = o.optString("albumArtist"),
    track = o.optInt("track"),
    disc = o.optInt("disc", 1),
    durationSec = o.optInt("duration"),
    ext = o.optString("ext"),
    genre = o.optString("genre"),
    year = o.optInt("year"),
)

fun List<Song>.toJsonArray(): JSONArray = JSONArray().also { arr -> forEach { arr.put(it.toJson()) } }

fun songsFromJson(arr: JSONArray): List<Song> = List(arr.length()) { songFromJson(arr.getJSONObject(it)) }
