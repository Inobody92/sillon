package io.github.inobody92.sillon.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private class FakeSource(
    val artists: Map<String, DzArtist>,
    val related: Map<String, List<DzArtist>> = emptyMap(),
    val albums: Map<String, List<DzAlbum>> = emptyMap(),
    val failingFor: Set<String> = emptySet(),
) : DiscoverySource {
    override suspend fun findArtist(name: String): DzArtist? {
        if (name in failingFor) throw java.io.IOException("Deezer indisponible")
        return artists[normalizeKey(name)]
    }
    override suspend fun related(artistId: String, limit: Int) = related[artistId].orEmpty().take(limit)
    override suspend fun latestAlbums(artistId: String, limit: Int) = albums[artistId].orEmpty().sortedByDescending { it.date }.take(limit)
}

class DiscoveryTest {
    private val today = LocalDate.of(2026, 11, 26)
    private fun a(id: String, name: String, fans: Long?) = DzArtist(id, name, fans, "https://p/$id")
    private fun alb(id: String, title: String, date: String, type: String = "album") = DzAlbum(id, title, date, type, "https://c/$id")

    private val queen = a("1", "Queen", 9_000_000)
    private val muse = a("2", "Muse", 5_000_000)
    private val filters = DiscoveryFilters(
        libraryArtists = setOf(normalizeKey("Queen"), normalizeKey("Muse")),
        libraryAlbums = emptySet(), isHidden = { false }, excluded = emptySet(),
    )

    private fun engine(source: DiscoverySource) = DiscoveryEngine(source) { today }
    private fun seeds() = listOf(Seed("Queen", 1.5), Seed("Muse", 1.0))

    @Test fun `une sortie recente absente de la bibliotheque est proposee`() = runBlocking {
        val src = FakeSource(
            artists = mapOf("queen" to queen),
            albums = mapOf("1" to listOf(alb("10", "Queen Budapest", "2026-11-06"), alb("11", "Vieux", "2019-01-01"))),
        )
        val out = engine(src).compute(listOf(Seed("Queen", 1.5)), filters)
        assertEquals(listOf("Queen Budapest"), out.map { it.title })
        assertEquals(SuggestionKind.NEW_RELEASE, out.single().kind)
        assertEquals("album:10", out.single().key)
        assertEquals("10", out.single().albumId)
    }

    @Test fun `une sortie deja dans la bibliotheque ou refusee n'est pas reproposee`() = runBlocking {
        val src = FakeSource(mapOf("queen" to queen), albums = mapOf("1" to listOf(alb("10", "Queen Budapest", "2026-11-06"), alb("12", "Autre", "2026-10-01"))))
        val inLibrary = filters.copy(libraryAlbums = setOf(albumKey("Queen Budapest", "Queen")))
        assertEquals(listOf("Autre"), engine(src).compute(listOf(Seed("Queen", 1.0)), inLibrary).map { it.title })
        val refused = filters.copy(excluded = setOf("album:12"))
        assertEquals(listOf("Queen Budapest"), engine(src).compute(listOf(Seed("Queen", 1.0)), refused).map { it.title })
    }

    @Test fun `une reedition n'est pas une nouveaute, et une autre edition d'un album possede est ecartee`() = runBlocking {
        val src = FakeSource(
            mapOf("queen" to queen),
            albums = mapOf("1" to listOf(
                alb("10", "The Open Door (Deluxe Edition)", "2026-09-01"), alb("11", "Album Remastered", "2026-09-02"),
                alb("12", "Les 20 ans (Anniversary Edition)", "2026-09-03"), alb("13", "Budapest (Live)", "2026-10-01"),
                alb("14", "Nouveau", "2026-11-01"),
            )),
        )
        val titles = engine(src).compute(listOf(Seed("Queen", 1.0)), filters).map { it.title }
        assertEquals(setOf("Budapest (Live)", "Nouveau"), titles.toSet())
        // Possédé sous son titre de base : « Nouveau (Bonus Tracks) » n'est pas proposé.
        val owned = filters.copy(libraryAlbums = setOf(albumKey("Nouveau", "Queen")))
        val src2 = FakeSource(mapOf("queen" to queen), albums = mapOf("1" to listOf(alb("20", "Nouveau (Bonus Tracks)", "2026-11-02"))))
        assertTrue(engine(src2).compute(listOf(Seed("Queen", 1.0)), owned).isEmpty())
    }

