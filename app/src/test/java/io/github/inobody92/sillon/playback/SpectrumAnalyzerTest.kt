package io.github.inobody92.sillon.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SpectrumAnalyzerTest {

    private val rate = 44_100

    private fun sine(freq: Float, amplitude: Float, n: Int) =
        FloatArray(n) { (amplitude * sin(2.0 * PI * freq * it / rate)).toFloat() }

    /** Dernière image obtenue en poussant [samples]. */
    private fun lastFrame(samples: FloatArray, analyzer: SpectrumAnalyzer = SpectrumAnalyzer(rate)): FloatArray {
        var last = FloatArray(analyzer.bands)
        analyzer.push(samples, samples.size) { last = it.copyOf() }
        return last
    }

    @Test fun lesBandesCouvrentLesFrequencesDansLOrdre() {
        val f = SpectrumAnalyzer(rate).centerFrequencies
        for (i in 1 until f.size) assertTrue("croissantes", f[i] > f[i - 1])
        assertTrue(f.first() in 50f..80f)
        assertTrue(f.last() in 12_000f..16_000f)
    }

    @Test fun uneSinusoideAllumeLaBonneBandeEtPasLesAutres() {
        val analyzer = SpectrumAnalyzer(rate)
        val levels = lastFrame(sine(1_000f, 0.5f, 16_384), analyzer)
        val peak = levels.indices.maxByOrNull { levels[it] }!!
        val f = analyzer.centerFrequencies
        assertTrue("la bande allumée est proche de 1 kHz (${f[peak]} Hz)", f[peak] in 700f..1_400f)
        assertTrue("pic net", levels[peak] > 0.6f)
        // Les bandes éloignées restent presque éteintes.
        assertTrue(levels.first() < 0.15f)
        assertTrue(levels.last() < 0.15f)
    }

    @Test fun lesGravesEtLesAiguesSontBienPlaces() {
        val a = SpectrumAnalyzer(rate)
        val lowPeak = lastFrame(sine(100f, 0.5f, 16_384), SpectrumAnalyzer(rate)).let { l -> l.indices.maxByOrNull { l[it] }!! }
        val highPeak = lastFrame(sine(8_000f, 0.5f, 16_384), SpectrumAnalyzer(rate)).let { l -> l.indices.maxByOrNull { l[it] }!! }
        assertTrue("100 Hz à gauche de 8 kHz", lowPeak < highPeak)
        assertTrue(a.centerFrequencies[lowPeak] < 200f)
        assertTrue(a.centerFrequencies[highPeak] in 5_000f..12_000f)
    }

    @Test fun leSilenceNAllumeRien() {
        val levels = lastFrame(FloatArray(16_384))
        assertTrue(levels.all { it == 0f })
    }

    @Test fun plusFortDonnePlusHaut() {
        val quiet = lastFrame(sine(1_000f, 0.02f, 16_384)).max()
        val loud = lastFrame(sine(1_000f, 0.5f, 16_384)).max()
        assertTrue("$quiet < $loud", quiet < loud)
    }

    @Test fun lesNiveauxResteDansZeroUn() {
        val levels = lastFrame(sine(440f, 1.0f, 16_384))
        assertTrue(levels.all { it in 0f..1f })
    }

    @Test fun uneImageEstProduiteToutesLesQuelquesMillisecondes() {
        val a = SpectrumAnalyzer(rate)
        var frames = 0
        a.push(sine(1_000f, 0.5f, 4096 + 5 * 1024), 4096 + 5 * 1024) { frames++ }
        assertTrue("environ une image tous les 1024 échantillons (reçu $frames)", frames in 5..7)
    }

    @Test fun pasDeSortieAvantQueLaFenetreSoitPleine() {
        val a = SpectrumAnalyzer(rate)
        var frames = 0
        a.push(sine(1_000f, 0.5f, 2000), 2000) { frames++ }
        assertEquals(0, frames)
    }

    @Test fun resetEffaceLEtat() {
        val a = SpectrumAnalyzer(rate)
        lastFrame(sine(1_000f, 0.5f, 8192), a)
        a.reset()
        var frames = 0
        a.push(sine(1_000f, 0.5f, 2000), 2000) { frames++ }
        assertEquals("il faut de nouveau remplir la fenêtre", 0, frames)
    }

    // --- conversion PCM ---

    private fun pcm16(vararg samples: Short): ByteBuffer =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder()).also { b -> samples.forEach { b.putShort(it) }; b.flip() }

    @Test fun pcm16StereoEstMoyennéEnMono() {
        val out = FloatArray(8)
        val n = PcmMono.convert(pcm16(16384, 0, -16384, -16384), C.ENCODING_PCM_16BIT, 2, out)
        assertEquals(2, n)
        assertEquals(0.25f, out[0], 0.001f)  // (0,5 + 0) / 2
        assertEquals(-0.5f, out[1], 0.001f)
    }

    @Test fun pcmFlottantEstLu() {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).also { it.putFloat(0.5f).putFloat(-0.25f); it.flip() }
        val out = FloatArray(4)
        assertEquals(2, PcmMono.convert(buf, C.ENCODING_PCM_FLOAT, 1, out))
        assertEquals(0.5f, out[0], 0f)
        assertEquals(-0.25f, out[1], 0f)
    }

    @Test fun unFormatNonPrisEnChargeNAffichePasDeFaux() {
        assertEquals(0, PcmMono.convert(pcm16(1, 2, 3, 4), C.ENCODING_PCM_24BIT, 2, FloatArray(8)))
    }

    @Test fun laLectureDuTamponNeLeConsommePas() {
        val buf = pcm16(100, 200)
        PcmMono.convert(buf, C.ENCODING_PCM_16BIT, 1, FloatArray(4))
        assertEquals("le tampon doit rester intact pour la sortie audio", 4, buf.remaining())
    }

    // --- bus partagé ---

    @Test fun leBusRenvoieLImageSouhaiteeAvecLeDelai() {
        SpectrumBus.clear()
        SpectrumBus.publish(FloatArray(24) { 0.2f }, nowNanos = 1_000_000_000L)
        SpectrumBus.publish(FloatArray(24) { 0.9f }, nowNanos = 2_000_000_000L)
        val out = FloatArray(24)
        // À t = 2,1 s avec 500 ms de délai, on affiche ce qui jouait à 1,6 s : la première image.
        assertTrue(SpectrumBus.frameAt(2_100_000_000L, 500_000_000L, 2_000_000_000L, out))
        assertEquals(0.2f, out[0], 0f)
        assertTrue(SpectrumBus.frameAt(3_000_000_000L, 500_000_000L, 2_000_000_000L, out))
        assertEquals(0.9f, out[0], 0f)
    }

    @Test fun sansImageRecenteLeBusSignaleLArret() {
        SpectrumBus.clear()
        SpectrumBus.publish(FloatArray(24) { 0.5f }, nowNanos = 1_000_000_000L)
        assertFalse(SpectrumBus.frameAt(5_000_000_000L, 0L, 200_000_000L, FloatArray(24)))
    }
}
