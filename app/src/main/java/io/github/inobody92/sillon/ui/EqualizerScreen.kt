package io.github.inobody92.sillon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inobody92.sillon.data.AppSettings
import io.github.inobody92.sillon.playback.EqPresets
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EqualizerScreen(onClose: () -> Unit) {
    val enabled by AppSettings.eqEnabled.collectAsStateWithLifecycle()
    val presetId by AppSettings.eqPreset.collectAsStateWithLifecycle()
    val gains by AppSettings.eqGains.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopAppBar(
                title = { Text("Égaliseur") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Activer l'égaliseur", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "S'applique à toute la lecture : haut-parleur, Bluetooth, voiture.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { AppSettings.setEqEnabled(it) })
                }

                // Les réglages restent modifiables égaliseur éteint, mais on indique qu'ils ne sont pas appliqués.
                Column(Modifier.alpha(if (enabled) 1f else 0.5f)) {
                    Text(
                        "Préréglages",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EqPresets.all.forEach { preset ->
                            FilterChip(
                                selected = presetId == preset.id,
                                onClick = {
                                    AppSettings.selectEqPreset(preset)
                                    AppSettings.setEqEnabled(true)
                                },
                                label = { Text(preset.label) },
                            )
                        }
                        if (presetId == EqPresets.CUSTOM_ID) {
                            FilterChip(selected = true, onClick = {}, label = { Text("Personnalisé") })
                        }
                    }

                    Text(
                        "Réglage fin",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
                    )
                    EqPresets.REFERENCE_HZ.forEachIndexed { index, hz ->
                        val db = gains[index]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(frequencyLabel(hz), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
                            Slider(
                                value = db,
                                onValueChange = {
                                    // Pas d'un demi-décibel : plus précis n'a pas de sens à l'oreille.
                                    AppSettings.setEqBand(index, (it * 2).roundToInt() / 2f)
                                    AppSettings.setEqEnabled(true)
                                },
                                valueRange = -EqPresets.MAX_DB..EqPresets.MAX_DB,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                gainLabel(db),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(64.dp),
                            )
                        }
                    }
                    Text(
                        "Évite de relever beaucoup de bandes à la fois : le son finirait par saturer. " +
                            "Pour plus de volume, baisse plutôt les bandes inutiles.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

private fun frequencyLabel(hz: Float): String =
    if (hz >= 1000f) "${(hz / 1000f).let { if (it % 1f == 0f) it.toInt().toString() else "%.1f".format(it) }} kHz" else "${hz.toInt()} Hz"

private fun gainLabel(db: Float): String = when {
    db > 0f -> "+%.1f dB".format(db)
    db < 0f -> "%.1f dB".format(db)
    else -> "0 dB"
}
