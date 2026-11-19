package io.github.inobody92.sillon.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class UpdateCheckerTest {

    private fun release(
        tag: String = "v1.5.0",
        draft: Boolean = false,
        prerelease: Boolean = false,
        page: String = "https://github.com/Inobody92/sillon/releases/tag/v1.5.0",
        assets: String = """[{"name":"Sillon-1.5.0.apk","browser_download_url":"https://github.com/Inobody92/sillon/releases/download/v1.5.0/Sillon-1.5.0.apk"}]""",
        body: String = "Nouveautés : widget",
    ) = """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"html_url":"$page","assets":$assets,"body":"$body"}"""

    // --- comparaison de versions ---

    @Test fun uneVersionPlusRecenteEstDetectee() {
        assertTrue(UpdateChecker.isNewer("1.4.1", "1.4.2"))
        assertTrue(UpdateChecker.isNewer("1.4.1", "1.5.0"))
        assertTrue(UpdateChecker.isNewer("1.4.9", "1.4.10"))   // numérique, pas alphabétique
        assertTrue(UpdateChecker.isNewer("1.9", "2"))
        assertTrue(UpdateChecker.isNewer("1.4", "1.4.1"))
    }

    @Test fun uneVersionIdentiqueOuAncienneNEstPasProposee() {
        assertFalse(UpdateChecker.isNewer("1.4.1", "1.4.1"))
        assertFalse(UpdateChecker.isNewer("1.4.1", "1.4.0"))
        assertFalse(UpdateChecker.isNewer("1.4.1", "1.4.1.0"))
        assertFalse(UpdateChecker.isNewer("2.0", "1.99.9"))
    }

    @Test fun uneVersionIllisibleNeProposeRien() {
        assertFalse(UpdateChecker.isNewer("1.4.1", "latest"))
        assertFalse(UpdateChecker.isNewer("1.4.1", ""))
        assertFalse(UpdateChecker.isNewer("", "1.5.0"))
        assertFalse(UpdateChecker.isNewer("1.4.1", "1.5.0-beta"))
    }

    // --- lecture de la réponse GitHub ---

    @Test fun uneReleaseNormaleEstLue() {
        val r = UpdateChecker.parse(release())!!
        assertEquals("1.5.0", r.version)
        assertEquals("https://github.com/Inobody92/sillon/releases/download/v1.5.0/Sillon-1.5.0.apk", r.apkUrl)
        assertEquals("https://github.com/Inobody92/sillon/releases/tag/v1.5.0", r.pageUrl)
        assertEquals("Nouveautés : widget", r.notes)
    }

    @Test fun leTagSansVEstAccepte() = assertEquals("1.5.0", UpdateChecker.parse(release(tag = "1.5.0"))!!.version)

    @Test fun lesBrouillonsEtPreversionsSontIgnores() {
        assertNull(UpdateChecker.parse(release(draft = true)))
        assertNull(UpdateChecker.parse(release(prerelease = true)))
    }

    @Test fun uneAdresseHorsDuDepotEstRefusee() {
        assertNull(UpdateChecker.parse(release(page = "https://exemple.invalid/tag")))
        val sansApk = UpdateChecker.parse(release(assets = """[{"browser_download_url":"https://exemple.invalid/x.apk"}]"""))!!
        assertNull("un APK hébergé ailleurs n'est jamais proposé", sansApk.apkUrl)
    }

    @Test fun sansApkOnGardeLaPageDeLaRelease() {
        val r = UpdateChecker.parse(release(assets = "[]"))!!
        assertNull(r.apkUrl)
        assertTrue(r.pageUrl.startsWith("https://github.com/Inobody92/sillon/"))
    }

    @Test fun uneReponseAbimeeEstIgnoree() {
        assertNull(UpdateChecker.parse(""))
        assertNull(UpdateChecker.parse("<html>"))
        assertNull(UpdateChecker.parse("""{"tag_name":"pas-une-version","html_url":"https://github.com/Inobody92/sillon/x"}"""))
        assertNull(UpdateChecker.parse("{}"))
    }

    // --- réseau ---

    private lateinit var server: MockWebServer
    @Before fun start() { server = MockWebServer().also { it.start() } }
    @After fun stop() { server.shutdown() }

    @Test fun laRequeteEstAnonymeEtIdentifieSeulementSillon() = runBlocking {
        server.enqueue(MockResponse().setBody(release()))
        UpdateChecker.fetch(server.url("/latest").toString(), OkHttpClient())
        val request = server.takeRequest()
        assertEquals("Sillon", request.getHeader("User-Agent"))
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
    }

    @Test fun aucuneReleasePubliee404DonneNull() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        assertNull(UpdateChecker.fetch(server.url("/latest").toString(), OkHttpClient()))
    }

    @Test fun uneErreurDeGitHubEstUneErreurReseau() {
        server.enqueue(MockResponse().setResponseCode(403))   // limite de requêtes
        assertThrows(IOException::class.java) { runBlocking { UpdateChecker.fetch(server.url("/latest").toString(), OkHttpClient()) } }
    }
}
