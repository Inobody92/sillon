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
                        HiddenRules.mergeFrom(remote.rules)
                        remote.accent?.let { AppSettings.mergeAccent(it) }
                        remote.discovery?.let { DiscoveryStore.mergeFrom(it) }
                        TasteStore.requestRebuild()
                    }
                    val after = snapshot()
                    // On écrit si le fichier n'existe pas encore, ou s'il manque quelque chose par rapport à nous.
                    if (remote == null || remote.exposure != after.exposure || remote.listen != after.listen || remote.rules != after.rules || remote.accent != after.accent || remote.discovery != after.discovery) {
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

    internal data class Profile(
        val exposure: Map<String, ExposureEntry>,
        val listen: Map<String, ListenCounts>,
        val rules: Map<String, HideRule> = emptyMap(),
        val accent: AccentChoice? = null,
        val discovery: DiscoveryMemory? = null,
    )

    private fun snapshot() = Profile(ExposureStore.snapshot(appContext), ListenStats.snapshot(), HiddenRules.snapshot(), AppSettings.accent.value.takeIf { it.updatedAt > 0 }, DiscoveryStore.memory().takeIf { it.dismissed.isNotEmpty() || it.proposed.isNotEmpty() })

    internal fun serialize(p: Profile): String {
        val exposure = JSONObject()
        p.exposure.forEach { (id, e) -> exposure.put(id, JSONArray(listOf(e.count, e.lastMs))) }
        val listen = JSONObject()
        p.listen.forEach { (id, l) -> listen.put(id, JSONArray(listOf(l.completes, l.skips))) }
        val rules = JSONArray()
        p.rules.values.forEach { rules.put(HiddenRules.encode(it)) }
        val json = JSONObject().put("version", 1).put("exposure", exposure).put("listen", listen).put("rules", rules)
        p.accent?.let { a ->
            json.put("theme", JSONObject().put("primary", a.primary ?: JSONObject.NULL).put("secondary", a.secondary ?: JSONObject.NULL).put("at", a.updatedAt))
        }
        p.discovery?.let { d ->
            json.put("discovery", JSONObject()
                .put("dismissed", JSONObject().also { o -> d.dismissed.forEach { (k, v) -> o.put(k, v) } })
                .put("proposed", JSONObject().also { o -> d.proposed.forEach { (k, v) -> o.put(k, v) } }))
        }
        return json.toString()
    }

    internal fun parse(text: String): Profile? = runCatching {
        val json = JSONObject(text)
        val exposure = HashMap<String, ExposureEntry>()
        json.optJSONObject("exposure")?.let { o -> o.keys().forEach { k -> o.getJSONArray(k).let { exposure[k] = ExposureEntry(it.getInt(0), it.getLong(1)) } } }
        val listen = HashMap<String, ListenCounts>()
        json.optJSONObject("listen")?.let { o -> o.keys().forEach { k -> o.getJSONArray(k).let { listen[k] = ListenCounts(it.getInt(0), it.getInt(1)) } } }
        val rules = HashMap<String, HideRule>()
        json.optJSONArray("rules")?.let { a -> for (i in 0 until a.length()) HiddenRules.decode(a.getJSONArray(i))?.let { rules[it.id] = it } }
        val accent = json.optJSONObject("theme")?.let { t ->
            AccentChoice(
                primary = if (t.isNull("primary")) null else t.getInt("primary"),
                secondary = if (t.isNull("secondary")) null else t.getInt("secondary"),
                updatedAt = t.optLong("at"),
            )
        }
        val discovery = json.optJSONObject("discovery")?.let { d ->
            fun times(o: JSONObject?) = HashMap<String, Long>().also { m -> o?.keys()?.forEach { k -> m[k] = o.getLong(k) } }
            DiscoveryMemory(times(d.optJSONObject("dismissed")), times(d.optJSONObject("proposed")))
        }
        Profile(exposure, listen, rules, accent, discovery)
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