    @Test fun `le titre de base ignore les mentions entre parentheses et les suffixes`() {
        assertEquals("The Open Door", baseTitle("The Open Door (Deluxe Edition)"))
        assertEquals("Album", baseTitle("Album [Remastered]"))
        assertEquals("Album", baseTitle("Album - 2011 Remaster"))
        assertEquals("Sans mention", baseTitle("Sans mention"))
        assertTrue(isReissue("Boy/Girl (Remastered 2008)") && isReissue("Édition Collector") && !isReissue("Budapest"))
    }

    @Test fun `les artistes proches excluent la bibliotheque, les masques et les refuses`() = runBlocking {
        val src = FakeSource(
            artists = mapOf("queen" to queen),
            related = mapOf("1" to listOf(muse, a("3", "Aerosmith", 5_000_000), a("4", "Caché", 4_000_000), a("5", "Refusé", 4_000_000), a("6", "Dire Straits", 2_000_000))),
        )
        val f = filters.copy(isHidden = { it == "Caché" }, excluded = setOf("artist:5"))
        val names = engine(src).compute(listOf(Seed("Queen", 1.0)), f).map { it.title }
        assertEquals(setOf("Aerosmith", "Dire Straits"), names.toSet()) // ni Muse (bibliothèque), ni Caché, ni Refusé
    }

    @Test fun `un artiste cite par plusieurs favoris passe devant`() = runBlocking {
        val commun = a("7", "Commun", 3_000_000)
        val seul = a("8", "Seul", 3_000_000)
        val src = FakeSource(
            artists = mapOf("queen" to queen, "muse" to muse),
            related = mapOf("1" to listOf(seul, commun), "2" to listOf(commun)),
        )
        val out = engine(src).compute(seeds(), filters)
        assertEquals("Commun", out.first { it.kind == SuggestionKind.RELATED }.title)
        assertTrue(out.first { it.title == "Commun" }.reason.contains("Queen") && out.first { it.title == "Commun" }.reason.contains("Muse"))
    }

    @Test fun `deux pepites peu connues sont reservees, y compris a deux pas`() = runBlocking {
        val populaires = (1..6).map { a("p$it", "Populaire $it", 5_000_000L - it) }
        val petit = a("u1", "Petit Groupe", 4_200)
        val minuscule = a("u2", "Tout Petit", 1_100)
        val tropPetit = a("u3", "Quasi Inconnu", 12) // sous le seuil : pas une pépite
        val src = FakeSource(
            artists = mapOf("queen" to queen),
            related = mapOf("1" to populaires + petit, "p1" to listOf(minuscule, tropPetit)),
        )
        val out = engine(src).compute(listOf(Seed("Queen", 1.0)), filters)
        val pepites = out.filter { it.kind == SuggestionKind.UNDERGROUND }
        assertEquals(DiscoveryTuning.UNDERGROUND_SLOTS, pepites.size)
        assertEquals(setOf("Petit Groupe", "Tout Petit"), pepites.map { it.title }.toSet())
        assertTrue(pepites.first { it.title == "Tout Petit" }.reason.startsWith("Pépite · dans l'univers de"))
        assertFalse(out.any { it.title == "Quasi Inconnu" })
        assertEquals(DiscoveryTuning.DAILY_COUNT, out.size)
    }

