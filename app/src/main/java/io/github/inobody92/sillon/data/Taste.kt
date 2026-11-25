package io.github.inobody92.sillon.data

import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/**
 * Goûts de l'utilisateur, déduits des notes (1 à 5 étoiles) : une moyenne par artiste et par genre, qui sert à
 * pousser les titres d'artistes et de genres appréciés dans le mix du jour et les radios.
 *
 * Tout est volontairement simple et réglable par les constantes ci-dessous.
 */
object TasteTuning {
    /** Poids de la moyenne globale dans la moyenne d'un artiste ou d'un genre (équivaut à ce nombre de notes « moyennes »). */
    const val PRIOR_WEIGHT = 3.0

    /** Part de l'artiste (le reste vient du genre) dans l'affinité d'un titre. */
    const val ARTIST_SHARE = 0.65

    /** Part de la note du titre lui-même quand il est noté (le reste vient de l'affinité artiste/genre). */
    const val OWN_RATING_SHARE = 0.8

    /** Raideur de la courbe note → poids : 0 = tout égal. Avec 0,55 : 5★ ≈ 3 fois plus de chances que 3★, 1★ ≈ 3 fois moins. */
    const val STEEPNESS = 0.55

    /** Un titre noté 1★ est quasiment écarté. */
    const val ONE_STAR_PENALTY = 0.2

    /** Une exposition de plus divise les chances par (1 + n)^EXPOSURE_POWER. */
    const val EXPOSURE_POWER = 0.8

    /** Les expositions comptent pour moitié après ce nombre de jours : un titre peut revenir avec le temps. */
    const val EXPOSURE_HALF_LIFE_DAYS = 30.0

    /** Un titre écouté récemment (jours de l'historique d'écoute) a ce facteur de chances. */
    const val RECENT_PLAY_FACTOR = 0.25

    /** Nombre maximal de titres d'un même artiste dans une sélection : évite qu'un favori occupe tout le mix. */
    const val MAX_PER_ARTIST = 3
}

