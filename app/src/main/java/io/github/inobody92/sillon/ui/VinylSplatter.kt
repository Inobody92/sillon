package io.github.inobody92.sillon.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Taches d'un vinyle « splatter » : couleurs tirées de la pochette, formes tirées au hasard mais toujours les
 * mêmes pour une même pochette (graine), pour que le disque ne change pas d'aspect à chaque ouverture.
 *
 * Les coordonnées sont en unités du rayon du disque, centre en (0,0).
 */
internal data class Dot(val x: Float, val y: Float, val radius: Float, val colorIndex: Int)

/** Secteur de couleur : un éventail qui part du centre et s'élargit vers le bord, aux côtés irréguliers. */
internal data class Wedge(val points: List<Pair<Float, Float>>, val colorIndex: Int, val alpha: Float = 0.88f)

/** Anneau concentrique plein (épaisseur en unités du rayon). */
internal data class Ring(val rho: Float, val width: Float, val colorIndex: Int)

internal enum class VinylPattern { CLASSIC, STRETCHED, SPIRAL, MARBLE, SOLID, RINGS }

/** Trait qui rayonne depuis le centre, épais côté centre, effilé vers le bord. [colorIndex] -1 = encre sombre. */
internal data class Streak(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val bend: Float, val width: Float, val colorIndex: Int)

/** [base] : couleur qui teinte tout le disque. */
internal data class Splatter(
    val pattern: VinylPattern,
    val base: Int,
    val wedges: List<Wedge> = emptyList(),
    val rings: List<Ring> = emptyList(),
    val streaks: List<Streak> = emptyList(),
    val dots: List<Dot> = emptyList(),
) {
    val isEmpty get() = wedges.isEmpty() && rings.isEmpty() && streaks.isEmpty() && dots.isEmpty()
}

/** Une couleur de la pochette et sa part (0..1) de la surface, neutres compris. */
internal data class WeightedColor(val color: Color, val share: Float)

/**
 * Couleurs dominantes de [pixels] (ARGB), au plus [max], avec leur part réelle de la pochette : les teintes vives
 * (regroupées par teinte), mais aussi le noir, le gris et le blanc quand ils pèsent vraiment. Les parts somment à 1.
 */
internal fun dominantColors(pixels: IntArray, max: Int = 4): List<WeightedColor> {
    // Catégories : 24 tranches de teinte (vives), puis sombre, clair, gris.
    val hueBins = 24
    val dark = hueBins
    val light = hueBins + 1
    val grey = hueBins + 2
    val n = hueBins + 3
    val count = IntArray(n)
    val r = FloatArray(n)
    val g = FloatArray(n)
    val b = FloatArray(n)
    var total = 0
    for (p in pixels) {
        if ((p ushr 24) < 128) continue
        val pr = ((p shr 16) and 255) / 255f
        val pg = ((p shr 8) and 255) / 255f
        val pb = (p and 255) / 255f
        val hi = max(pr, max(pg, pb))
        val lo = min(pr, min(pg, pb))
        val sat = if (hi == 0f) 0f else (hi - lo) / hi
        val bin = when {
            hi < 0.25f -> dark
            sat < 0.25f -> if (hi >= 0.7f) light else grey
            else -> {
                val delta = hi - lo
                var hue = when (hi) {
                    pr -> ((pg - pb) / delta) % 6f
                    pg -> (pb - pr) / delta + 2f
                    else -> (pr - pg) / delta + 4f
                }
                if (hue < 0f) hue += 6f
                ((hue / 6f) * hueBins).toInt().coerceIn(0, hueBins - 1)
            }
        }
        count[bin]++; r[bin] += pr; g[bin] += pg; b[bin] += pb
        total++
    }
    if (total == 0) return emptyList()

    // Neutres d'abord (ils comptent tels quels), puis les teintes par ordre de poids, écartées d'au moins 45°.
    val chosen = ArrayList<Int>()
    for (bin in (dark..grey).sortedByDescending { count[it] }) if (count[bin] > 0) chosen += bin
    val hues = ArrayList<Int>()
    for (bin in (0 until hueBins).sortedByDescending { count[it] }) {
        if (count[bin] == 0) break
        if (hues.all { c -> minOf((c - bin + hueBins) % hueBins, (bin - c + hueBins) % hueBins) >= 3 }) hues += bin
    }
    chosen += hues

    // Chaque tranche de teinte non retenue revient à la teinte retenue la plus proche : la part reste fidèle.
    val share = FloatArray(n)
    for (bin in 0 until n) {
        if (count[bin] == 0) continue
        val target = if (bin >= hueBins) bin else hues.minByOrNull { c -> minOf((c - bin + hueBins) % hueBins, (bin - c + hueBins) % hueBins) }
        if (target != null) share[target] += count[bin].toFloat() / total
    }
    val kept = chosen.filter { share[it] >= 0.06f }.sortedByDescending { share[it] }.take(max)
    if (kept.isEmpty()) return emptyList()
    val sum = kept.sumOf { share[it].toDouble() }.toFloat()
    return kept.map { WeightedColor(Color(r[it] / count[it], g[it] / count[it], b[it] / count[it]), share[it] / sum) }
}