    @Test fun `jamais plus que le nombre voulu, sans doublon, sorties d'abord puis pepites en dernier`() = runBlocking {
        val many = (1..20).map { a("r$it", "Artiste $it", if (it % 3 == 0) 5_000L else 2_000_000L) }
        val src = FakeSource(
            artists = mapOf("queen" to queen, "muse" to muse),
            related = mapOf("1" to many, "2" to many.reversed()),
            albums = mapOf("1" to listOf(alb("10", "Récent", "2026-11-06")), "2" to listOf(alb("20", "Récent 2", "2026-10-06"))),
        )
        val out = engine(src).compute(seeds(), filters)
        assertEquals(5, out.size)
        assertEquals(out.size, out.map { it.key }.toSet().size)
        assertEquals(out.sortedBy { it.kind.ordinal }, out)
        assertEquals(SuggestionKind.NEW_RELEASE, out.first().kind)
        assertEquals(SuggestionKind.UNDERGROUND, out.last().kind)
    }

    @Test fun `quand il manque des candidats on rend ce qu'on a`() = runBlocking {
        val src = FakeSource(artists = mapOf("queen" to queen), related = mapOf("1" to listOf(a("3", "Seul", 2_000_000))))
        assertEquals(listOf("Seul"), engine(src).compute(listOf(Seed("Queen", 1.0)), filters).map { it.title })
        assertEquals(emptyList<Suggestion>(), engine(FakeSource(emptyMap())).compute(listOf(Seed("Inconnu", 1.0)), filters))
    }

    @Test fun `la panne de Deezer pour un artiste n'empeche pas les autres`() = runBlocking {
        val src = FakeSource(
            artists = mapOf("queen" to queen, "muse" to muse),
            related = mapOf("2" to listOf(a("3", "Aerosmith", 5_000_000))),
            failingFor = setOf("Queen"),
        )
        assertEquals(listOf("Aerosmith"), engine(src).compute(seeds(), filters).map { it.title })
    }

    @Test fun `un artiste masque n'a pas de sortie proposee`() = runBlocking {
        val src = FakeSource(mapOf("queen" to queen), albums = mapOf("1" to listOf(alb("10", "Queen Budapest", "2026-11-06"))))
        assertTrue(engine(src).compute(listOf(Seed("Queen", 1.0)), filters.copy(isHidden = { it == "Queen" })).isEmpty())
    }

    @Test fun `les reponses de Deezer sont lues et filtrees`() {
        val albums = parseDzAlbums(JSONObject("""{"data":[
            {"id":1,"title":"Acoustic","release_date":"2025-08-22","record_type":"album","cover_medium":"c1"},
            {"id":2,"title":"Budapest","release_date":"2026-11-06","record_type":"album","cover_medium":"c2"},
            {"id":3,"title":"Single","release_date":"2026-12-01","record_type":"single"},
            {"id":4,"title":"Sans date","release_date":"","record_type":"album"},
            {"id":5,"title":"EP","release_date":"2026-03-27","record_type":"ep"}]}"""))
        assertEquals(listOf("Budapest", "EP", "Acoustic"), albums.map { it.title })
        val artists = parseDzArtists(JSONObject("""{"data":[{"id":5,"name":"Muse","nb_fan":5000000,"picture_medium":"p"},{"id":6,"name":"X"}]}"""))
        assertEquals(DzArtist("5", "Muse", 5_000_000, "p"), artists[0])
        assertEquals(null, artists[1].fans)
        assertEquals(emptyList<DzArtist>(), parseDzArtists(JSONObject("""{"error":{"code":4}}""")))
    }

    @Test fun `une suggestion survit a la sauvegarde`() {
        val s = Suggestion("album:10", SuggestionKind.NEW_RELEASE, "Budapest", "Queen", "Nouveauté de Queen", "c", "1", "10", 9_000_000, "2026-11-06")
        assertEquals(s, Suggestion.fromJson(s.toJson()))
        val artist = Suggestion("artist:3", SuggestionKind.UNDERGROUND, "Petit", "Petit", "Pépite", null, "3", null, null, null)
        assertEquals(artist, Suggestion.fromJson(artist.toJson()))
        assertEquals(null, Suggestion.fromJson(JSONObject("""{"kind":"?"}""")))
    }

