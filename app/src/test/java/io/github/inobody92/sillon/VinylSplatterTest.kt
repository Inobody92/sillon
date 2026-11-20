package io.github.inobody92.sillon

import io.github.inobody92.sillon.ui.dominantColors
import io.github.inobody92.sillon.ui.VinylPattern
import io.github.inobody92.sillon.ui.splatterFor
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VinylSplatterTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun `les parts de couleur suivent la pochette`() {
        val px = IntArray(100) { if (it < 60) argb(220, 30, 30) else argb(30, 60, 220) }
        val c = dominantColors(px)
        assertEquals(2, c.size)
        assertTrue(c[0].color.red > 0.7f && c[0].color.blue < 0.3f)   // rouge d'abord
        assertTrue(c[1].color.blue > 0.7f && c[1].color.red < 0.3f)
        assertEquals(0.6f, c[0].share, 0.01f)
        assertEquals(0.4f, c[1].share, 0.01f)
    }

    @Test fun `bleu pour moitie, noir et rouge pour le reste`() {
        val px = IntArray(100) { when { it < 50 -> argb(20, 60, 230); it < 80 -> argb(5, 5, 5); else -> argb(220, 30, 30) } }
        val c = dominantColors(px)
        assertEquals(3, c.size)
        assertTrue(c[0].color.blue > 0.8f)
        assertEquals(0.5f, c[0].share, 0.01f)
        assertEquals(0.3f, c[1].share, 0.01f)   // le noir compte
        assertEquals(1f, c.sumOf { it.share.toDouble() }.toFloat(), 0.001f)
    }

    @Test fun `deux nuances voisines ne donnent qu'une couleur`() {
        val px = IntArray(100) { if (it % 2 == 0) argb(220, 30, 30) else argb(220, 60, 30) }
        val c = dominantColors(px)
        assertEquals(1, c.size)
        assertEquals(1f, c[0].share, 0.001f)
    }

    @Test fun `pochette sans couleur ni pixel opaque`() {
        assertEquals(1, dominantColors(IntArray(50) { argb(20, 20, 20) }).size)
        assertTrue(dominantColors(IntArray(50) { 0 }).isEmpty())
        assertTrue(dominantColors(IntArray(0)).isEmpty())
    }

    @Test fun `la couleur dominante teinte le disque`() {
        // Bleu à 50 % : c'est le fond de chaque disque, quel que soit le motif tiré.
        for (seed in 0 until 300) assertEquals(0, splatterFor(seed, listOf(0.5f, 0.3f, 0.2f)).base)
        // Sans dominante nette, le fond reste l'une des couleurs, la plus présente plus souvent.
        val counts = IntArray(3)
        for (seed in 0 until 600) counts[splatterFor(seed, listOf(0.34f, 0.33f, 0.33f)).base]++
        assertTrue(counts.all { it > 0 })
    }

    @Test fun `les taches sont stables pour une meme pochette et restent sur le disque`() {
        val a = splatterFor(1234, listOf(0.5f, 0.3f, 0.2f))
        assertEquals(a, splatterFor(1234, listOf(0.5f, 0.3f, 0.2f)))
        assertTrue(splatterFor(1, emptyList()).isEmpty)
        for (seed in 0 until 600) {
            val sp = splatterFor(seed, listOf(0.4f, 0.35f, 0.25f))
            assertTrue(sp.base in 0..2)
            sp.wedges.forEach { w ->
                assertTrue(w.colorIndex in 0..2 && w.alpha in 0f..1f)
                w.points.forEach { (x, y) -> assertTrue("$seed ${sp.pattern}", hypot(x, y) < 2.0f) }
            }
            sp.rings.forEach { r -> assertTrue(r.colorIndex in 0..2 && r.rho + r.width / 2f <= 1.3f) }
            sp.streaks.forEach { st ->
                assertTrue(st.colorIndex in -1..2 && st.colorIndex != sp.base)
                assertTrue(hypot(st.x1, st.y1) <= 1.0f && hypot(st.x0, st.y0) >= 0.3f)
            }
            sp.dots.forEach { d -> assertTrue(hypot(d.x, d.y) + d.radius <= 1.0f && d.colorIndex in -1..2) }
        }
    }

    @Test fun `tous les motifs apparaissent et ne sont jamais vides avec plusieurs couleurs`() {
        val all = (0 until 600).map { splatterFor(it, listOf(0.4f, 0.35f, 0.25f)) }
        assertEquals(VinylPattern.entries.toSet(), all.map { it.pattern }.toSet())
        // Chaque motif (sauf uni, qui peut être réduit à une teinte) dessine quelque chose.
        all.filter { it.pattern != VinylPattern.SOLID }.forEach { assertTrue("${it.pattern}", !it.isEmpty) }
    }

    @Test fun `une seule couleur donne un disque uni sans secteurs`() {
        assertTrue((0 until 100).all { splatterFor(it, listOf(1f)).let { s -> s.wedges.isEmpty() && s.rings.isEmpty() && s.base == 0 } })
    }

    @Test fun `les disques sont tres varies`() {
        val shapes = (0 until 200).map { seed -> splatterFor(seed, listOf(0.4f, 0.35f, 0.25f)).let { listOf(it.pattern, it.wedges.size, it.streaks.size / 20, it.dots.size / 10, it.rings.size) } }
        assertTrue(shapes.toSet().size > 40)
    }
}