/**
 * Chaque disque tire au sort un motif ([VinylPattern]) puis ses propres paramètres. Déterministe pour une graine
 * donnée, donc stable pour une même pochette. Un disque a toujours au moins un élément visible sauf sans couleur.
 */
internal fun splatterFor(seed: Int, shares: List<Float>): Splatter {
    val colorCount = shares.size
    if (colorCount <= 0) return Splatter(VinylPattern.SOLID, 0)
    val rnd = Random(seed)
    // Les motifs unis sont les plus sobres : on les tire moins souvent.
    val weights = mapOf(
        VinylPattern.CLASSIC to 3, VinylPattern.STRETCHED to 3, VinylPattern.SPIRAL to 2,
        VinylPattern.MARBLE to 3, VinylPattern.SOLID to 1, VinylPattern.RINGS to 2,
    )
    var pick = rnd.nextInt(weights.values.sum())
    val pattern = VinylPattern.entries.first { p -> (pick - weights.getValue(p)).also { pick = it } < 0 }

    // Le fond est la couleur dominante de la pochette dès qu'elle pèse vraiment (>= 35 %) : elle doit couvrir le
    // plus de disque. Sans dominante nette, tirage au prorata du carré des parts.
    val top = shares.indices.maxByOrNull { shares[it] } ?: 0
    val base = if (shares[top] >= 0.35f) top else pickWeighted(rnd, shares.map { it * it }, emptySet())
    val g = Gen(rnd, colorCount, base, shares)
    return when (pattern) {
        VinylPattern.CLASSIC -> g.classic()
        VinylPattern.STRETCHED -> g.stretched()
        VinylPattern.SPIRAL -> g.spiral()
        VinylPattern.MARBLE -> g.marble()
        VinylPattern.SOLID -> g.solid()
        VinylPattern.RINGS -> g.rings()
    }
}

private const val TAU = 2f * PI.toFloat()
private const val INNER = 0.30f
private const val RIM = 1.0f

/** Indice tiré au hasard, proportionnellement à [weights], hors [excluded] (le premier autorisé si tout est nul). */
private fun pickWeighted(rnd: Random, weights: List<Float>, excluded: Set<Int>): Int {
    val allowed = weights.indices.filter { it !in excluded }
    if (allowed.isEmpty()) return 0
    val total = allowed.sumOf { weights[it].toDouble() }.toFloat()
    if (total <= 0f) return allowed.first()
    var x = rnd.nextFloat() * total
    for (i in allowed) { x -= weights[i]; if (x < 0f) return i }
    return allowed.last()
}

private class Gen(val rnd: Random, val colorCount: Int, val base: Int, val shares: List<Float>) {
    /** Part de la pochette qui n'est pas la couleur de fond : plus elle est grande, plus il y a d'accents. */
    val amount = (1f - shares[base]).coerceIn(0.12f, 1f)
    fun scaled(n: Int) = maxOf(1, (n * (0.35f + 1.3f * amount)).toInt())
    fun between(lo: Float, hi: Float) = lo + rnd.nextFloat() * (hi - lo)
    /** Couleur d'accent : tirée au prorata de sa part sur la pochette, hors couleur de fond. */
    fun other() = if (colorCount < 2) 0 else pickWeighted(rnd, shares, setOf(base))
    /** Couleur différente de [not] et du fond si possible. */
    fun another(not: Int): Int {
        if (colorCount < 3) return other()
        return pickWeighted(rnd, shares, setOf(base, not))
    }

