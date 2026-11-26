package io.github.inobody92.sillon.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.math.min

/*
 * Découvertes : chaque jour, quelques suggestions de musique que tu n'as pas encore — les sorties récentes de tes
 * artistes préférés, des artistes proches, et une ou deux « pépites » peu connues. Rien n'est téléchargé tout seul :
 * l'application propose, tu valides.
 *
 * Tout est calculé sur le téléphone à partir de tes goûts et de ta bibliothèque ; seules les questions « artistes
 * proches de X » et « derniers albums de X » partent chez Deezer (API publique, sans compte).
 */
object DiscoveryTuning {
    /** Suggestions affichées par jour, dont [UNDERGROUND_SLOTS] pépites. */
    const val DAILY_COUNT = 5
    const val UNDERGROUND_SLOTS = 2
    const val RELEASE_SLOTS = 2

    /** Artistes de départ (tes favoris) interrogés chez Deezer. */
    const val MAX_SEEDS = 12

    /** Une sortie est « récente » pendant ce nombre de jours. */
    const val RELEASE_DAYS = 365L

    /** « Pépite » : un artiste proche des tiens mais peu connu — entre ces deux nombres de fans Deezer. */
    const val UNDERGROUND_MIN_FANS = 800L
    const val UNDERGROUND_MAX_FANS = 100_000L

    /** Albums lus par artiste de départ avant de filtrer. */
    const val ALBUMS_LOOKED_UP = 8

    /** Artistes proches lus par artiste de départ, et nombre d'artistes de départ explorés à un deuxième pas. */
    const val RELATED_PER_ARTIST = 15
    const val SECOND_HOP_PARENTS = 8

    /** Une suggestion déjà faite (ou refusée) n'est pas reproposée avant ce délai (refus : jamais). */
    const val RENEW_AFTER_DAYS = 45L
}

/** Titre sans ses mentions entre parenthèses ou crochets et sans suffixe après un tiret : « Album (Deluxe Edition) » → « Album ». */
fun baseTitle(title: String): String =
    title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ").replace(Regex("\\s+-\\s+.*$"), " ").trim()

private val REISSUE = Regex("(?i)\\b(deluxe|remaster(ed)?|remasteris[ée]e?|anniversary|anniversaire|expanded|collector'?s?|reissue|edition|édition)\\b")

/** Une réédition (deluxe, remasterisé, anniversaire…) n'est pas une nouveauté. */
fun isReissue(title: String) = REISSUE.containsMatchIn(title)

data class DzArtist(val id: String, val name: String, val fans: Long?, val photo: String?)
data class DzAlbum(val id: String, val title: String, val date: String, val type: String, val cover: String?)

/** Ce qu'il faut savoir de Deezer ; remplaçable par un faux dans les tests. */
interface DiscoverySource {
    /** L'artiste dont le nom est exactement [name] (le plus de fans en cas d'homonymes), ou null. */
    suspend fun findArtist(name: String): DzArtist?
    suspend fun related(artistId: String, limit: Int): List<DzArtist>
    /** Albums et EP, du plus récent au plus ancien. */
    suspend fun latestAlbums(artistId: String, limit: Int): List<DzAlbum>
}

internal fun parseDzArtists(json: JSONObject): List<DzArtist> {
    val data = json.optJSONArray("data") ?: return emptyList()
    return List(data.length()) { data.getJSONObject(it) }.mapNotNull { a ->
        val id = a.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        DzArtist(
            id, a.optString("name"),
            if (a.has("nb_fan") && !a.isNull("nb_fan")) a.optLong("nb_fan") else null,
            a.optString("picture_medium").ifBlank { null },
        )
    }
}

internal fun parseDzAlbums(json: JSONObject): List<DzAlbum> {
    val data = json.optJSONArray("data") ?: return emptyList()
    return List(data.length()) { data.getJSONObject(it) }.mapNotNull { a ->
        val id = a.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val type = a.optString("record_type")
        val date = a.optString("release_date")
        // Pas de singles (on propose des albums) ni d'album sans date (impossible de dire s'il est récent).
        if (type !in setOf("album", "ep") || date.isBlank()) return@mapNotNull null
        DzAlbum(id, a.optString("title"), date, type, a.optString("cover_medium").ifBlank { null })
    }.sortedByDescending { it.date }
}

