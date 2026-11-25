package io.github.inobody92.sillon.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/*
 * Palette de Sillon, dérivée de l'icône (disque crème sur bordeaux, teinte ~15° en OKLCH).
 *
 * Principes (clair et sombre conçus ensemble) :
 *  - Sombre : surfaces très sombres, à peine teintées de bordeaux, pour que les pochettes colorées ressortent ;
 *    teintes désaturées et éclaircies plutôt que simplement inversées.
 *  - Contrastes vérifiés par calcul (WCAG) : texte >= 4,5:1 sur toutes les surfaces, contours >= 3:1.
 *      sombre : texte 16:1 (fond) / 12:1 (carte haute), texte secondaire 10:1 / 7,8:1, accent 10:1 / 7,6:1
 *      clair  : texte 16,6:1 / 13:1, texte secondaire 8,2:1 / 6,4:1, accent 9,4:1 / 7,4:1
 *  - Les écrans n'utilisent jamais de couleur en dur : uniquement ces rôles sémantiques.
 */
internal val SillonDark = darkColorScheme(
    primary = Color(0xFFF5A5AA),
    onPrimary = Color(0xFF3D1017),
    primaryContainer = Color(0xFF5C2229),
    onPrimaryContainer = Color(0xFFFFDFE0),
    inversePrimary = Color(0xFF812A37),
    secondary = Color(0xFFD6B5AF),
    onSecondary = Color(0xFF331D1A),
    secondaryContainer = Color(0xFF4B302C),
    onSecondaryContainer = Color(0xFFF7E1DC),
    tertiary = Color(0xFFEBC07B),
    onTertiary = Color(0xFF392400),
    tertiaryContainer = Color(0xFF583D07),
    onTertiaryContainer = Color(0xFFFBE5C3),
    background = Color(0xFF110A0A),
    onBackground = Color(0xFFF0E7E7),
    surface = Color(0xFF110A0A),
    onSurface = Color(0xFFF0E7E7),
    surfaceVariant = Color(0xFF372A2A),
    onSurfaceVariant = Color(0xFFC9B9B9),
    surfaceTint = Color(0xFFF5A5AA),
    inverseSurface = Color(0xFFEBE2E2),
    inverseOnSurface = Color(0xFF2A2222),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF9B8687),
    outlineVariant = Color(0xFF4F3D3E),
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFF0E0707),
    surfaceBright = Color(0xFF322929),
    surfaceContainerLowest = Color(0xFF0A0505),
    surfaceContainerLow = Color(0xFF181011),
    surfaceContainer = Color(0xFF1E1616),
    surfaceContainerHigh = Color(0xFF271E1E),
    surfaceContainerHighest = Color(0xFF302626),
)

internal val SillonLight = lightColorScheme(
    primary = Color(0xFF7B2432),
    onPrimary = Color(0xFFFFFAFB),
    primaryContainer = Color(0xFFFED3D5),
    onPrimaryContainer = Color(0xFF470F19),
    inversePrimary = Color(0xFFF5A5AA),
    secondary = Color(0xFF664741),
    onSecondary = Color(0xFFFFFBFA),
    secondaryContainer = Color(0xFFF6DCD7),
    onSecondaryContainer = Color(0xFF3A211D),
    tertiary = Color(0xFF704E09),
    onTertiary = Color(0xFFFFFBF6),
    tertiaryContainer = Color(0xFFFBE2BE),
    onTertiaryContainer = Color(0xFF3B2700),
    background = Color(0xFFFFF8F8),
    onBackground = Color(0xFF211818),
    surface = Color(0xFFFFF8F8),
    onSurface = Color(0xFF211818),
    surfaceVariant = Color(0xFFEFDEDF),
    onSurfaceVariant = Color(0xFF594849),
    surfaceTint = Color(0xFF7B2432),
    inverseSurface = Color(0xFF2D2424),
    inverseOnSurface = Color(0xFFF5ECEC),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF836F6F),
    outlineVariant = Color(0xFFD7C6C6),
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFFE3D8D8),
    surfaceBright = Color(0xFFFFF8F8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF1F1),
    surfaceContainer = Color(0xFFF5EAEA),
    surfaceContainerHigh = Color(0xFFEFE3E3),
    surfaceContainerHighest = Color(0xFFEADCDC),
)

/** Couleur principale de Sillon (thème clair) : pastille « Sillon » du choix de couleurs. */
internal val SillonLightPrimary = Color(0xFF7B2432)

/**
 * [dynamicColors] : reprend les couleurs du fond d'écran (Material You, Android 12+) au lieu de la palette
 * de Sillon. Désactivé par défaut : l'identité de l'application reste la même d'un téléphone à l'autre.
 */
@Composable
fun SillonTheme(
    dark: Boolean,
    dynamicColors: Boolean = false,
    accent: io.github.inobody92.sillon.data.AccentChoice = io.github.inobody92.sillon.data.AccentChoice(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val base = if (dark) SillonDark else SillonLight
    val scheme = when {
        dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        // Couleurs choisies par l'utilisateur : tout le reste (fonds, textes) en est déduit, contrastes garantis.
        accent.primary != null -> customColorScheme(base, Color(accent.primary), accent.secondary?.let { Color(it) }, dark)
        else -> base
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
