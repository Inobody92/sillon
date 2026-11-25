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
        val p = TasteProfile.fromRatings(emptyList())
        assertEquals(1.0, p.weight(song("1", "A")), 1e-9)
    }

    @Test fun `un artiste adore pousse ses titres non notes`() {
        val p = TasteProfile.fromRatings(List(6) { rated("a$it", 5, "Adoré", "Pop") } + List(6) { rated("b$it", 2, "Bof", "Metal") })
        val loved = p.weight(song("x", "Adoré", "Pop"))
        val meh = p.weight(song("y", "Bof", "Metal"))
        assertTrue("$loved devrait dépasser $meh", loved > 1.5 * meh)
        assertEquals(1.0, p.weight(song("z", "Inconnu", "Jazz")), 0.35) // artiste et genre inconnus : proche du neutre
    }

    @Test fun `une seule note ne pese pas autant que plusieurs`() {
        val p = TasteProfile.fromRatings(listOf(rated("1", 5, "Un seul")) + List(8) { rated("b$it", 4, "Régulier") } + List(8) { rated("c$it", 2, "Autre") })
        assertTrue(p.artistAverage(normalizeKey("Régulier"))!! > p.artistAverage(normalizeKey("Un seul"))!!)
    }

    @Test fun `un titre a une etoile est quasiment ecarte`() {
        val p = TasteProfile.fromRatings(listOf(rated("1", 1, "A"), rated("2", 5, "B")))
        assertTrue(p.weight(song("1", "A")) < 0.2 * p.weight(song("2", "B")))
    }

    @Test fun `un like vaut cinq etoiles et une ecoute pese moins qu'une note`() {
        val like = TasteSignal("1", 5.0, 1.0, "Aimé", "Pop")
        val listened = implicitSignal("2", completes = 2, skips = 0, artistName = "Écouté", genreName = "Pop")!!
        assertTrue(listened.weight < like.weight)
        assertTrue(listened.value in 3.5..4.5)
        val p = TasteProfile(listOf(like, listened, TasteSignal("3", 2.0, 1.0, "Bof", "Metal")))
        assertEquals(2, p.ratedCount) // le signal d'écoute n'est pas une note
        assertTrue(p.weight(song("x", "Aimé", "Pop")) > p.weight(song("y", "Bof", "Metal")))
    }

    @Test fun `un titre passe plusieurs fois est tire vers le bas, sans etre ecarte`() {
        val skipped = implicitSignal("1", completes = 0, skips = 3, artistName = "A", genreName = "Rock")!!
        assertTrue(skipped.value < 1.7)
        val p = TasteProfile(listOf(skipped, TasteSignal("2", 5.0, 1.0, "B", "Rock")))
        assertTrue(p.weight(song("1", "A")) > 0.1 * p.weight(song("2", "B")))
    }

    @Test fun `aucun evenement d'ecoute, aucun signal`() {
        assertEquals(null, implicitSignal("1", 0, 0, "A", "Rock"))
    }

    @Test fun `les accents et la casse ne separent pas un artiste`() {
        val p = TasteProfile.fromRatings(listOf(rated("1", 5, "Beyoncé"), rated("2", 5, "BEYONCE")))
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

    @Test fun `un enchainement automatique est un titre fini`() {
        assertEquals(ListenEvent.COMPLETED, classifyListen(180_000, 180_000, automatic = true))
        assertEquals(ListenEvent.COMPLETED, classifyListen(170_000, 180_000, automatic = false)) // presque fini, passé à la main
    }

    @Test fun `passer apres quelques secondes est un avis, pas avant`() {
        assertEquals(null, classifyListen(1_500, 200_000, automatic = false)) // on cherche un titre
        assertEquals(ListenEvent.SKIPPED, classifyListen(12_000, 200_000, automatic = false))
        assertEquals(null, classifyListen(90_000, 200_000, automatic = false)) // écouté longtemps : pas d'avis net
    }

    @Test fun `un titre court est jugé sur sa duree`() {
        assertEquals(ListenEvent.SKIPPED, classifyListen(5_000, 40_000, automatic = false))
        assertEquals(null, classifyListen(20_000, 40_000, automatic = false))
    }
}

class NasProfileTest {
    @Test fun `le profil survit a l'ecriture et a la relecture`() {
        val p = NasProfile.Profile(
            exposure = mapOf("1" to ExposureEntry(3, 1_700_000_000_000L), "2" to ExposureEntry(1, 5L)),
            listen = mapOf("1" to ListenCounts(2, 1)),
        )
        assertEquals(p, NasProfile.parse(NasProfile.serialize(p)))
    }

    @Test fun `un fichier illisible n'efface rien`() {
        assertEquals(null, NasProfile.parse("pas du json"))
    }

    @Test fun `la fusion garde l'exposition la plus recente`() {
        val local = mapOf("a" to ExposureEntry(5, 100L), "b" to ExposureEntry(1, 50L))
        val remote = mapOf("a" to ExposureEntry(2, 200L), "c" to ExposureEntry(4, 10L))
        val merged = NasProfile.mergeExposure(local, remote)
        assertEquals(ExposureEntry(2, 200L), merged["a"]) // plus récent
        assertEquals(ExposureEntry(1, 50L), merged["b"])
        assertEquals(ExposureEntry(4, 10L), merged["c"])
    }

    @Test fun `la fusion des ecoutes prend le maximum sans double compte`() {
        val merged = NasProfile.mergeListen(mapOf("a" to ListenCounts(3, 0)), mapOf("a" to ListenCounts(1, 2), "b" to ListenCounts(1, 0)))
        assertEquals(ListenCounts(3, 2), merged["a"])
        assertEquals(ListenCounts(1, 0), merged["b"])
    }
}