/** Deezer, via son API publique. Espace les requêtes (limite : 50 toutes les 5 s) et patiente si le quota est atteint. */
class DeezerSource(
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
    private val base: String = "https://api.deezer.com",
) : DiscoverySource {

    private val gate = Mutex()
    private var last = 0L

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        gate.withLock {
            val url = "$base/$path".toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
            var attempt = 0
            while (true) {
                val wait = last + 110 - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                last = System.currentTimeMillis()
                val json = http.newCall(Request.Builder().url(url).header("User-Agent", "Sillon").build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw java.io.IOException("Deezer : HTTP ${resp.code}")
                    JSONObject(resp.body?.string().orEmpty())
                }
                val error = json.optJSONObject("error")
                when {
                    error == null -> return@withLock json
                    error.optInt("code") == 4 && attempt++ < 2 -> delay(5_000) // quota dépassé : on patiente
                    else -> throw java.io.IOException("Deezer : ${error.optString("message", "erreur")}")
                }
            }
            @Suppress("UNREACHABLE_CODE") JSONObject()
        }
    }

    override suspend fun findArtist(name: String): DzArtist? =
        parseDzArtists(get("search/artist", mapOf("q" to name, "limit" to "25")))
            .filter { normalizeKey(it.name) == normalizeKey(name) }
            .maxByOrNull { it.fans ?: 0L }

    override suspend fun related(artistId: String, limit: Int): List<DzArtist> =
        parseDzArtists(get("artist/$artistId/related", mapOf("limit" to "$limit")))

    override suspend fun latestAlbums(artistId: String, limit: Int): List<DzAlbum> =
        parseDzAlbums(get("artist/$artistId/albums", mapOf("limit" to "50"))).take(limit)
}

enum class SuggestionKind { NEW_RELEASE, RELATED, UNDERGROUND }

/** Une suggestion du jour. [key] : "album:<id Deezer>" ou "artist:<id Deezer>" (sert à ne pas la reproposer). */
data class Suggestion(
    val key: String,
    val kind: SuggestionKind,
    val title: String,
    val artist: String,
    val reason: String,
    val cover: String?,
    val artistId: String,
    val albumId: String?,
    val fans: Long?,
    val date: String?,
) {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("kind", kind.name).put("title", title).put("artist", artist)
        .put("reason", reason).put("cover", cover ?: JSONObject.NULL).put("artistId", artistId)
        .put("albumId", albumId ?: JSONObject.NULL).put("fans", fans ?: JSONObject.NULL).put("date", date ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): Suggestion? = runCatching {
            Suggestion(
                o.getString("key"), SuggestionKind.valueOf(o.getString("kind")), o.getString("title"), o.getString("artist"),
                o.getString("reason"), o.optString("cover").ifBlank { null }, o.getString("artistId"),
                o.optString("albumId").ifBlank { null }, if (o.isNull("fans")) null else o.getLong("fans"),
                o.optString("date").ifBlank { null },
            )
        }.getOrNull()
    }
}

data class Seed(val name: String, val weight: Double)

/** Ce que le moteur doit éviter de proposer. */
data class DiscoveryFilters(
    /** Artistes déjà dans la bibliothèque (clé normalisée, voir [normalizeKey]). */
    val libraryArtists: Set<String>,
    /** Albums déjà dans la bibliothèque (clé de [albumKey]). */
    val libraryAlbums: Set<String>,
    /** Artistes masqués (par une règle), par nom. */
    val isHidden: (String) -> Boolean,
    /** Clés de suggestions refusées ou déjà proposées récemment ("artist:ID" / "album:ID"). */
    val excluded: Set<String>,
)

class DiscoveryEngine(private val source: DiscoverySource, private val today: () -> LocalDate = { LocalDate.now() }) {

    private class Candidate(val artist: DzArtist, var score: Double, val reasons: MutableList<String>, val hop: Int)

