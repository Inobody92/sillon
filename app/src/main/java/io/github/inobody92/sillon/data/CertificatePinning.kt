package io.github.inobody92.sillon.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Ce que l'utilisateur doit voir avant de faire confiance à un certificat auto-signé. */
data class CertificateInfo(
    /** Empreinte SHA-256 du certificat, en hexadécimal majuscule sans séparateur. */
    val sha256: String,
    val subject: String,
    val issuer: String,
    val notAfter: String,
) {
    /** `AA:BB:CC…`, plus lisible pour comparer avec ce qu'affiche DSM. */
    val formatted: String get() = sha256.chunked(2).joinToString(":")
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

/**
 * Accepte un seul certificat : celui dont l'empreinte a été confirmée par l'utilisateur. Contrairement à un
 * « faire confiance à tout », un certificat différent (attaque, ou remplacement légitime) est refusé.
 * La date d'expiration n'est pas contrôlée : l'utilisateur a accepté ce certificat précis.
 */
class PinnedTrustManager(expectedSha256: String) : X509TrustManager {

    private val expected = expectedSha256.replace(":", "").uppercase()

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("Authentification client non prise en charge")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Aucun certificat reçu")
        if (sha256Hex(leaf.encoded) != expected) {
            throw CertificateException("Le certificat du NAS a changé")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Lit le certificat présenté par le NAS **sans l'approuver** et sans rien lui envoyer d'autre qu'une requête vide :
 * aucun identifiant ne passe par ce client. Sert uniquement à afficher l'empreinte pour confirmation.
 */
@Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
suspend fun probeCertificate(baseUrl: String): CertificateInfo = withContext(Dispatchers.IO) {
    // Le certificat est capturé ici, pendant la poignée de main : c'est plus fiable que de relire
    // la liste exposée après coup par la bibliothèque HTTP, qui peut être vide selon la version de TLS.
    var captured: X509Certificate? = null
    val acceptAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            captured = chain.firstOrNull()
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(acceptAll), SecureRandom()) }
    val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .sslSocketFactory(ctx.socketFactory, acceptAll)
        .hostnameVerifier { _, _ -> true }
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    client.newCall(Request.Builder().url("$baseUrl/").head().build()).execute().close()
    val cert = captured ?: throw IOException("Le NAS n'a présenté aucun certificat")
    CertificateInfo(
        sha256 = sha256Hex(cert.encoded),
        subject = cert.subjectX500Principal.name,
        issuer = cert.issuerX500Principal.name,
        notAfter = cert.notAfter.toString(),
    )
}
