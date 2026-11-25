package io.github.inobody92.sillon.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TasteTest {
    private fun song(id: String, artist: String, genre: String = "Rock", albumArtist: String = "") =
        Song(id, "t$id", artist, "alb", albumArtist, 1, 1, 200, "mp3", genre)

    private fun rated(id: String, rating: Int, artist: String, genre: String = "Rock") = RatedSong(id, rating, artist, genre)

    @Test fun `sans note tout est neutre`() {
        val p = TasteProfile(emptyList())
        assertEquals(1.0, p.weight(song("1", "A")), 1e-9)
    }

    @Test fun `un artiste adore pousse ses titres non notes`() {
        val p = TasteProfile(List(6) { rated("a$it", 5, "Adoré", "Pop") } + List(6) { rated("b$it", 2, "Bof", "Metal") })
        val loved = p.weight(song("x", "Adoré", "Pop"))
        val meh = p.weight(song("y", "Bof", "Metal"))
        assertTrue("$loved devrait dépasser $meh", loved > 1.5 * meh)
        assertEquals(1.0, p.weight(song("z", "Inconnu", "Jazz")), 0.35) // artiste et genre inconnus : proche du neutre
    }

    @Test fun `une seule note ne pese pas autant que plusieurs`() {
        val p = TasteProfile(listOf(rated("1", 5, "Un seul")) + List(8) { rated("b$it", 4, "Régulier") } + List(8) { rated("c$it", 2, "Autre") })
        assertTrue(p.artistAverage(normalizeKey("Régulier"))!! > p.artistAverage(normalizeKey("Un seul"))!!)
    }

    @Test fun `un titre a une etoile est quasiment ecarte`() {
        val p = TasteProfile(listOf(rated("1", 1, "A"), rated("2", 5, "B")))
        assertTrue(p.weight(song("1", "A")) < 0.2 * p.weight(song("2", "B")))
    }

    @Test fun `les accents et la casse ne separent pas un artiste`() {
        val p = TasteProfile(listOf(rated("1", 5, "Beyoncé"), rated("2", 5, "BEYONCE")))
        assertEquals(1, p.artists().size)
        assertEquals(2, p.artists().first().ratedCount)
    }

    @Test fun `exposition inconnue neutre puis decroissante puis qui s'attenue`() {
        val now = 1_000_000_000_000L
        assertEquals(1.0, Exposure.factor(null, now), 1e-9)
        val once = Exposure.factor(ExposureEntry(1, now), now)
        val thrice = Exposure.factor(ExposureEntry(3, now), now)
        assertTrue(once < 1.0 && thrice < once)
        val later = Exposure.factor(ExposureEntry(3, now - 60L * 24 * 3600_000), now)
        assertTrue("avec le temps le titre peut revenir", later > thrice)
    }

    @Test fun `le tirage suit les poids`() {
        val items = listOf("fort" to 9.0, "faible" to 1.0)
        var fort = 0
        repeat(2000) { i ->
            if (weightedSample(items, 1, Random(i), { it.second }).first().first == "fort") fort++
        }
        assertTrue("fort tiré $fort fois sur 2000", fort in 1650..1950) // ~90 %
    }

    @Test fun `le plafond par artiste est respecte`() {
        val items = List(10) { "a$it" to "A" } + List(10) { "b$it" to "B" }
        val out = weightedSample(items, 8, Random(1), { 1.0 }, group = { it.second }, maxPerGroup = 3)
        assertEquals(6, out.size) // 3 + 3 : impossible d'aller plus loin
        assertTrue(out.count { it.second == "A" } <= 3)
    }

    @Test fun `le tirage ne repete pas un element`() {
        val out = weightedSample((1..20).toList(), 20, Random(3), { it.toDouble() })
        assertEquals(20, out.toSet().size)
    }
}
