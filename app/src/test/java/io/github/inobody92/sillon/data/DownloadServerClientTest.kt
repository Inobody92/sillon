package io.github.inobody92.sillon.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadServerClientTest {

    private lateinit var server: MockWebServer
    private fun client(prefix: String = "") =
        DownloadServerClient(server.url(prefix).toString(), "jeton-secret", OkHttpClient())

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Before fun start() { server = MockWebServer().also { it.start() } }
    @After fun stop() { server.shutdown() }

    @Test fun lEnteteDAutorisationEstEnvoyeSurChaqueRequete() = runBlocking {
        server.enqueue(json("""{"ok": true}"""))
        assertTrue(client().health())
        assertEquals("Bearer jeton-secret", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun rechercheDeChansonsEstLueEtLaRequeteEstEncodee() = runBlocking {
        server.enqueue(json("""{"resultats":[{"id":"3135556","titre":"One More Time","artiste":"Daft Punk","album":"Discovery",
            "pochette":"https://c/x.jpg","duree":320,"fans":4000000,"apercu":null,"lien":"https://d/1"}]}"""))
        val songs = client().searchSongs("daft punk & co")
        assertEquals(1, songs.size)
        with(songs[0]) {
            assertEquals("3135556", id); assertEquals("Daft Punk", artist); assertEquals(320, durationSec)
            assertEquals(4_000_000L, fans); assertEquals("https://c/x.jpg", cover)
        }
        val request = server.takeRequest()
        assertEquals("/api/chansons?q=daft%20punk%20%26%20co", request.path)
        assertEquals("GET", request.method)
    }

    @Test fun lesChampsAbsentsOuNulsDeviennentNull() = runBlocking {
        server.enqueue(json("""{"resultats":[{"id":"1","titre":"T","artiste":"A","album":"","pochette":null,"duree":null,"fans":null}]}"""))
        val s = client().searchSongs("ab").single()
        assertNull(s.cover); assertNull(s.durationSec); assertNull(s.fans)
    }

    @Test fun rechercheDArtistes() = runBlocking {
        server.enqueue(json("""{"resultats":[{"id":"27","nom":"Daft Punk","vrai_nom":null,"groupe":true,"albums":12,"titres":140,"fans":4000000,"photo":"https://p"}]}"""))
        val a = client().searchArtists("daft").single()
        assertEquals("Daft Punk", a.name); assertTrue(a.isGroup); assertNull(a.realName); assertEquals(140, a.tracks)
    }

    @Test fun telechargerUneChansonEnvoieLIdentifiant() = runBlocking {
        server.enqueue(json("""{"numero":4,"nom":"Daft Punk - One More Time","position":1}""", 202))
        val q = client().downloadSong("3135556")
        assertEquals(QueuedTask(4, "Daft Punk - One More Time", 1), q)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/chansons", request.path)
        assertEquals("3135556", JSONObject(request.body.readUtf8()).getString("id"))
    }

    @Test fun telechargerUnArtisteEnvoieLeNombreDeTitres() = runBlocking {
        server.enqueue(json("""{"numero":5,"nom":"Daft Punk (top 10)","position":0}""", 202))
        client().downloadArtist("27", 10)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("27", body.getString("id")); assertEquals(10, body.getInt("nombre"))
    }

    @Test fun lienEnvoyeTelQuel() = runBlocking {
        server.enqueue(json("""{"numero":6,"nom":"https://x/y","position":0}""", 202))
        client().downloadLink("https://x/y")
        assertEquals("https://x/y", JSONObject(server.takeRequest().body.readUtf8()).getString("url"))
    }

    @Test fun fileEtHistorique() = runBlocking {
        server.enqueue(json("""{"pause_jusqua":null,"maintenant":100,
            "taches":[{"numero":1,"nom":"Daft Punk","etat":"Téléchargement","en_cours":true,"position":null,"pourcentage":42.5,
              "total":10,"faites":4,"morceau":"Around the World","vitesse":1500000,"eta":12,"erreurs":1,"automatique":false},
              {"numero":2,"nom":"MIKA","etat":"En attente","en_cours":false,"position":0,"pourcentage":0,"total":0,"faites":0,
              "morceau":null,"vitesse":null,"eta":null,"erreurs":0,"automatique":false}],
            "terminees":[{"numero":0,"nom":"Autre","statut":"termine","telechargees":3,"erreurs":0,"total":3,"erreur":null,"fin":90}]}"""))
        val q = client().queue()
        assertNull(q.pausedUntil)
        assertEquals(2, q.tasks.size)
        assertTrue(q.tasks[0].running); assertEquals(42.5f, q.tasks[0].percent); assertEquals("Around the World", q.tasks[0].currentTrack)
        assertFalse(q.tasks[1].running); assertEquals(0, q.tasks[1].position); assertNull(q.tasks[1].currentTrack)
        assertEquals("termine", q.finished[0].status); assertEquals(3, q.finished[0].downloaded)
    }

    @Test fun annulerAppelleLaBonneRoute() = runBlocking {
        server.enqueue(json("""{"message":"Annulation demandée."}"""))
        assertEquals("Annulation demandée.", client().cancel(7))
        val r = server.takeRequest()
        assertEquals("DELETE", r.method); assertEquals("/api/taches/7", r.path)
    }

    @Test fun unJetonRefuseEstUneErreurDAuthentification() {
        server.enqueue(json("""{"erreur":"Jeton refusé."}""", 401))
        val e = assertThrows(DownloadServerException::class.java) { runBlocking { client().health() } }
        assertEquals(DownloadServerException.Kind.AUTH, e.kind)
    }

    @Test fun leMessageDuServeurEstRepris() {
        server.enqueue(json("""{"erreur":"Déjà en cours ou dans la file."}""", 409))
        val e = assertThrows(DownloadServerException::class.java) { runBlocking { client().downloadSong("1") } }
        assertEquals("Déjà en cours ou dans la file.", e.message)
        assertEquals(DownloadServerException.Kind.SERVER, e.kind)
    }

    @Test fun uneReponseQuiNEstPasDuJsonEstUneErreurClaire() {
        server.enqueue(MockResponse().setBody("<html>502 Bad Gateway</html>").setResponseCode(502))
        val e = assertThrows(DownloadServerException::class.java) { runBlocking { client().queue() } }
        assertEquals("Erreur 502 du serveur", e.message)
    }

    @Test fun unServeurEteintEstUneErreurReseau() {
        val c = client()
        server.shutdown()
        val e = assertThrows(DownloadServerException::class.java) { runBlocking { c.health() } }
        assertEquals(DownloadServerException.Kind.NETWORK, e.kind)
    }

    @Test fun unePrefixeDeCheminDeReverseProxyEstConserve() = runBlocking {
        server.enqueue(json("""{"ok": true}"""))
        client("/musique/").health()
        assertEquals("/musique/api/sante", server.takeRequest().path)
    }

    @Test fun lAdresseDoitEtreEnHttps() {
        assertEquals("https://nas.exemple.fr:8443", DownloadServerClient.normalizeUrl("nas.exemple.fr:8443/"))
        assertThrows(IllegalArgumentException::class.java) { DownloadServerClient.normalizeUrl("http://192.168.1.10:8787") }
        assertThrows(IllegalArgumentException::class.java) { DownloadServerClient.normalizeUrl("  ") }
    }
}