    /** Ruban le long d'une courbe : large au milieu, effilé aux bouts (ou constant si [taper] est faux). */
    fun ribbon(centre: List<Pair<Float, Float>>, width: Float, taper: Boolean = true): List<Pair<Float, Float>> {
        val n = centre.size
        val left = ArrayList<Pair<Float, Float>>()
        val right = ArrayList<Pair<Float, Float>>()
        for (i in 0 until n) {
            val a = centre[maxOf(i - 1, 0)]
            val b = centre[minOf(i + 1, n - 1)]
            val dx = b.first - a.first
            val dy = b.second - a.second
            val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-4f)
            val t = i / (n - 1f)
            val h = width / 2f * if (taper) (0.1f + 0.9f * sin(PI.toFloat() * t)) else 1f
            left += Pair(centre[i].first - dy / len * h, centre[i].second + dx / len * h)
            right += Pair(centre[i].first + dy / len * h, centre[i].second - dx / len * h)
        }
        return left + right.reversed()
    }

    fun classic(): Splatter {
        val wedges = ArrayList<Wedge>()
        val wedgeCount = if (colorCount < 2) 0 else (rnd.nextInt(7) * (0.4f + 1.2f * amount)).toInt()
        repeat(wedgeCount) {
            val centre = rnd.nextFloat() * TAU
            val half = between(0.08f, 0.75f)
            val ph = rnd.nextFloat() * TAU
            val ampA = between(0.04f, 0.2f)
            val ampB = between(0.0f, 0.07f)
            val fa = between(1.5f, 4f)
            val fb = between(5f, 11f)
            val spread = between(0.2f, 1.6f)
            val steps = 40
            fun edge(sign: Float, w: Float) = List(steps + 1) { k ->
                val t = k / steps.toFloat()
                val rho = INNER + (RIM + 0.04f - INNER) * t
                val h = half * (0.4f + spread * t) * (1f + ampA * sin(fa * t * PI.toFloat() + w) + ampB * sin(fb * t * PI.toFloat() + 2f * w))
                val ang = centre + sign * h
                Pair(rho * cos(ang), rho * sin(ang))
            }
            wedges += Wedge(edge(-1f, ph) + edge(1f, ph + between(0f, 3f)).reversed(), other())
        }

        val burstCount = 1 + rnd.nextInt(7)
        val bursts = List(burstCount) { Pair(rnd.nextFloat() * TAU, between(0.15f, 1.6f)) }
        val inkChance = between(0f, 1f)
        val reach = between(0.15f, 0.7f)
        val thick = between(0.012f, 0.045f)
        val curve = between(0f, 0.5f)
        val streaks = ArrayList<Streak>()
        val dots = ArrayList<Dot>()
        repeat(scaled(20 + rnd.nextInt(200))) {
            val (c, spread) = bursts[rnd.nextInt(burstCount)]
            val a = c + (rnd.nextFloat() - 0.5f) * spread * 2f
            val r0 = between(0.32f, 0.78f)
            val r1 = (r0 + 0.04f + rnd.nextFloat() * rnd.nextFloat() * reach).coerceAtMost(0.985f)
            val color = if (rnd.nextFloat() < inkChance || colorCount < 2) -1 else other()
            streaks += Streak(r0 * cos(a), r0 * sin(a), r1 * cos(a), r1 * sin(a), (rnd.nextFloat() - 0.5f) * curve,
                0.005f + rnd.nextFloat() * rnd.nextFloat() * thick, color)
            if (rnd.nextFloat() < 0.55f && r1 < 0.95f) {
                val radius = between(0.005f, 0.02f)
                val rd = (r1 + between(0.015f, 0.07f)).coerceAtMost(0.99f - radius)
                dots += Dot(rd * cos(a), rd * sin(a), radius, color)
            }
        }
        repeat(rnd.nextInt(25)) {
            val a = rnd.nextFloat() * TAU
            val rho = between(0.35f, 0.96f)
            dots += Dot(rho * cos(a), rho * sin(a), between(0.004f, 0.017f), if (rnd.nextBoolean()) -1 else other())
        }
        return Splatter(VinylPattern.CLASSIC, base, wedges = wedges, streaks = streaks, dots = dots)
    }

    /** Taches étirées sur fond uni : des gouttes tirées dans un même sens, comme étalées par la rotation. */
    fun stretched(): Splatter {
        val wedges = ArrayList<Wedge>()
        val dots = ArrayList<Dot>()
        val radial = rnd.nextBoolean()                 // étirées vers le bord, ou le long des sillons
        val skew = between(-0.9f, 0.9f)                // inclinaison commune, comme un coup de pinceau
        repeat(if (colorCount < 2) 0 else scaled(9 + rnd.nextInt(14))) {
            val a = rnd.nextFloat() * TAU
            val rho = between(0.38f, 0.9f)
            val long = between(0.14f, 0.55f) * if (rnd.nextFloat() < 0.2f) 1.6f else 1f
            val short = between(0.018f, 0.075f)
            val dir = (if (radial) a else a + PI.toFloat() / 2f) + skew * between(0.3f, 1f)
            val cx = rho * cos(a)
            val cy = rho * sin(a)
            val steps = 14
            val centre = List(steps + 1) { k ->
                val t = (k / steps.toFloat() - 0.5f) * long
                Pair(cx + t * cos(dir), cy + t * sin(dir))
            }.filter { (x, y) -> kotlin.math.hypot(x, y) in 0.3f..0.99f }
            if (centre.size >= 4) wedges += Wedge(ribbon(centre, short * 2f), other(), 0.9f)
        }
        repeat(rnd.nextInt(30)) {
            val a = rnd.nextFloat() * TAU
            val rho = between(0.35f, 0.96f)
            dots += Dot(rho * cos(a), rho * sin(a), between(0.004f, 0.018f), other())
        }
        return Splatter(VinylPattern.STRETCHED, base, wedges = wedges, dots = dots)
    }

    /** Spirale : une ou deux bandes qui s'enroulent du centre vers le bord. */
    fun spiral(): Splatter {
        if (colorCount < 2) return Splatter(VinylPattern.SPIRAL, base)
        val turns = between(2.5f, 7f)
        val pitch = (RIM + 0.05f - INNER) / turns
        val dir = if (rnd.nextBoolean()) 1f else -1f
        val start = rnd.nextFloat() * TAU
        val bands = if (colorCount >= 3 && rnd.nextBoolean()) 2 else 1
        val frac = between(0.15f, 0.5f) * (0.4f + 0.8f * amount).coerceAtMost(1f) / bands
        val steps = (turns * 48).toInt()
        val wedges = List(bands) { b ->
            val offset = b * pitch / bands
            val outline = List(steps + 1) { k ->
                val t = k / steps.toFloat()
                val rho = INNER - 0.05f + offset + t * turns * pitch
                Triple(rho, start + dir * t * turns * TAU, 0f)
            }.filter { it.first < RIM + 0.06f }
            val w = pitch * frac
            val outer = outline.map { (r, a, _) -> Pair((r + w) * cos(a), (r + w) * sin(a)) }
            val innerSide = outline.map { (r, a, _) -> Pair((r - w) * cos(a), (r - w) * sin(a)) }
            Wedge(outer + innerSide.reversed(), if (b == 0) other() else another(base), 0.92f)
        }
        return Splatter(VinylPattern.SPIRAL, base, wedges = wedges)
    }

    /**
     * Marbré : des bandes qui font tout le tour du disque, déformées en douceur, d'épaisseur variable, et qui se
     * chevauchent en translucide (comme du papier marbré), striées de veines fines.
     */
    fun marble(): Splatter {
        val wedges = ArrayList<Wedge>()
        if (colorCount >= 2) {
            val bands = 3 + rnd.nextInt(3) + (if (amount > 0.6f) 1 else 0)
            repeat(bands) {
                val r0 = between(0.34f, 1.0f)
                val width = between(0.025f, 0.09f)
                // Déformation lente (1 à 4 ondulations par tour) : pas de bosses isolées, des courants.
                val f1 = 1 + rnd.nextInt(4)
                val f2 = 2 + rnd.nextInt(4)
                val a1 = between(0.02f, 0.07f)
                val a2 = between(0.0f, 0.03f)
                val p1 = rnd.nextFloat() * TAU
                val p2 = rnd.nextFloat() * TAU
                val fw = 1 + rnd.nextInt(3)             // la bande s'épaissit et s'amincit
                val pw = rnd.nextFloat() * TAU
                val n = 140
                val outer = ArrayList<Pair<Float, Float>>()
                val innerSide = ArrayList<Pair<Float, Float>>()
                for (k in 0..n) {
                    val th = k / n.toFloat() * TAU
                    val centre = r0 + a1 * sin(f1 * th + p1) + a2 * sin(f2 * th + p2)
                    val w = width * (0.12f + 0.88f * (0.5f + 0.5f * sin(fw * th + pw)))
                    val ro = (centre + w).coerceAtMost(1.1f)
                    val ri = (centre - w).coerceAtLeast(0.2f)
                    outer += Pair(ro * cos(th), ro * sin(th))
                    innerSide += Pair(ri * cos(th), ri * sin(th))
                }
                wedges += Wedge(outer + innerSide.reversed(), other(), between(0.4f, 0.7f))
            }
            // Veines fines par-dessus.
            repeat(2 + rnd.nextInt(3)) {
                val rho = between(0.42f, 0.95f)
                val start = rnd.nextFloat() * TAU
                val sweep = between(1.0f, 3.6f)
                val a1 = between(0.02f, 0.08f)
                val f1 = 2 + rnd.nextInt(3)
                val p1 = rnd.nextFloat() * TAU
                val drift = between(-0.2f, 0.2f)
                val pts = List(50) { k ->
                    val t = k / 49f
                    val ang = start + sweep * t
                    val r = (rho + drift * t + a1 * sin(f1 * ang + p1)).coerceIn(0.33f, 0.98f)
                    Pair(r * cos(ang), r * sin(ang))
                }
                wedges += Wedge(ribbon(pts, between(0.008f, 0.03f)), another(base), between(0.5f, 0.8f))
            }
        }
        return Splatter(VinylPattern.MARBLE, base, wedges = wedges)
    }

    /** Couleurs unies : le disque coupé en 2 à 4 parts nettes, ou une seule couleur. */
    fun solid(): Splatter {
        if (colorCount < 2) return Splatter(VinylPattern.SOLID, base)
        val parts = if (amount < 0.3f) 2 else 2 + rnd.nextInt(3)
        val start = rnd.nextFloat() * TAU
        val wedges = ArrayList<Wedge>()
        for (i in 1 until parts) {
            if (i % 2 == 0 && colorCount < 3) continue
            val a0 = start + i * TAU / parts
            val a1 = a0 + TAU / parts
            val pts = listOf(Pair(0f, 0f)) + List(24) { k -> val a = a0 + (a1 - a0) * k / 23f; Pair(1.05f * cos(a), 1.05f * sin(a)) }
            wedges += Wedge(pts, if (i % 2 == 1) other() else another(base), 0.95f)
        }
        return Splatter(VinylPattern.SOLID, base, wedges = wedges)
    }

    /** Anneaux concentriques colorés, de largeurs variées. */
    fun rings(): Splatter {
        if (colorCount < 2) return Splatter(VinylPattern.RINGS, base)
        val rings = ArrayList<Ring>()
        var rho = INNER + between(0.0f, 0.06f)
        var color = other()
        while (rho < RIM) {
            val w = between(0.03f, 0.16f)
            if (rnd.nextFloat() < 0.65f) rings += Ring(rho + w / 2f, w, color)
            color = another(color).takeIf { it != base } ?: other()
            rho += w + between(0.0f, 0.12f)
        }
        return Splatter(VinylPattern.RINGS, base, rings = rings)
    }
}