    suspend fun compute(
        seeds: List<Seed>,
        filters: DiscoveryFilters,
        count: Int = DiscoveryTuning.DAILY_COUNT,
        undergroundSlots: Int = DiscoveryTuning.UNDERGROUND_SLOTS,
        releaseSlots: Int = DiscoveryTuning.RELEASE_SLOTS,
    ): List<Suggestion> {
        val resolved = seeds.mapNotNull { seed ->
            runCatching { source.findArtist(seed.name) }.getOrNull()?.let { seed to it }
        }
        val seedIds = resolved.map { it.second.id }.toSet()
        val now = today()

        // 1. Sorties récentes des artistes de départ, absentes de la bibliothèque.
        val releases = ArrayList<Pair<Double, Suggestion>>()
        for ((seed, dz) in resolved) {
            if (filters.isHidden(dz.name)) continue
            // On lit plus d'albums qu'on n'en veut : les rééditions et ce qu'on possède déjà sont écartés ensuite.
            val albums = runCatching { source.latestAlbums(dz.id, DiscoveryTuning.ALBUMS_LOOKED_UP) }.getOrDefault(emptyList())
            for (album in albums) {
                val date = runCatching { LocalDate.parse(album.date) }.getOrNull() ?: continue
                val age = java.time.temporal.ChronoUnit.DAYS.between(date, now)
                if (age < 0 || age > DiscoveryTuning.RELEASE_DAYS) continue
                if (isReissue(album.title) || "album:${album.id}" in filters.excluded) continue
                // Déjà dans la bibliothèque, y compris sous une autre édition (« Album » et « Album (Deluxe Edition) »).
                if (albumKey(album.title, dz.name) in filters.libraryAlbums || albumKey(baseTitle(album.title), dz.name) in filters.libraryAlbums) continue
                // Plus c'est récent et plus l'artiste compte pour toi, plus ça remonte.
                val score = seed.weight * (1.0 - age.toDouble() / (DiscoveryTuning.RELEASE_DAYS * 1.2))
                val s = Suggestion(
                    "album:${album.id}", SuggestionKind.NEW_RELEASE, album.title, dz.name,
                    "Nouveauté de ${dz.name}", album.cover ?: dz.photo, dz.id, album.id, dz.fans, album.date,
                )
                releases.add(score to s)
            }
        }

        // 2. Artistes proches : un artiste cité par plusieurs de tes favoris remonte.
        fun blocked(a: DzArtist) = a.id in seedIds || normalizeKey(a.name) in filters.libraryArtists ||
            filters.isHidden(a.name) || "artist:${a.id}" in filters.excluded || a.name.isBlank()
        val candidates = LinkedHashMap<String, Candidate>()
        for ((seed, dz) in resolved) {
            val related = runCatching { source.related(dz.id, DiscoveryTuning.RELATED_PER_ARTIST) }.getOrDefault(emptyList())
            related.forEachIndexed { rank, r ->
                if (blocked(r)) return@forEachIndexed
                val gain = seed.weight * (1.0 + (related.size - rank).toDouble() / (related.size * 2.0))
                val c = candidates.getOrPut(r.id) { Candidate(r, 0.0, mutableListOf(), 1) }
                c.score += gain
                if (dz.name !in c.reasons) c.reasons.add(dz.name)
            }
        }

        // 3. Deuxième pas : les proches de tes proches, là où se trouvent les artistes moins connus.
        val parents = candidates.values.sortedByDescending { it.score }.take(DiscoveryTuning.SECOND_HOP_PARENTS)
        for (parent in parents) {
            val related = runCatching { source.related(parent.artist.id, DiscoveryTuning.RELATED_PER_ARTIST) }.getOrDefault(emptyList())
            for (r in related) {
                if (blocked(r) || r.id in candidates) continue
                candidates[r.id] = Candidate(r, parent.score * 0.5, mutableListOf(parent.artist.name), 2)
            }
        }

        fun isUnderground(c: Candidate) = (c.artist.fans ?: Long.MAX_VALUE) in
            DiscoveryTuning.UNDERGROUND_MIN_FANS until DiscoveryTuning.UNDERGROUND_MAX_FANS

        fun toSuggestion(c: Candidate, kind: SuggestionKind): Suggestion {
            val from = c.reasons.take(2).joinToString(" et ")
            val reason = when {
                kind == SuggestionKind.UNDERGROUND && c.hop == 2 -> "Pépite · dans l'univers de $from"
                kind == SuggestionKind.UNDERGROUND -> "Pépite · proche de $from"
                else -> "Parce que tu aimes $from"
            }
            return Suggestion("artist:${c.artist.id}", kind, c.artist.name, c.artist.name, reason, c.artist.photo, c.artist.id, null, c.artist.fans, null)
        }

        val chosen = LinkedHashMap<String, Suggestion>()
        fun add(s: Suggestion) { if (chosen.size < count) chosen.putIfAbsent(s.key, s) }
        val artistsWithRelease = HashSet<String>()

        // Une sortie par artiste, les plus pertinentes d'abord.
        releases.sortedByDescending { it.first }.forEach { (_, s) ->
            if (artistsWithRelease.size < releaseSlots && artistsWithRelease.add(s.artistId)) add(s)
        }
        val underground = candidates.values.filter(::isUnderground).sortedByDescending { it.score }
        val mainstream = candidates.values.filterNot(::isUnderground).sortedByDescending { it.score }
        val undergroundPicks = underground.take(undergroundSlots)
        // Les « classiques » prennent la place restante ; les pépites ont leurs places réservées.
        val roomForMainstream = count - chosen.size - undergroundPicks.size
        mainstream.take(roomForMainstream.coerceAtLeast(0)).forEach { add(toSuggestion(it, SuggestionKind.RELATED)) }
        undergroundPicks.forEach { add(toSuggestion(it, SuggestionKind.UNDERGROUND)) }
        // Il manque des suggestions : on complète avec ce qui reste, sans jamais dépasser [count].
        mainstream.forEach { add(toSuggestion(it, SuggestionKind.RELATED)) }
        underground.forEach { add(toSuggestion(it, SuggestionKind.UNDERGROUND)) }
        releases.sortedByDescending { it.first }.forEach { (_, s) -> add(s) }

        return order(chosen.values.toList())
    }

