package io.github.inobody92.sillon.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentPaletteTest {
    private val base = SillonDark
    private val seeds = (0 until 360 step 30).map { hslColor(it.toFloat(), 0.85f, 0.5f) } +
        listOf(Color(0xFF808080), Color.White, Color.Black, hslColor(60f, 1f, 0.5f), hslColor(240f, 1f, 0.2f))

    @Test fun `la conversion HSL est reversible`() {
        for (c in listOf(Color(0xFF7B2432), Color(0xFF2E7D32), Color(0xFF1565C0), Color(0xFFFFC107))) {
            val h = c.toHsl()
            val back = hslColor(h[0], h[1], h[2])
            assertEquals(c.red.toDouble(), back.red.toDouble(), 0.01)
            assertEquals(c.green.toDouble(), back.green.toDouble(), 0.01)
            assertEquals(c.blue.toDouble(), back.blue.toDouble(), 0.01)
        }
    }

    @Test fun `le contraste est celui du WCAG`() {
        assertEquals(21.0, contrast(Color.Black, Color.White), 0.05)
        assertEquals(1.0, contrast(Color.White, Color.White), 0.001)
    }

    private fun check(dark: Boolean, primary: Color, secondary: Color?) {
        val s = customColorScheme(base, primary, secondary, dark)
        val label = "dark=$dark primaire=${primary.toHsl().toList()} secondaire=$secondary"
        val surfaces = listOf(s.background, s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)
        surfaces.forEach { assertTrue("texte sur fond $label", contrast(s.onSurface, it) >= 7.0) }
        listOf(s.surfaceVariant, s.surfaceContainerHigh, s.background).forEach {
            assertTrue("texte secondaire $label (${contrast(s.onSurfaceVariant, it)})", contrast(s.onSurfaceVariant, it) >= 4.5)
        }
        assertTrue("accent sur le fond $label (${contrast(s.primary, s.background)})", contrast(s.primary, s.background) >= 4.5)
        assertTrue("secondaire sur le fond $label", contrast(s.secondary, s.background) >= 4.5)
        assertTrue("texte sur accent $label", contrast(s.onPrimary, s.primary) >= 4.5)
        assertTrue("texte sur conteneur $label", contrast(s.onPrimaryContainer, s.primaryContainer) >= 4.5)
        assertTrue("texte sur conteneur secondaire $label", contrast(s.onSecondaryContainer, s.secondaryContainer) >= 4.5)
        assertTrue("contour $label", contrast(s.outline, s.background) >= 3.0)
    }

    @Test fun `tous les contrastes tiennent pour toute couleur, en sombre`() {
        seeds.forEach { check(true, it, null) }
    }

    @Test fun `tous les contrastes tiennent pour toute couleur, en clair`() {
        seeds.forEach { check(false, it, null) }
    }

    @Test fun `avec une couleur secondaire choisie aussi`() {
        for (p in seeds.take(6)) for (sec in listOf(Color(0xFF00897B), Color(0xFFFFC107), Color.White)) {
            check(true, p, sec); check(false, p, sec)
        }
    }

    @Test fun `la couleur choisie est conservee quand elle est deja lisible`() {
        val seed = hslColor(210f, 0.7f, 0.72f) // bleu clair : lisible sur fond sombre
        val s = customColorScheme(base, seed, null, dark = true)
        val h = s.primary.toHsl()
        assertEquals(210.0, h[0].toDouble(), 3.0)
        assertEquals(0.72, h[2].toDouble(), 0.03)
    }

    @Test fun `les fonds prennent la teinte de la couleur principale`() {
        val blue = customColorScheme(base, hslColor(220f, 0.8f, 0.5f), null, dark = true)
        val hue = blue.surfaceContainer.toHsl()[0].toDouble()
        assertEquals(220.0, hue, 8.0)
    }

    @Test fun `une couleur grise donne des fonds neutres`() {
        val s = customColorScheme(base, Color(0xFF808080), null, dark = true)
        assertTrue(s.surfaceContainer.toHsl()[1] < 0.05f)
    }
}
