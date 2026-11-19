package io.github.inobody92.sillon.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import javax.net.ssl.SSLException

/** Un vrai serveur HTTPS à certificat auto-signé, comme un NAS qui n'a pas de certificat reconnu. */
class CertificatePinningTest {

    private lateinit var server: MockWebServer
    private lateinit var cert: HeldCertificate

    @Before fun setUp() {
        cert = HeldCertificate.Builder().commonName("nas.local").addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        server = MockWebServer().apply {
            useHttps(serverCerts.sslSocketFactory(), false)
            dispatcher = FakeDsm()
            start()
        }
    }

    @After fun tearDown() = server.shutdown()

    private fun url() = server.url("/").toString().trimEnd('/')

    private fun creds(pin: String?, insecure: Boolean = true) =
        Credentials(url(), "maxime", "secret", insecure, null, pin)

    @Test fun lEmpreinteLueCorrespondAuCertificatDuServeur() = runBlocking {
        val info = probeCertificate(url())
        assertEquals(sha256Hex(cert.certificate.encoded), info.sha256)
        assertTrue(info.formatted.contains(":"))
        assertTrue(info.subject.contains("nas.local"))
    }

    @Test fun avecLaBonneEmpreinteLaConnexionPasse() = runBlocking {
        val pin = probeCertificate(url()).sha256
        val c = SynoClient(creds(pin))
        c.connect()
        assertEquals("SID1", c.sid)
    }

    @Test fun uneEmpreinteDifferenteEstRefusee() {
        val other = HeldCertificate.Builder().commonName("intrus").build()
        val wrong = sha256Hex(other.certificate.encoded)
        val e = assertThrows(SSLException::class.java) { runBlocking { SynoClient(creds(wrong)).connect() } }
        assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("a changé") == true })
    }

    @Test fun sansEmpreinteUnCertificatAutoSigneEstRefuse() {
        // Plus de « faire confiance à tout » : sans confirmation de l'utilisateur, la validation normale échoue.
        assertThrows(SSLException::class.java) { runBlocking { SynoClient(creds(pin = null)).connect() } }
    }

    @Test fun lEmpreinteSAccepteAvecOuSansDeuxPoints() {
        val hex = sha256Hex(cert.certificate.encoded)
        val formatted = hex.chunked(2).joinToString(":").lowercase()
        PinnedTrustManager(formatted).checkServerTrusted(arrayOf(cert.certificate), "RSA")
        PinnedTrustManager(hex).checkServerTrusted(arrayOf(cert.certificate), "RSA")
    }
}
