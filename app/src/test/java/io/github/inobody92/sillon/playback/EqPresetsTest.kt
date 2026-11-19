package io.github.inobody92.sillon.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqPresetsTest {

    private val gains = listOf(4f, 2f, 0f, -2f, -4f)

    @Test fun auxFrequencesDeReferenceOnRetrouveLeGainExact() {
        val out = EqPresets.resample(EqPresets.REFERENCE_HZ, gains, -15f, 15f)
        gains.indices.forEach { assertEquals(gains[it], out[it], 0.001f) }
    }

    @Test fun entreDeuxReferencesLeGainEstInterpole() {
        // 60 Hz -> 4 dB et 230 Hz -> 2 dB : au milieu (sur l'échelle logarithmique) on attend 3 dB.
        val middle = Math.sqrt(60.0 * 230.0).toFloat()
        assertEquals(3f, EqPresets.resample(listOf(middle), gains, -15f, 15f)[0], 0.01f)
    }

    @Test fun auDelaDesExtremitesOnGardeLeDernierGain() {
        val out = EqPresets.resample(listOf(20f, 20_000f), gains, -15f, 15f)
        assertEquals(4f, out[0], 0f)
        assertEquals(-4f, out[1], 0f)
    }

    @Test fun lesGainsSontBornesALaPlageDeLAppareil() {
        val out = EqPresets.resample(EqPresets.REFERENCE_HZ, listOf(10f, 10f, 0f, -10f, -10f), -6f, 6f)
        assertEquals(6f, out[0], 0f)
        assertEquals(-6f, out[4], 0f)
    }

    @Test fun uneBandeDePlusQueCinqEstSupportee() {
        val out = EqPresets.resample(listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f), gains, -15f, 15f)
        assertEquals(10, out.size)
        // Courbe décroissante dans l'exemple : jamais de remontée.
        for (i in 1 until out.size) assertTrue(out[i] <= out[i - 1] + 0.0001f)
    }

    @Test fun lesPrereglagesSontCoherents() {
        assertEquals("les identifiants sont uniques", EqPresets.all.size, EqPresets.all.map { it.id }.toSet().size)
        EqPresets.all.forEach { p ->
            assertEquals(EqPresets.REFERENCE_HZ.size, p.gains.size)
            assertTrue("${p.label} reste modéré", p.gains.all { it in -6f..6f })
        }
        assertTrue("le neutre ne change rien", EqPresets.byId(EqPresets.NEUTRAL_ID)!!.gains.all { it == 0f })
        assertTrue(EqPresets.byId(EqPresets.CUSTOM_ID) == null)
    }

    @Test fun laSauvegardeSeRelitIdentique() {
        assertEquals(gains, EqPresets.decode(EqPresets.encode(gains)))
    }

    @Test fun uneValeurAbimeeRevientAuNeutre() {
        val neutral = EqPresets.all.first().gains
        assertEquals(neutral, EqPresets.decode(null))
        assertEquals(neutral, EqPresets.decode("1,2,abc"))
        assertEquals(neutral, EqPresets.decode("1,2,3"))
    }

    @Test fun uneValeurHorsPlageEstRamenee() {
        assertEquals(listOf(10f, -10f, 0f, 0f, 0f), EqPresets.decode("99,-99,0,0,0"))
    }
}
