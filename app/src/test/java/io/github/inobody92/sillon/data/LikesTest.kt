package io.github.inobody92.sillon.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LikesTest {

    private lateinit var server: MockWebServer
    private lateinit var dsm: FakeDsm
    private lateinit var client: SynoClient
    private lateinit var stateFile: File

    @Before fun setUp() = runBlocking {
        dsm = FakeDsm()
        server = MockWebServer().apply { dispatcher = dsm; start() }
        client = SynoClient(Credentials(server.url("/").toString().trimEnd('/'), "maxime", "secret", false))
        client.connect()
        stateFile = File(Files.createTempDirectory("likes-test").toFile(), "likes.json")
        Likes.initForTest(stateFile)
    }

    @After fun tearDown() {
        server.shutdown()
        stateFile.parentFile.deleteRecursively()
    }

    private fun likedOnServer(): List<String> =
        dsm.playlists.values.firstOrNull { it.first == Likes.NAME }?.second.orEmpty()

    @Test fun lePremierLikeCreeLaPlaylist() = runBlocking {
        Likes.toggleNow(client, "music_1")
        assertEquals(listOf("music_1"), likedOnServer())
        assertTrue(Likes.isLiked("music_1"))
        assertNotNull(Likes.state.value.playlistId)
    }

    @Test fun deuxLikesDifferentsSAjoutentSansDoublon() = runBlocking {
        Likes.toggleNow(client, "music_1")
        Likes.toggleNow(client, "music_2")
        assertEquals(listOf("music_1", "music_2"), likedOnServer())
        assertEquals(1, dsm.playlists.size) // une seule playlist, pas une par like
    }

    @Test fun retirerUnLikeLeRetireDuNasEtDeLEtatLocal() = runBlocking {
        Likes.toggleNow(client, "music_1")
        Likes.toggleNow(client, "music_2")
        Likes.toggleNow(client, "music_1")
        assertEquals(listOf("music_2"), likedOnServer())
        assertFalse(Likes.isLiked("music_1"))
    }

    @Test fun unCacheLocalEnRetardNeCreePasDeDoublonSurLeNas() = runBlocking {
        Likes.toggleNow(client, "music_1")
        // Le NAS contient déjà le titre mais l'état local l'ignore (autre appareil, cache perdu).
        Likes.initForTest(File(stateFile.parentFile, "autre.json"))
        Likes.sync(client)
        assertTrue(Likes.isLiked("music_1"))
        Likes.toggleNow(client, "music_1") // retire
        Likes.toggleNow(client, "music_1") // re-like
        assertEquals(listOf("music_1"), likedOnServer())
    }

    @Test fun laSynchronisationRetireLesDoublonsDuNas() = runBlocking {
        Likes.toggleNow(client, "music_1")
        val list = dsm.playlists.values.single().second
        list.addAll(listOf("music_2", "music_1", "music_2")) // doublons ajoutés ailleurs
        Likes.sync(client)
        assertEquals(listOf("music_1", "music_2"), likedOnServer())
        assertEquals(setOf("music_1", "music_2"), Likes.state.value.ids)
    }

    @Test fun retirerUnLikeEnleveToutesSesOccurrences() = runBlocking {
        Likes.toggleNow(client, "music_1")
        dsm.playlists.values.single().second.addAll(listOf("music_2", "music_1"))
        Likes.initForTest(File(stateFile.parentFile, "b.json"))
        Likes.sync(client) // supprime déjà le doublon ; on réintroduit pour tester le retrait
        dsm.playlists.values.single().second.add("music_1")
        Likes.toggleNow(client, "music_1")
        assertFalse("music_1" in likedOnServer())
    }

    @Test fun siLeNasRefuseOnRevientEnArriere() = runBlocking {
        Likes.toggleNow(client, "music_1")
        server.shutdown() // NAS injoignable
        Likes.toggleNow(client, "music_2")
        assertFalse("l'état local ne doit pas annoncer un like que le NAS n'a pas enregistré", Likes.isLiked("music_2"))
        assertTrue(Likes.isLiked("music_1"))
    }

    @Test fun lEtatSurvitAUnRedemarrage() = runBlocking {
        Likes.toggleNow(client, "music_1")
        val id = Likes.state.value.playlistId
        Likes.initForTest(stateFile)
        assertTrue(Likes.isLiked("music_1"))
        assertEquals(id, Likes.state.value.playlistId)
    }

    @Test fun laPlaylistSupprimeeSurLeNasRemetLEtatAZero() = runBlocking {
        Likes.toggleNow(client, "music_1")
        dsm.playlists.clear()
        Likes.sync(client)
        assertTrue(Likes.state.value.ids.isEmpty())
        assertNull(Likes.state.value.playlistId)
    }
}
