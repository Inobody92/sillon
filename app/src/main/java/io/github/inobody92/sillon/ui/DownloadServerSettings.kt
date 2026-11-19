package io.github.inobody92.sillon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inobody92.sillon.data.CertificateInfo
import io.github.inobody92.sillon.data.DownloadServerClient
import io.github.inobody92.sillon.data.DownloadServerConfig
import io.github.inobody92.sillon.data.DownloadServerException
import io.github.inobody92.sillon.data.DownloadServerStore
import io.github.inobody92.sillon.data.probeCertificate
import kotlinx.coroutines.launch

/** Section « Serveur de téléchargement » des réglages. Facultative : sans adresse, la fonction reste invisible. */
@Composable
fun DownloadServerSection() {
    val config by DownloadServerStore.config.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }

    Text(
        "Serveur de téléchargement",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
    Text(
        config?.host ?: "Optionnel : ajoute de la musique à ta bibliothèque depuis l'application, grâce à ton propre " +
            "serveur de téléchargement (voir docs/DOWNLOAD_SERVER.md).",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { editing = true }) { Text(if (config == null) "Configurer" else "Modifier") }
        if (config != null) {
            TextButton(onClick = { DownloadServerStore.clear() }) {
                Text("Supprimer", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (editing) DownloadServerDialog(initial = config, onDismiss = { editing = false })
}

@Composable
private fun DownloadServerDialog(initial: DownloadServerConfig?, onDismiss: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(initial?.url.orEmpty()) }
    var token by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var untrusted by remember { mutableStateOf<CertificateInfo?>(null) }
    val scope = rememberCoroutineScope()

    /** Teste la connexion puis enregistre. Un certificat inconnu déclenche la confirmation d'empreinte. */
    fun validate(candidate: DownloadServerConfig) {
        working = true
        error = null
        scope.launch {
            try {
                if (!DownloadServerClient.create(candidate).health()) throw DownloadServerException("Réponse inattendue", DownloadServerException.Kind.SERVER)
                DownloadServerStore.save(candidate)
                onDismiss()
            } catch (e: DownloadServerException) {
                if (e.kind == DownloadServerException.Kind.CERTIFICATE) {
                    try {
                        untrusted = probeCertificate(candidate.url)
                    } catch (_: Exception) {
                        error = e.message
                    }
                } else {
                    error = e.message
                }
            } finally {
                working = false
            }
        }
    }

    fun submit() {
        val normalized = try {
            DownloadServerClient.normalizeUrl(url)
        } catch (e: IllegalArgumentException) {
            error = e.message
            return
        }
        // Champ jeton laissé vide en modification : on garde le jeton enregistré.
        val secret = token.trim().ifEmpty { initial?.token.orEmpty() }
        if (secret.isEmpty()) {
            error = "Le jeton est obligatoire"
            return
        }
        validate(DownloadServerConfig(normalized, secret, initial?.takeIf { it.url == normalized }?.pinnedSha256))
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Serveur de téléchargement") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, label = { Text("Adresse (HTTPS)") },
                    placeholder = { Text("mon-nas.exemple.fr:8443") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text(if (initial != null) "Jeton (vide = inchangé)" else "Jeton") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                }
                if (working) {
                    Text("Test de la connexion…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = !working && url.isNotBlank()) { Text("Tester et enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Annuler") } },
    )

    untrusted?.let { info ->
        AlertDialog(
            onDismissRequest = { untrusted = null },
            title = { Text("Faire confiance à ce certificat ?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Ce serveur utilise un certificat que le téléphone ne reconnaît pas. S'il est auto-signé, compare " +
                            "l'empreinte ci-dessous avec celle de ton serveur avant d'accepter.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Empreinte SHA-256", style = MaterialTheme.typography.labelMedium)
                    Text(info.formatted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Sujet : ${info.subject}", style = MaterialTheme.typography.bodySmall)
                    Text("Valable jusqu'au : ${info.notAfter}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    untrusted = null
                    val normalized = DownloadServerClient.normalizeUrl(url)
                    val secret = token.trim().ifEmpty { initial?.token.orEmpty() }
                    validate(DownloadServerConfig(normalized, secret, info.sha256))
                }) { Text("Faire confiance") }
            },
            dismissButton = { TextButton(onClick = { untrusted = null }) { Text("Annuler") } },
        )
    }
}
