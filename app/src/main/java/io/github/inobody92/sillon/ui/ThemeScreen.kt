package io.github.inobody92.sillon.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inobody92.sillon.data.AppSettings

private data class Swatch(val name: String, val color: Color)

private val SWATCHES = listOf(
    Swatch("Rouge", Color(0xFFD32F2F)), Swatch("Orange", Color(0xFFF57C00)), Swatch("Ambre", Color(0xFFFFB300)),
    Swatch("Citron", Color(0xFFC0CA33)), Swatch("Vert", Color(0xFF43A047)), Swatch("Menthe", Color(0xFF00BFA5)),
    Swatch("Cyan", Color(0xFF00ACC1)), Swatch("Bleu", Color(0xFF1E88E5)), Swatch("Indigo", Color(0xFF3949AB)),
    Swatch("Violet", Color(0xFF8E24AA)), Swatch("Rose", Color(0xFFEC407A)), Swatch("Ardoise", Color(0xFF607D8B)),
)

/** Choix des couleurs principale et secondaire. Tout l'écran (et l'application) se recolore en direct. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(onClose: () -> Unit) {
    val accent by AppSettings.accent.collectAsStateWithLifecycle()
    val dynamic by AppSettings.dynamicColors.collectAsStateWithLifecycle()
    val systemColorsAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val defaultPrimary = SillonLightPrimary

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopAppBar(
                title = { Text("Couleurs") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") } },
            )
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Preview()

                if (systemColorsAvailable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Couleurs du système", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Reprend celles de ton fond d'écran (Material You). Tes couleurs ne s'appliquent pas tant que c'est activé.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = dynamic, onCheckedChange = { AppSettings.setDynamicColors(it) })
                    }
                    HorizontalDivider()
                }

                ColorChooser(
                    title = "Couleur principale",
                    subtitle = "Boutons, curseurs, éléments actifs. Les fonds en prennent la teinte.",
                    current = accent.primary?.let { Color(it) },
                    noneLabel = "Sillon",
                    noneColor = defaultPrimary,
                    enabled = !dynamic,
                    onChange = { AppSettings.setAccent(it?.toArgb(), accent.secondary.takeIf { _ -> it != null }) },
                )
                HorizontalDivider()
                ColorChooser(
                    title = "Couleur secondaire",
                    subtitle = "Pastilles, barre de navigation, filtres.",
                    current = accent.secondary?.let { Color(it) },
                    noneLabel = "Auto",
                    noneColor = null,
                    enabled = !dynamic && accent.primary != null,
                    onChange = { AppSettings.setAccent(accent.primary, it?.toArgb()) },
                )
                if (accent.primary == null && !dynamic) {
                    Text("Choisis d'abord une couleur principale pour régler la secondaire.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                HorizontalDivider()
                OutlinedButton(onClick = { AppSettings.setAccent(null, null) }, enabled = accent.isCustom) {
                    Text("Rétablir les couleurs de Sillon")
                }
                Text(
                    "Les contrastes de lecture sont ajustés automatiquement : une couleur très claire ou très foncée est " +
                        "légèrement décalée pour que le texte reste lisible, en thème clair comme en sombre. " +
                        "Ton choix est sauvegardé avec ton profil sur le NAS.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Aperçu : quelques éléments types, recolorés en direct. */
@Composable
private fun Preview() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Aperçu", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Titre en cours de lecture", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("Artiste · Album", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { 0.4f }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {}) { Text("Lecture") }
                FilterChip(selected = true, onClick = {}, label = { Text("Filtre") })
                FilterChip(selected = false, onClick = {}, label = { Text("Autre") })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorChooser(
    title: String,
    subtitle: String,
    current: Color?,
    noneLabel: String,
    noneColor: Color?,
    enabled: Boolean,
    onChange: (Color?) -> Unit,
) {
    var custom by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SwatchDot(noneColor ?: MaterialTheme.colorScheme.surfaceVariant, noneLabel, selected = current == null, enabled = enabled, label = noneLabel) { onChange(null) }
            SWATCHES.forEach { sw ->
                SwatchDot(sw.color, sw.name, selected = current != null && !custom && current.toArgb() == sw.color.toArgb(), enabled = enabled) { custom = false; onChange(sw.color) }
            }
        }
        TextButton(onClick = { custom = !custom }, enabled = enabled) { Text(if (custom) "Masquer la couleur libre" else "Couleur libre…") }
        if (custom && enabled) FreeColor(current ?: noneColor ?: SWATCHES[7].color, onChange = { onChange(it) })
    }
}

@Composable
private fun SwatchDot(color: Color, description: String, selected: Boolean, enabled: Boolean, label: String? = null, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = if (enabled) 1f else 0.4f))
                .border(if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = "$description, sélectionné", tint = if (color.luminance() > 0.5f) Color.Black else Color.White)
        }
        if (label != null) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Couleur libre : teinte, saturation, luminosité et code hexadécimal. */
@Composable
private fun FreeColor(initial: Color, onChange: (Color) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var hue by remember { mutableStateOf(hsv[0]) }
    var sat by remember { mutableStateOf(hsv[1]) }
    var value by remember { mutableStateOf(hsv[2]) }
    var hex by remember { mutableStateOf(String.format("%06X", initial.toArgb() and 0xFFFFFF)) }
    fun current() = Color.hsv(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
    fun push() { val c = current(); hex = String.format("%06X", c.toArgb() and 0xFFFFFF); onChange(c) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GradientSlider("Teinte", hue / 360f, Brush.horizontalGradient(List(7) { Color.hsv(it * 60f, 1f, 1f) })) { hue = it * 360f; push() }
        GradientSlider("Saturation", sat, Brush.horizontalGradient(listOf(Color.hsv(hue, 0f, value), Color.hsv(hue, 1f, value)))) { sat = it; push() }
        GradientSlider("Luminosité", value, Brush.horizontalGradient(listOf(Color.Black, Color.hsv(hue, sat, 1f)))) { value = it; push() }
        OutlinedTextField(
            value = hex,
            onValueChange = { text ->
                val clean = text.removePrefix("#").uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                hex = clean
                if (clean.length == 6) {
                    val c = Color(0xFF000000.toInt() or clean.toInt(16))
                    val h = FloatArray(3); android.graphics.Color.colorToHSV(c.toArgb(), h)
                    hue = h[0]; sat = h[1]; value = h[2]
                    onChange(c)
                }
            },
            label = { Text("Code couleur (RRGGBB)") },
            prefix = { Text("#") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GradientSlider(label: String, position: Float, track: Brush, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(track))
            Slider(
                value = position.coerceIn(0f, 1f),
                onValueChange = onChange,
                colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
            )
        }
    }
}
