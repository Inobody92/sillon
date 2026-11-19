package io.github.inobody92.sillon.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le client du serveur de téléchargement face à un vrai HTTPS : certificat inconnu, épinglé, ou connexion coupée. */
class DownloadServerTlsTest {

    private val cert = HeldCertificate.Builder().commonName("serveur.local").addSubjectAlternativeName("localhost").build()
    private lateinit var server: MockWebServer

    private fun startTls(policy: SocketPolicy = SocketPolicy.KEEP_OPEN) {
        val certs = HandshakeCertificates.Builder().heldCertificate(cert).build()
        server = MockWebServer().apply {
            useHttps(certs.sslSocketFactory(), false)
            enqueue(MockResponse().setBody("""{"ok": true}""").setSocketPolicy(policy))
            start()
        }
    }

    @After fun stop() = server.shutdown()

    private fun url() = server.url("/").toString().trimEnd('/')
    private fun pin() = sha256Hex(cert.certificate.encoded)

    @Test fun unCertificatInconnuEstSignaleCommeTel() {
        startTls()
        val e = assertThrows(DownloadServerException::class.java) {
            runBlocking { DownloadServerClient.create(DownloadServerConfig(url(), "t")).health() }
        }
        assertEquals(DownloadServerException.Kind.CERTIFICATE, e.kind)
    }

    @Test fun leCertificatEpingleEstAccepte() = runBlocking {
        startTls()
        assertTrue(DownloadServerClient.create(DownloadServerConfig(url(), "t", pin())).health())
    }

    @Test fun unAutreCertificatQueCelEpingleEstRefuse() {
        startTls()
        val autre = sha256Hex(HeldCertificate.Builder().commonName("intrus").build().certificate.encoded)
        val e = assertThrows(DownloadServerException::class.java) {
            runBlocking { DownloadServerClient.create(DownloadServerConfig(url(), "t", autre)).health() }
        }
        assertEquals(DownloadServerException.Kind.CERTIFICATE, e.kind)
    }

    @Test fun uneConnexionCoupeePendantLaPoigneeDeMainEstUnePanneReseau() {
        // Serveur ou proxy en panne : le téléphone se connecte puis est coupé. Ce n'est pas un problème de certificat.
        startTls(SocketPolicy.DISCONNECT_AT_START)
        val e = assertThrows(DownloadServerException::class.java) {
            runBlocking { DownloadServerClient.create(DownloadServerConfig(url(), "t", pin())).health() }
        }
        assertEquals(DownloadServerException.Kind.NETWORK, e.kind)
    }
}
