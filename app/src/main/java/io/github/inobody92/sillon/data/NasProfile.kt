package io.github.inobody92.sillon.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sauvegarde de ce qui n'existe que sur le téléphone — l'exposition des titres (combien de fois chacun est tombé) et
 * les statistiques d'écoute — dans un fichier du dossier personnel de l'utilisateur sur le NAS, `/home/.sillon/profil.json`.
 * Un nouveau téléphone ou une réinstallation reprennent donc là où on s'était arrêté. Les notes, les likes et les
 * titres masqués sont déjà sur le NAS (Audio Station).
 *
 * Synchronisation : à la connexion on relit le fichier, on fusionne avec l'état local (l'entrée la plus récente
 * l'emporte), puis on réécrit si quelque chose a changé ; ensuite chaque changement local est envoyé au bout d'un moment.
 */
object NasProfile {

    private const val DIR = "/home/.sillon"
    private const val NAME = "profil.json"
    private const val PUSH_DELAY_MS = 60_000L
    private const val TAG = "SillonSync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var pushJob: Job? = null
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Relit le profil du NAS, le fusionne avec l'état local et le réécrit s'il a changé. */
    fun sync(client: SynoClient) {
        scope.launch {
            lock.withLock {
                try {
                    val remote = client.nasFileRead("$DIR/$NAME")?.let { parse(String(it)) }
                    val before = snapshot()
                    if (remote != null) {
                        ExposureStore.mergeFrom(appContext, remote.exposure)
                        ListenStats.mergeFrom(remote.listen)
                        TasteStore.requestRebuild()
                    }
                    val after = snapshot()
                    // On écrit si le fichier n'existe pas encore, ou s'il manque quelque chose par rapport à nous.
                    if (remote == null || remote.exposure != after.exposure || remote.listen != after.listen) {
                        client.nasFileWrite(DIR, NAME, serialize(after).toByteArray())
                        Log.i(TAG, "profil écrit sur le NAS (${after.exposure.size} expositions, ${after.listen.size} écoutes)")
                    } else {
                        Log.i(TAG, "profil du NAS à jour (${before.exposure.size} expositions, ${before.listen.size} écoutes)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "synchronisation du profil impossible : ${e.message}")
                }
            }
        }
    }

    /** À appeler après un changement local : l'envoi se fait au bout d'un moment, en regroupant les changements. */
    fun requestPush() {
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DELAY_MS)
            val client = SessionHolder.client ?: return@launch
            lock.withLock {
                try {
                    client.nasFileWrite(DIR, NAME, serialize(snapshot()).toByteArray())
                } catch (e: Exception) {
                    Log.w(TAG, "envoi du profil impossible : ${e.message}")
                }
            }
        }
    }

    internal data class Profile(val exposure: Map<String, ExposureEntry>, val listen: Map<String, ListenCounts>)

    private fun snapshot() = Profile(ExposureStore.snapshot(appContext), ListenStats.snapshot())

    internal fun serialize(p: Profile): String {
        val exposure = JSONObject()
        p.exposure.forEach { (id, e) -> exposure.put(id, JSONArray(listOf(e.count, e.lastMs))) }
        val listen = JSONObject()
        p.listen.forEach { (id, l) -> listen.put(id, JSONArray(listOf(l.completes, l.skips))) }
        return JSONObject().put("version", 1).put("exposure", exposure).put("listen", listen).toString()
    }

    internal fun parse(text: String): Profile? = runCatching {
        val json = JSONObject(text)
        val exposure = HashMap<String, ExposureEntry>()
        json.optJSONObject("exposure")?.let { o -> o.keys().forEach { k -> o.getJSONArray(k).let { exposure[k] = ExposureEntry(it.getInt(0), it.getLong(1)) } } }
        val listen = HashMap<String, ListenCounts>()
        json.optJSONObject("listen")?.let { o -> o.keys().forEach { k -> o.getJSONArray(k).let { listen[k] = ListenCounts(it.getInt(0), it.getInt(1)) } } }
        Profile(exposure, listen)
    }.getOrNull()

    /** Fusion de deux expositions : l'entrée la plus récente l'emporte (à date égale, le plus grand compte). */
    internal fun mergeExposure(local: Map<String, ExposureEntry>, remote: Map<String, ExposureEntry>): Map<String, ExposureEntry> {
        val out = HashMap(local)
        remote.forEach { (id, r) ->
            val l = out[id]
            if (l == null || r.lastMs > l.lastMs || (r.lastMs == l.lastMs && r.count > l.count)) out[id] = r
        }
        return out
    }

    /** Fusion de deux statistiques d'écoute : le plus grand compte de chaque sorte (on ne peut pas les additionner sans les compter deux fois). */
    internal fun mergeListen(local: Map<String, ListenCounts>, remote: Map<String, ListenCounts>): Map<String, ListenCounts> {
        val out = HashMap(local)
        remote.forEach { (id, r) ->
            val l = out[id]
            out[id] = if (l == null) r else ListenCounts(maxOf(l.completes, r.completes), maxOf(l.skips, r.skips))
        }
        return out
    }
}