    @Test fun `une suggestion d'artiste relue du disque n'a pas d'identifiant d'album`() {
        // Ce que contient le fichier enregistré pour une suggestion d'artiste : des valeurs JSON nulles.
        val onDisk = """{"key":"artist:3","kind":"UNDERGROUND","title":"UFO","artist":"UFO","reason":"Pépite","cover":null,
            "artistId":"3","albumId":null,"fans":68000,"date":null}"""
        val s = Suggestion.fromJson(JSONObject(onDisk))!!
        assertEquals(null, s.albumId)
        assertEquals(null, s.cover)
        assertEquals(DownloadRequest.Artist("3", 10), DiscoveryActions.requestFor(s, 10))
        assertEquals(DownloadRequest.Artist("3", 0), DiscoveryActions.requestFor(s)) // sans précision : toute la discographie
        // Garde-fou : le texte « null » (ce que renvoie optString sur Android) n'est jamais pris pour un identifiant.
        val asText = Suggestion.fromJson(JSONObject(onDisk.replace("\"albumId\":null", "\"albumId\":\"null\"")))!!
        assertEquals(null, asText.albumId)
    }

    @Test fun `une sortie se telecharge comme un album`() {
        val s = Suggestion("album:10", SuggestionKind.NEW_RELEASE, "Budapest", "Queen", "r", null, "1", "10", null, "2026-11-06")
        assertEquals(DownloadRequest.Album("10"), DiscoveryActions.requestFor(s))
    }

    @Test fun `stringOrNull ignore l'absent, le nul et le texte null`() {
        val o = JSONObject("""{"a":"x","b":null,"c":"","d":"null"}""")
        assertEquals("x", o.stringOrNull("a"))
        assertEquals(listOf(null, null, null, null), listOf("b", "c", "d", "absent").map { o.stringOrNull(it) })
    }

    // --- artistes de départ ---

    private fun song(id: String, artist: String) = Song(id, "t$id", artist, "alb", "", 1, 1, 200, "mp3", "Rock")

    @Test fun `les favoris passent avant, puis l'ecoute recente, puis la bibliotheque`() {
        val profile = TasteProfile(listOf(
            TasteSignal("1", 5.0, 1.0, "Queen", "Rock"), TasteSignal("2", 5.0, 1.0, "Queen", "Rock"),
            TasteSignal("3", 1.0, 1.0, "Détesté", "Rock"),
        ))
        val library = listOf(song("a", "Gros"), song("b", "Gros"), song("c", "Petit"))
        val recent = listOf(song("r", "Récent"))
        val seeds = pickSeeds(profile, library, recent, isHidden = { false })
        assertEquals(listOf("Queen", "Récent", "Gros", "Petit"), seeds.map { it.name })
        assertTrue(seeds.none { it.name == "Détesté" }) // mal noté : pas un point de départ
    }

    @Test fun `un artiste masque n'est pas un point de depart, et le nombre est borne`() {
        val library = (1..30).map { song("s$it", "Artiste $it") }
        val seeds = pickSeeds(TasteProfile(emptyList()), library, emptyList(), isHidden = { it == "Artiste 1" })
        assertEquals(DiscoveryTuning.MAX_SEEDS, seeds.size)
        assertFalse(seeds.any { it.name == "Artiste 1" })
        assertNotNull(seeds.firstOrNull())
    }

    @Test fun `le dossier d'attente du bot n'est pas un album`() {
        assertTrue(isTempDownloadAlbum(".telechargement") && isTempDownloadAlbum("  .telechargement "))
        assertFalse(isTempDownloadAlbum("telechargement") || isTempDownloadAlbum(".38 Special") || isTempDownloadAlbum(""))
    }
}
