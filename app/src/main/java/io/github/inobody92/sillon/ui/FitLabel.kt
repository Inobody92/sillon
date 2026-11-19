package io.github.inobody92.sillon.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * Libellé court sur une seule ligne qui se réduit s'il ne tient pas dans la largeur disponible (petit écran, texte
 * agrandi dans les réglages Android) au lieu de passer à la ligne et d'être coupé en deux (« Recherch / e »).
 * Reprend le style imposé par le parent (couleur, taille de base), donc utilisable dans une barre de navigation.
 */
@Composable
fun FitLabel(text: String) {
    var shrink by remember(text) { mutableFloatStateOf(1f) }
    val base = LocalTextStyle.current
    // Taille de départ : celle du parent, ou 12 sp si elle n'est pas définie (on ne multiplie pas une taille indéfinie).
    val size = if (base.fontSize.isSpecified) base.fontSize else 12.sp
    Text(
        text,
        style = base.copy(
            fontSize = size * shrink,
            lineHeight = if (base.lineHeight.isSpecified) base.lineHeight * shrink else base.lineHeight,
        ),
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.Center,
        overflow = TextOverflow.Clip,
        onTextLayout = { if (it.didOverflowWidth && shrink > 0.6f) shrink *= 0.92f },
    )
}
