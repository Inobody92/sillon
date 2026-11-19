package io.github.inobody92.sillon.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Résout un ID QuickConnect en adresse utilisable. Synology propose deux voies : une connexion directe (adresse
 * publique ou locale du NAS, rapide) et un relais (toujours possible, mais au débit bridé, mauvais pour la musique).
 * On essaie donc les adresses directes en premier et on ne retombe sur le relais qu'en dernier recours.
 *
 * Le protocole de résolution (`Serv.php`) n'est pas documenté officiellement : il est reconstitué d'après le
 * comportement connu du service, d'où une lecture très défensive de la réponse.
 */
object QuickConnect {

    private const val DIRECTORY = "https://global.quickconnect.to/Serv.php"
    private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9-]{2,}$")

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    /** Un ID QuickConnect n'a ni point ni deux-points : il se distingue d'une adresse. */
    fun looksLikeId(input: String): Boolean {
        val t = input.trim()
        return ID_PATTERN.matches(t) && !t.equals("localhost", ignoreCase = true) && !t.all { it.isDigit() }
    }

    /** Adresses candidates, de la plus souhaitable à la moins souhaitable (le relais est toujours en dernier). */
    fun candidates(serverInfo: JSONObject, id: String): List<String> {
        val server = serverInfo.optJSONObject("server")
        val service = serverInfo.optJSONObject("service")
        val smart = serverInfo.optJSONObject("smartdns")

        val ports = listOfNotNull(
            service?.optInt("ext_port")?.takeIf { it > 0 },
            service?.optInt("port")?.takeIf { it > 0 },
            5001,
            443,
        ).distinct()

        val hosts = buildList {
            smart?.optString("host")?.takeIf { it.isNotBlank() }?.let(::add)
            smart?.optString("external")?.takeIf { it.isNotBlank() }?.let(::add)
            server?.optString("ddns")?.takeIf { it.isNotBlank() && it != "NULL" }?.let(::add)
            server?.optString("fqdn")?.takeIf { it.isNotBlank() && it != "NULL" }?.let(::add)
            server?.optJSONObject("external")?.optString("ip")?.takeIf { it.isNotBlank() }?.let(::add)
            smart?.optJSONArray("lan")?.let { a -> for (i in 0 until a.length()) add(a.optString(i)) }
            server?.optJSONArray("interface")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.optString("ip")?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }.filter { it.isNotBlank() }.distinct()

        val direct = hosts.flatMap { host -> ports.map { port -> if (port == 443) "https://$host" else "https://$host:$port" } }
        return (direct + "https://$id.quickconnect.to").distinct()
    }

    /** Renvoie l'adresse du NAS pour cet ID, ou null s'il est introuvable ou injoignable. */
    suspend fun resolve(id: String): String? = withContext(Dispatchers.IO) {
        val info = try {
            fetchServerInfo(id)
        } catch (_: Exception) {
            null
        }
        val list = if (info != null) candidates(info, id) else listOf("https://$id.quickconnect.to")
        // Toutes les adresses sont testées en parallèle ; on garde la première, dans l'ordre de préférence.
        val reachable = coroutineScope {
            list.map { base -> async { base to isDsm(base) } }.map { it.await() }
        }
        reachable.firstOrNull { it.second }?.first
    }

    private fun fetchServerInfo(id: String): JSONObject? {
        val body = JSONObject()
            .put("version", 1)
            .put("command", "get_server_info")
            .put("stop_when_error", false)
            .put("stop_when_success", false)
            .put("id", "dsm_portal_https")
            .put("serverID", id)
            .put("is_gofile", false)
            .put("path", "")
            .toString()
            .toRequestBody("application/json".toMediaType())
        val first = post(DIRECTORY, body) ?: return null
        if (first.has("server") || first.has("service")) return first
        // Le répertoire peut renvoyer la liste des sites régionaux à interroger.
        val sites = first.optJSONArray("sites") ?: return null
        for (i in 0 until sites.length()) {
            val host = sites.optString(i).takeIf { it.isNotBlank() } ?: continue
            val reply = post("https://$host/Serv.php", body) ?: continue
            if (reply.has("server") || reply.has("service")) return reply
        }
        return null
    }

    private fun post(url: String, body: okhttp3.RequestBody): JSONObject? =
        client.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string()?.let { runCatching { JSONObject(it) }.getOrNull() } else null
        }

    /** Vrai si cette adresse répond comme un DSM (requête publique, sans identifiants), avec un certificat valide. */
    private fun isDsm(base: String): Boolean = try {
        val url = "$base/webapi/query.cgi?api=SYNO.API.Info&method=query&version=1&query=SYNO.API.Auth"
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            resp.isSuccessful && resp.body?.string()?.contains("\"success\"") == true
        }
    } catch (_: Exception) {
        false
    }
}
