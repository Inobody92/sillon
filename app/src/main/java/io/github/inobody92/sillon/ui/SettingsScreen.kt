package io.github.inobody92.sillon.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inobody92.sillon.data.AppSettings
import io.github.inobody92.sillon.data.AudioQuality
import io.github.inobody92.sillon.data.Downloads
import io.github.inobody92.sillon.data.OfflineStore
import io.github.inobody92.sillon.playback.AudioCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.inobody92.sillon.data.SynoClient
import io.github.inobody92.sillon.data.PlayerVisual
import io.github.inobody92.sillon.data.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(client: SynoClient, onClose: () -> Unit, onOpenDownloads: () -> Unit, onLogout: () -> Unit) {
    val theme by AppSettings.theme.collectAsStateWithLifecycle()
    val dynamicColors by AppSettings.dynamicColors.collectAsStateWithLifecycle()
    val visual by AppSettings.playerVisual.collectAsStateWithLifecycle()
    val wifiOnly by AppSettings.downloadWifiOnly.collectAsStateWithLifecycle()
    val autoLikes by AppSettings.autoDownloadLikes.collectAsStateWithLifecycle()
    val offline by OfflineStore.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cacheMb by AppSettings.cacheMaxMb.collectAsStateWithLifecycle()
    var cacheUsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { cacheUsed = withContext(Dispatchers.IO) { AudioCache.usedBytes(context) } }
    val savedWifi by AppSettings.wifiQuality.collectAsStateWithLifecycle()
    val savedMobile by AppSettings.mobileQuality.collectAsStateWithLifecycle()
    // Sans conversion possible, la qualité réellement appliquée est toujours l'originale.
    val wifi = if (client.canTranscodeMp3) savedWifi else AudioQuality.ORIGINAL
    val mobile = if (client.canTranscodeMp3) savedMobile else AudioQuality.ORIGINAL

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Section("Apparence")
                ThemeMode.entries.forEach { mode ->
                    OptionRow(mode.label, selected = theme == mode) { AppSettings.setTheme(mode) }
                }

                Section("Écran de lecture")
                PlayerVisual.entries.forEach { v ->
                    OptionRow(v.label, selected = visual == v) { AppSettings.setPlayerVisual(v) }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                val canConvert = client.canTranscodeMp3
                Section("Qualité audio · Wi-Fi")
                AudioQuality.entries.forEach { q ->
                    OptionRow(q.label, selected = wifi == q, enabled = canConvert || q == AudioQuality.ORIGINAL) {
                        AppSettings.setWifiQuality(q)
                    }
                }

                Section("Qualité audio · données mobiles")
                AudioQuality.entries.forEach { q ->
                    OptionRow(q.label, selected = mobile == q, enabled = canConvert || q == AudioQuality.ORIGINAL) {
                        AppSettings.setMobileQuality(q)
                    }
                }
                Text(
                    if (canConvert) {
                        "Hors qualité originale, le NAS convertit chaque morceau en MP3 avant de l'envoyer : " +
                            "cela économise de la data mais sollicite le processeur du NAS, et le déplacement " +
                            "dans un morceau peut être limité. Le changement s'applique au morceau suivant."
                    } else {
                        "Ce NAS n'annonce pas de conversion MP3 (réponse « transcode » d'Audio Station) : " +
                            "les morceaux sont lus en qualité originale. La conversion dépend de la " +
                            "configuration d'Audio Station sur le NAS."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Section("Cache de lecture")
                Text(
                    "Les titres déjà écoutés ou préchargés sont gardés ici : ils repartent sans repasser par le NAS " +
                        "et l'enchaînement ne dépend plus du réseau.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                listOf(256, 512, 1024, 2048, 5120).forEach { mb ->
                    OptionRow(if (mb >= 1024) "${mb / 1024} Go" else "$mb Mo", selected = cacheMb == mb) {
                        AppSettings.setCacheMaxMb(mb)
                    }
                }
                Text(
                    "Taille appliquée au prochain démarrage de la lecture. Actuellement utilisé : ${formatBytes(cacheUsed)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            AudioCache.clear(context)
                            cacheUsed = AudioCache.usedBytes(context)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) { Text("Vider le cache") }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Section("Hors-ligne")
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Télécharger uniquement en Wi-Fi", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Évite de consommer ta data mobile",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = {
                            AppSettings.setDownloadWifiOnly(it)
                            // Recalcule la contrainte réseau des téléchargements déjà en attente.
                            if (offline.pendingCount > 0) Downloads.schedule(context, replace = true)
                        },
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Télécharger mes titres likés", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Chaque titre liké est téléchargé automatiquement. Désactiver n'efface rien.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoLikes, onCheckedChange = { AppSettings.setAutoDownloadLikes(it) })
                }
                OutlinedButton(onClick = onOpenDownloads, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("Gérer les téléchargements · ${formatBytes(offline.bytes)}")
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Section("Compte")
                Text(
                    "${client.creds.account} · ${client.creds.url.removePrefix("https://")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                OutlinedButton(onClick = onLogout, modifier = Modifier.padding(16.dp)) { Text("Se déconnecter") }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
        )
    }
}
