package io.github.inobody92.sillon.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Thème personnalisé : à partir d'une couleur principale (et d'une secondaire, facultative), on fabrique toutes
 * les couleurs de l'application. Les fonds sont teintés de la teinte de la couleur principale, comme la palette
 * de Sillon l'est de son bordeaux. Les contrastes de texte (WCAG) sont garantis par calcul, pas à l'œil :
 *  - texte sur les fonds >= 7:1 ; texte secondaire sur les fonds >= 4,5:1 ;
 *  - couleur d'accent sur le fond >= 4,5:1 ; texte sur l'accent et sur son conteneur >= 4,5:1.
 */

/** Teinte (0-360), saturation (0-1), luminosité (0-1) d'une couleur. */
internal fun Color.toHsl(): FloatArray {
    val r = red; val g = green; val b = blue
    val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
    val l = (mx + mn) / 2f
    val d = mx - mn
    if (d == 0f) return floatArrayOf(0f, 0f, l)
    val s = d / (1f - abs(2f * l - 1f))
    val h = when (mx) {
        r -> ((g - b) / d) % 6f
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    } * 60f
    return floatArrayOf(if (h < 0) h + 360f else h, s.coerceIn(0f, 1f), l)
}

internal fun hslColor(h: Float, s: Float, l: Float): Color {
    val hh = ((h % 360f) + 360f) % 360f
    val ss = s.coerceIn(0f, 1f); val ll = l.coerceIn(0f, 1f)
    val c = (1f - abs(2f * ll - 1f)) * ss
    val x = c * (1f - abs((hh / 60f) % 2f - 1f))
    val m = ll - c / 2f
    val (r, g, b) = when {
        hh < 60f -> Triple(c, x, 0f)
        hh < 120f -> Triple(x, c, 0f)
        hh < 180f -> Triple(0f, c, x)
        hh < 240f -> Triple(0f, x, c)
        hh < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}

/** Rapport de contraste WCAG entre deux couleurs (1 à 21). */
internal fun contrast(a: Color, b: Color): Double {
    val la = a.luminance().toDouble(); val lb = b.luminance().toDouble()
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/** Éclaircit (thème sombre) ou assombrit (thème clair) [c] jusqu'à atteindre [minContrast] sur [bg], en gardant teinte et saturation. */
internal fun ensureContrast(c: Color, bg: Color, minContrast: Double, lighten: Boolean): Color {
    val hsl = c.toHsl()
    var l = hsl[2]
    var out = c
    var guard = 0
    while (contrast(out, bg) < minContrast && guard++ < 100) {
        l += if (lighten) 0.01f else -0.01f
        out = hslColor(hsl[0], hsl[1], l)
    }
    return out
}

/** Noir ou blanc, à peine teintés, selon ce qui se lit le mieux sur [bg]. */
internal fun readableOn(bg: Color, hue: Float): Color {
    val dark = hslColor(hue, 0.2f, 0.03f)
    val light = hslColor(hue, 0.1f, 1f)
    return if (contrast(dark, bg) >= contrast(light, bg)) dark else light
}

/** Les quatre rôles d'une couleur d'accent. */
internal data class AccentRoles(val main: Color, val onMain: Color, val container: Color, val onContainer: Color)

internal fun accentRoles(seed: Color, dark: Boolean, surface: Color): AccentRoles {
    val (h, s0, _) = seed.toHsl().let { Triple(it[0], it[1], it[2]) }
    val s = min(s0, 0.92f)
    var main = ensureContrast(seed.copy(alpha = 1f), surface, 4.5, lighten = dark)
    // Une couleur de luminosité moyenne n'est lisible ni avec du noir ni avec du blanc : on la décale un peu.
    var guard = 0
    while (contrast(readableOn(main, h), main) < 4.5 && guard++ < 100) {
        val hsl = main.toHsl()
        main = hslColor(hsl[0], hsl[1], hsl[2] + if (dark) 0.01f else -0.01f)
    }
    val container = if (dark) hslColor(h, s * 0.55f, 0.24f) else hslColor(h, s * 0.85f, 0.90f)
    val onContainer = if (dark) hslColor(h, s * 0.6f, 0.90f) else hslColor(h, min(1f, s), 0.16f)
    return AccentRoles(main, readableOn(main, h), container, onContainer)
}

/**
 * Jeu de couleurs complet pour [primary] (et [secondary], sinon dérivée de la principale). Les rôles que la
 * personnalisation ne touche pas (erreur, scrim) viennent de [base].
 */
internal fun customColorScheme(base: ColorScheme, primary: Color, secondary: Color?, dark: Boolean): ColorScheme {
    val p = primary.toHsl()
    val hue = p[0]
    // Une couleur grise donne des fonds neutres ; une couleur vive, des fonds nettement teintés.
    val tint = min(1f, p[1] * 2f)
    fun surf(l: Float, sat: Float) = hslColor(hue, sat * tint, l)
    val sd = 0.22f; val sl = 0.35f

    val bg = if (dark) surf(0.06f, sd) else surf(0.985f, sl)
    val accent = accentRoles(primary, dark, bg)
    val secondarySeed = secondary ?: hslColor(hue + 28f, min(0.55f, p[1] * 0.7f), 0.5f)
    val sec = accentRoles(secondarySeed, dark, bg)
    val tertiarySeed = hslColor(hue + 60f, min(0.7f, max(p[1], 0.35f)), 0.5f)
    val ter = accentRoles(tertiarySeed, dark, bg)
    val otherMode = accentRoles(primary, !dark, if (dark) surf(0.985f, sl) else surf(0.06f, sd))

    val onBg = if (dark) hslColor(hue, 0.08f * tint, 0.92f) else hslColor(hue, 0.15f * tint, 0.10f)
    val onVariant = if (dark) hslColor(hue, 0.12f * tint, 0.78f) else hslColor(hue, 0.15f * tint, 0.30f)

    return if (dark) base.copy(
        primary = accent.main, onPrimary = accent.onMain, primaryContainer = accent.container, onPrimaryContainer = accent.onContainer,
        inversePrimary = otherMode.main,
        secondary = sec.main, onSecondary = sec.onMain, secondaryContainer = sec.container, onSecondaryContainer = sec.onContainer,
        tertiary = ter.main, onTertiary = ter.onMain, tertiaryContainer = ter.container, onTertiaryContainer = ter.onContainer,
        background = bg, onBackground = onBg, surface = bg, onSurface = onBg,
        surfaceVariant = surf(0.18f, sd), onSurfaceVariant = onVariant, surfaceTint = accent.main,
        inverseSurface = surf(0.90f, sl), inverseOnSurface = surf(0.15f, sd),
        outline = hslColor(hue, 0.10f * tint, 0.58f), outlineVariant = surf(0.27f, sd),
        surfaceDim = surf(0.05f, sd), surfaceBright = surf(0.17f, sd),
        surfaceContainerLowest = surf(0.04f, sd), surfaceContainerLow = surf(0.08f, sd), surfaceContainer = surf(0.10f, sd),
        surfaceContainerHigh = surf(0.13f, sd), surfaceContainerHighest = surf(0.16f, sd),
    ) else base.copy(
        primary = accent.main, onPrimary = accent.onMain, primaryContainer = accent.container, onPrimaryContainer = accent.onContainer,
        inversePrimary = otherMode.main,
        secondary = sec.main, onSecondary = sec.onMain, secondaryContainer = sec.container, onSecondaryContainer = sec.onContainer,
        tertiary = ter.main, onTertiary = ter.onMain, tertiaryContainer = ter.container, onTertiaryContainer = ter.onContainer,
        background = bg, onBackground = onBg, surface = bg, onSurface = onBg,
        surfaceVariant = surf(0.91f, sl), onSurfaceVariant = onVariant, surfaceTint = accent.main,
        inverseSurface = surf(0.15f, sd), inverseOnSurface = surf(0.95f, sl),
        outline = hslColor(hue, 0.10f * tint, 0.45f), outlineVariant = surf(0.80f, sl),
        surfaceDim = surf(0.87f, sl), surfaceBright = surf(0.985f, sl),
        surfaceContainerLowest = Color.White, surfaceContainerLow = surf(0.96f, sl), surfaceContainer = surf(0.94f, sl),
        surfaceContainerHigh = surf(0.92f, sl), surfaceContainerHighest = surf(0.90f, sl),
    )
}
