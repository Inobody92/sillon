package io.github.inobody92.sillon.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadRequestTest {
    @Test fun `chaque type de demande survit a la serialisation`() {
        listOf(
            DownloadRequest.Song("123"),
            DownloadRequest.Album("302127"),
            DownloadRequest.Artist("27", 10),
            DownloadRequest.Artist("27", 0),
            DownloadRequest.Link("https://youtu.be/abc"),
        ).forEach { assertEquals(it, downloadRequestFromJson(it.toJson())) }
    }

    @Test fun `une demande illisible est ignoree`() {
        assertNull(downloadRequestFromJson(org.json.JSONObject("""{"type":"inconnu"}""")))
        assertNull(downloadRequestFromJson(org.json.JSONObject("""{"type":"song"}""")))
    }
}
