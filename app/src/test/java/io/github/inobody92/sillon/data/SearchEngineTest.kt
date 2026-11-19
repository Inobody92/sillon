package io.github.inobody92.sillon.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {

    private fun song(id: String, title: String, artist: String, album: String, genre: String = "", albumArtist: String = artist) =
        Song(id, title, artist, album, albumArtist, track = 1, disc = 1, durationSec = 200, ext = "m4a", genre = genre)

    private val engine = SearchEngine(
        listOf(
            song("1", "James Joint", "Rihanna", "ANTI", "R&B"),
            song("2", "Work", "Rihanna", "ANTI", "R&B"),
            song("3", "Alcaline", "Mylène Farmer", "Interstellaires", "Chanson française"),
            song("4", "2033", "Indochine", "13", "Rock"),
            song("5", "Henry Darger", "Indochine", "13", "Rock"),
            song("6", "Dernière chanson (Remastered)", "Francis Cabrel", "Fragile", "Chanson française"),
        ),
    )

    @Test fun normalisationEnleveAccentsCasseEtPonctuation() {
        assertEquals("mylene farmer", SearchEngine.norm("  Mylène   FARMER!! "))
        assertEquals("c est la vie", SearchEngine.norm("C'est la vie"))
    }

    @Test fun accentsIgnores() {
        assertEquals("Mylène Farmer", engine.search("mylene").artists.first().name)
        assertEquals("Mylène Farmer", engine.search("MYLÈNE").artists.first().name)
    }

    @Test fun fauteDeFrappeTolerée() {
        assertEquals("Indochine", engine.search("indochne").artists.first().name) // lettre manquante
        assertEquals("Indochine", engine.search("indochien").artists.first().name) // lettres échangées
    }

    @Test fun motsDansUnOrdreQuelconqueEtSurPlusieursChamps() {
        assertEquals("James Joint", engine.search("rihanna james joint").songs.first().title)
        assertEquals("James Joint", engine.search("joint james").songs.first().title)
    }

    @Test fun tousLesMotsDoiventEtreTrouves() {
        assertTrue(engine.search("rihanna indochine").songs.isEmpty())
    }

    @Test fun genreSansAccent() {
        val titles = engine.search("chanson francaise").songs.map { it.title }
        assertTrue("Alcaline" in titles)
        assertTrue("Dernière chanson (Remastered)" in titles)
    }

    @Test fun titreExactPasseAvantUneSimpleOccurrence() {
        val e = SearchEngine(listOf(song("1", "Work Out", "A", "X"), song("2", "Work", "B", "Y")))
        assertEquals("Work", e.search("work").songs.first().title)
    }

    @Test fun albumsEtArtistesSontRegroupes() {
        val r = engine.search("indochine 13")
        assertEquals("13", r.albums.first().name)
        assertEquals(2, r.songs.size)
    }

    @Test fun unSeulCaractereNeCompteQuEnDebutDeMot() {
        assertTrue(engine.search("j").songs.any { it.title == "James Joint" })
        assertTrue("Alcaline" !in engine.search("j").songs.map { it.title }) // « j » au milieu d'un mot
    }

    @Test fun rienNeCorrespond() {
        assertTrue(engine.search("zzzqx").isEmpty)
        assertTrue(engine.search("   ").isEmpty)
    }

    @Test fun distanceDEditionAvecEchange() {
        assertEquals(0, SearchEngine.distance("abc", "abc", 2))
        assertEquals(1, SearchEngine.distance("abcd", "abdc", 2)) // échange compté pour 1
        assertEquals(1, SearchEngine.distance("indochine", "indochne", 2))
        assertTrue(SearchEngine.distance("abcdef", "uvwxyz", 2) > 2)
    }

    @Test fun songsByIdsGardeLOrdre() {
        assertEquals(listOf("5", "1"), engine.songsByIds(listOf("5", "inconnu", "1")).map { it.id })
    }
}
