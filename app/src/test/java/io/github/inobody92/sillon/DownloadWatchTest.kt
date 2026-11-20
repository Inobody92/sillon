package io.github.inobody92.sillon

import io.github.inobody92.sillon.data.RemoteFinished
import io.github.inobody92.sillon.data.RemoteQueue
import io.github.inobody92.sillon.data.finishedToAnnounce
import io.github.inobody92.sillon.data.noticeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadWatchTest {
    private fun fin(n: Int, status: String = "termine", downloaded: Int = 3, errors: Int = 0, error: String? = null) =
        RemoteFinished(n, "Linkin Park", status, downloaded, errors, downloaded + errors, error, 1_700_000_000L + n)

    @Test fun `seules les demandes suivies sont annoncees`() {
        val q = RemoteQueue(null, emptyList(), listOf(fin(1), fin(2), fin(3)))
        assertEquals(listOf(2), finishedToAnnounce(q, setOf(2, 9)).map { it.number })
    }

    @Test fun `termine avec succes`() {
        val n = noticeFor(fin(1, downloaded = 135))
        assertEquals("Linkin Park", n.title)
        assertEquals("135 titres ajoutés à la bibliothèque", n.text)
        assertEquals("1 titre ajouté à la bibliothèque", noticeFor(fin(1, downloaded = 1)).text)
    }

    @Test fun `termine avec des echecs partiels`() {
        assertEquals("10 titres ajoutés · 2 échecs", noticeFor(fin(1, downloaded = 10, errors = 2)).text)
    }

    @Test fun `deja present, echec et annulation`() {
        assertEquals("Déjà dans la bibliothèque", noticeFor(fin(1, downloaded = 0)).text)
        val failed = noticeFor(fin(1, "echec", 0, 1, "YouTube bloque"))
        assertEquals("Échec : Linkin Park", failed.title)
        assertEquals("YouTube bloque", failed.text)
        assertTrue(noticeFor(fin(1, "annule", 0)).title.startsWith("Annulé"))
    }
}