    /** Sorties d'abord, puis artistes proches, puis pépites. */
    private fun order(list: List<Suggestion>) = list.sortedBy { it.kind.ordinal }
}

/** Fabrique la liste des artistes de départ : tes favoris d'abord, puis ce que tu écoutes, puis le plus gros de ta bibliothèque. */
fun pickSeeds(
    profile: TasteProfile,
    librarySongs: List<Song>,
    recentSongs: List<Song>,
    isHidden: (String) -> Boolean,
    max: Int = DiscoveryTuning.MAX_SEEDS,
): List<Seed> {
    val out = LinkedHashMap<String, Seed>()
    fun add(name: String, weight: Double) {
        val key = normalizeKey(name)
        if (key.isEmpty() || isHidden(name) || key in out || out.size >= max) return
        out[key] = Seed(name.trim(), weight)
    }
    // 1. Artistes aimés (notes, likes, écoute) : la moyenne lissée au-dessus de 3,3 ; plus il y a de signaux, plus c'est sûr.
    profile.artists().filter { it.average >= 3.3 }.forEach { a ->
        val evidence = 1.0 + 0.2 * min(a.ratedCount + a.listenedCount, 5)
        add(a.name, ((a.average - 2.5) * evidence).coerceAtLeast(0.2))
    }
    // 2. Ce que tu as écouté récemment (démarrage à froid : peu de notes).
    recentSongs.groupingBy { artistName(it) }.eachCount().entries.sortedByDescending { it.value }
        .forEach { (name, n) -> add(name, 0.8 + 0.1 * min(n, 5)) }
    // 3. Les artistes les plus présents dans la bibliothèque.
    librarySongs.groupingBy { artistName(it) }.eachCount().entries.sortedByDescending { it.value }
        .forEach { (name, n) -> add(name, 0.5 + 0.05 * ln(1.0 + n)) }
    return out.values.toList()
}
