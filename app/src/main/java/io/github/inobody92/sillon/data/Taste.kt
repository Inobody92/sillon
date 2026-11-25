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

    /** Une note, un like ou un titre masqué pèsent plein pot ; une écoute seule (titre fini, titre passé) beaucoup moins. */
    const val EXPLICIT_WEIGHT = 1.0
    const val IMPLICIT_WEIGHT_PER_EVENT = 0.2
    const val IMPLICIT_WEIGHT_MAX = 0.6

    /** Un titre écouté en entier compte pour cette hausse de note estimée, un titre passé pour cette baisse (plafonnées). */
    const val COMPLETE_BONUS = 0.5
    const val SKIP_PENALTY = 0.8

    /** Nombre maximal de titres d'un même artiste dans une sélection : évite qu'un favori occupe tout le mix. */
    const val MAX_PER_ARTIST = 3
}

internal fun normalizeKey(text: String): String =
    Normalizer.normalize(text.trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/** Clé d'artiste d'un titre : l'artiste de l'album (les invités n'éparpillent pas les notes), sinon l'artiste du titre. */
fun artistKey(song: Song): String = normalizeKey(song.albumArtist.ifBlank { song.artist })
fun artistName(song: Song): String = song.albumArtist.ifBlank { song.artist }.trim()
fun genreKey(song: Song): String = normalizeKey(song.genre)

/** Un titre noté (1 à 5), avec ce qu'il faut pour calculer les moyennes. */
data class RatedSong(val id: String, val rating: Int, val artistName: String, val genreName: String)

/**
 * Ce qu'on sait du goût de l'utilisateur pour un titre : une valeur de 1 à 5 et la confiance qu'on lui accorde
 * ([weight], 1 = une vraie note). Viennent d'une note, d'un like (= 5), d'un titre masqué (= 1) ou de l'écoute.
 */
data class TasteSignal(val id: String, val value: Double, val weight: Double, val artistName: String, val genreName: String) {
    val artistKey get() = normalizeKey(artistName)
    val genreKey get() = normalizeKey(genreName)
    val explicit get() = weight >= TasteTuning.EXPLICIT_WEIGHT
}

/** Valeur et poids déduits de l'écoute seule : titres écoutés en entier ([completes]) et titres passés ([skips]). */
fun implicitSignal(id: String, completes: Int, skips: Int, artistName: String, genreName: String): TasteSignal? {
    if (completes + skips <= 0) return null
    val value = (3.0 + TasteTuning.COMPLETE_BONUS * minOf(completes, 4) - TasteTuning.SKIP_PENALTY * minOf(skips, 3)).coerceIn(1.0, 5.0)
    val weight = minOf(TasteTuning.IMPLICIT_WEIGHT_MAX, TasteTuning.IMPLICIT_WEIGHT_PER_EVENT * (completes + skips))
    return TasteSignal(id, value, weight, artistName, genreName)
}

/** Moyenne lissée d'un artiste ou d'un genre. [ratedCount] : titres notés, likés ou masqués ; [listenedCount] : titres connus par l'écoute seule. */
data class Affinity(val name: String, val average: Double, val ratedCount: Int, val listenedCount: Int = 0)

class TasteProfile(signals: List<TasteSignal>) {

    private val byId: Map<String, TasteSignal> = signals.associateBy { it.id }
    private val totalWeight = signals.sumOf { it.weight }
    val globalMean: Double = if (totalWeight <= 0.0) NEUTRAL else signals.sumOf { it.value * it.weight } / totalWeight

    /** Titres notés, likés ou masqués (les écoutes seules ne comptent pas ici). */
    val ratedCount: Int = signals.count { it.explicit }

    private val artistStats = group(signals, { it.artistKey }, { it.artistName })
    private val genreStats = group(signals, { it.genreKey }, { it.genreName })

    private class Stat(val name: String, var sumWeighted: Double = 0.0, var sumWeight: Double = 0.0, var explicit: Int = 0, var implicit: Int = 0)

    private fun group(signals: List<TasteSignal>, key: (TasteSignal) -> String, name: (TasteSignal) -> String): Map<String, Stat> {
        val map = HashMap<String, Stat>()
        signals.forEach { sig ->
            val k = key(sig)
            if (k.isEmpty()) return@forEach
            val s = map.getOrPut(k) { Stat(name(sig).ifBlank { k }) }
            s.sumWeighted += sig.value * sig.weight
            s.sumWeight += sig.weight
            if (sig.explicit) s.explicit++ else s.implicit++
        }
        return map
    }

    private fun smoothed(s: Stat?): Double? =
        s?.let { (it.sumWeighted + TasteTuning.PRIOR_WEIGHT * globalMean) / (it.sumWeight + TasteTuning.PRIOR_WEIGHT) }

    fun artistAverage(key: String): Double? = smoothed(artistStats[key])
    fun genreAverage(key: String): Double? = smoothed(genreStats[key])

    private fun affinities(stats: Map<String, Stat>) = stats.values.map { Affinity(it.name, smoothed(it)!!, it.explicit, it.implicit) }
        .sortedWith(compareByDescending<Affinity> { it.average }.thenByDescending { it.ratedCount + it.listenedCount })

    /** Artistes connus, du mieux au moins bien apprécié. */
    fun artists(): List<Affinity> = affinities(artistStats)
    fun genres(): List<Affinity> = affinities(genreStats)

    /** Note estimée de 1 à 5 : celle du titre s'il en a une, mélangée à l'affinité de son artiste et de son genre. */
    fun score(song: Song): Double {
        val a = artistAverage(artistKey(song)) ?: globalMean
        val g = genreAverage(genreKey(song)) ?: globalMean
        val affinity = TasteTuning.ARTIST_SHARE * a + (1 - TasteTuning.ARTIST_SHARE) * g
        val own = byId[song.id] ?: return affinity
        val share = (TasteTuning.OWN_RATING_SHARE * own.weight).coerceIn(0.0, TasteTuning.OWN_RATING_SHARE)
        return share * own.value + (1 - share) * affinity
    }

    /** Poids de tirage lié aux goûts : 1 = neutre. Sans aucun signal, tout vaut 1. */
    fun weight(song: Song): Double {
        if (byId.isEmpty()) return 1.0
        val w = exp(TasteTuning.STEEPNESS * (score(song) - NEUTRAL))
        val own = byId[song.id]
        return if (own != null && own.explicit && own.value <= 1.0) w * TasteTuning.ONE_STAR_PENALTY else w
    }

    companion object {
        const val NEUTRAL = 3.0

        /** Profil construit à partir de notes seules. */
        fun fromRatings(rated: List<RatedSong>) =
            TasteProfile(rated.map { TasteSignal(it.id, it.rating.toDouble(), TasteTuning.EXPLICIT_WEIGHT, it.artistName, it.genreName) })
    }
}

/** Ce que l'écoute d'un titre nous apprend. */
enum class ListenEvent { COMPLETED, SKIPPED }

/**
 * Qualifie la fin d'un titre à partir de ce qui a été écouté. Un titre fini (enchaînement automatique ou presque
 * entièrement écouté) est un signe positif ; un titre passé à la main après quelques secondes est un signe négatif.
 * Moins de [MIN_SKIP_MS] : on cherche simplement un titre, ce n'est pas un avis. Sans signification : null.
 */
fun classifyListen(positionMs: Long, durationMs: Long, automatic: Boolean): ListenEvent? {
    if (automatic) return ListenEvent.COMPLETED
    if (durationMs > 0 && positionMs >= (durationMs * 0.85).toLong()) return ListenEvent.COMPLETED
    if (positionMs < MIN_SKIP_MS) return null
    val limit = if (durationMs > 0) minOf(MAX_SKIP_MS, (durationMs * 0.4).toLong()) else MAX_SKIP_MS
    return if (positionMs < limit) ListenEvent.SKIPPED else null
}

const val MIN_SKIP_MS = 4_000L
const val MAX_SKIP_MS = 30_000L

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
