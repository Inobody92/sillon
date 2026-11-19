package io.github.inobody92.sillon.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inobody92.sillon.data.UpdateChecker

/**
 * Vérifie une fois au lancement s'il existe une version plus récente et, si oui, la propose.
 * « Télécharger » ouvre le fichier dans le navigateur ; l'installation reste une confirmation d'Android.
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { UpdateChecker.checkAtLaunch(context.applicationContext) }

    val release by UpdateChecker.available.collectAsStateWithLifecycle()
    val r = release ?: return
    AlertDialog(
        onDismissRequest = UpdateChecker::dismiss,
        title = { Text("Mise à jour disponible") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Sillon ${r.version} est disponible (tu as la ${UpdateChecker.installedVersion(context)}).",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (r.notes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Nouveautés", style = MaterialTheme.typography.labelLarge)
                    Text(
                        r.notes.take(700),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 14,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                openUrl(context, r.apkUrl ?: r.pageUrl)
                UpdateChecker.dismiss()
            }) { Text("Télécharger") }
        },
        dismissButton = {
            Column {
                TextButton(onClick = UpdateChecker::dismiss) { Text("Plus tard") }
                TextButton(onClick = { UpdateChecker.ignore(r) }) { Text("Ignorer cette version") }
            }
        },
    )
}

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Aucun navigateur : rien à faire, la fenêtre se ferme.
    }
}