internal fun normalizeKey(text: String): String =
    Normalizer.normalize(text.trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/** Clé d'artiste d'un titre : l'artiste de l'album (les invités n'éparpillent pas les notes), sinon l'artiste du titre. */
fun artistKey(song: Song): String = normalizeKey(song.albumArtist.ifBlank { song.artist })
fun artistName(song: Song): String = song.albumArtist.ifBlank { song.artist }.trim()
fun genreKey(song: Song): String = normalizeKey(song.genre)

/** Un titre noté, avec ce qu'il faut pour calculer les moyennes. */
data class RatedSong(val id: String, val rating: Int, val artistName: String, val genreName: String) {
    val artistKey get() = normalizeKey(artistName)
    val genreKey get() = normalizeKey(genreName)
}

/** Moyenne lissée d'un artiste ou d'un genre. */
data class Affinity(val name: String, val average: Double, val ratedCount: Int)

class TasteProfile(rated: List<RatedSong>) {

    private val ratings: Map<String, Int> = rated.associate { it.id to it.rating }
    val globalMean: Double = if (rated.isEmpty()) NEUTRAL else rated.sumOf { it.rating }.toDouble() / rated.size
    val ratedCount: Int get() = ratings.size

    private val artistStats = group(rated, { it.artistKey }, { it.artistName })
    private val genreStats = group(rated, { it.genreKey }, { it.genreName })

    private class Stat(val name: String, var sum: Int = 0, var count: Int = 0)

    private fun group(rated: List<RatedSong>, key: (RatedSong) -> String, name: (RatedSong) -> String): Map<String, Stat> {
        val map = HashMap<String, Stat>()
        rated.forEach { r ->
            val k = key(r)
            if (k.isEmpty()) return@forEach
            val s = map.getOrPut(k) { Stat(name(r).ifBlank { k }) }
            s.sum += r.rating
            s.count++
        }
        return map
    }

    private fun smoothed(s: Stat?): Double? =
        s?.let { (it.sum + TasteTuning.PRIOR_WEIGHT * globalMean) / (it.count + TasteTuning.PRIOR_WEIGHT) }

    fun artistAverage(key: String): Double? = smoothed(artistStats[key])
    fun genreAverage(key: String): Double? = smoothed(genreStats[key])

    /** Artistes notés, du mieux au moins bien noté. */
    fun artists(): List<Affinity> = artistStats.values.map { Affinity(it.name, smoothed(it)!!, it.count) }
        .sortedWith(compareByDescending<Affinity> { it.average }.thenByDescending { it.ratedCount })

    fun genres(): List<Affinity> = genreStats.values.map { Affinity(it.name, smoothed(it)!!, it.count) }
        .sortedWith(compareByDescending<Affinity> { it.average }.thenByDescending { it.ratedCount })

    /** Note estimée de 1 à 5 : celle du titre s'il est noté, mélangée à l'affinité de son artiste et de son genre. */
    fun score(song: Song): Double {
        val a = artistAverage(artistKey(song)) ?: globalMean
        val g = genreAverage(genreKey(song)) ?: globalMean
        val affinity = TasteTuning.ARTIST_SHARE * a + (1 - TasteTuning.ARTIST_SHARE) * g
        val own = ratings[song.id] ?: return affinity
        return TasteTuning.OWN_RATING_SHARE * own + (1 - TasteTuning.OWN_RATING_SHARE) * affinity
    }

    /** Poids de tirage lié aux goûts : 1 = neutre. Sans aucune note, tout vaut 1. */
    fun weight(song: Song): Double {
        if (ratings.isEmpty()) return 1.0
        val w = exp(TasteTuning.STEEPNESS * (score(song) - NEUTRAL))
        return if (ratings[song.id] == 1) w * TasteTuning.ONE_STAR_PENALTY else w
    }

    companion object {
        const val NEUTRAL = 3.0
    }
}

/** Combien de fois un titre est « tombé » dans une sélection, et quand pour la dernière fois. */
data class ExposureEntry(val count: Int, val lastMs: Long)

object Exposure {
    private const val DAY_MS = 24L * 3600_000L

    /** Nombre d'expositions, atténué par le temps écoulé depuis la dernière. */
    fun effectiveCount(entry: ExposureEntry?, now: Long): Double {
        entry ?: return 0.0
        val days = ((now - entry.lastMs).coerceAtLeast(0L)).toDouble() / DAY_MS
        return entry.count * 0.5.pow(days / TasteTuning.EXPOSURE_HALF_LIFE_DAYS)
    }

    /** Facteur de chances : 1 pour un titre jamais tiré, de plus en plus petit à chaque tirage. */
    fun factor(entry: ExposureEntry?, now: Long): Double =
        (1.0 + effectiveCount(entry, now)).pow(-TasteTuning.EXPOSURE_POWER)
}

/**
 * Tire [n] éléments sans remise, avec une probabilité proportionnelle à leur poids (méthode d'Efraimidis-Spirakis :
 * chaque élément reçoit la clé u^(1/poids), on garde les plus grandes). [maxPerGroup] limite les éléments d'un même groupe.
 */
fun <T> weightedSample(
    items: List<T>,
    n: Int,
    random: Random,
    weight: (T) -> Double,
    group: (T) -> String = { "" },
    maxPerGroup: Int = Int.MAX_VALUE,
): List<T> {
    val keyed = items.map { item ->
        val w = weight(item).coerceAtLeast(1e-9)
        // ln(u)/w est croissant avec u^(1/w) et évite les dépassements de précision.
        item to ln(random.nextDouble().coerceAtLeast(1e-12)) / w
    }.sortedByDescending { it.second }
    val perGroup = HashMap<String, Int>()
    val out = ArrayList<T>(n)
    for ((item, _) in keyed) {
        if (out.size >= n) break
        val g = group(item)
        val used = perGroup.getOrDefault(g, 0)
        if (g.isNotEmpty() && used >= maxPerGroup) continue
        perGroup[g] = used + 1
        out.add(item)
    }
    return out
}
