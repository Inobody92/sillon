package io.github.inobody92.sillon.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dernières images du spectre, partagées entre le service de lecture (qui les produit) et l'écran (qui les affiche).
 * Les deux vivent dans le même processus : un simple objet partagé suffit, sans permission ni copie d'audio.
 */
object SpectrumBus {
    private const val CAPACITY = 64
    private val times = LongArray(CAPACITY)
    private val frames = Array(CAPACITY) { FloatArray(SpectrumAnalyzer.DEFAULT_BANDS) }
    private var head = 0L
    private val lock = Any()

    fun publish(levels: FloatArray, nowNanos: Long) {
        synchronized(lock) {
            val slot = (head % CAPACITY).toInt()
            System.arraycopy(levels, 0, frames[slot], 0, minOf(levels.size, frames[slot].size))
            times[slot] = nowNanos
            head++
        }
    }

    /**
     * Copie dans [out] l'image la plus récente datant d'au plus [now] - [delayNanos] (le son arrive aux oreilles
     * après un délai de tampon : l'écran doit s'y caler). Renvoie false s'il n'y a rien de récent : le son est arrêté.
     */
    fun frameAt(nowNanos: Long, delayNanos: Long, maxAgeNanos: Long, out: FloatArray): Boolean {
        val target = nowNanos - delayNanos
        synchronized(lock) {
            var i = head - 1
            val oldest = maxOf(0L, head - CAPACITY)
            while (i >= oldest) {
                val slot = (i % CAPACITY).toInt()
                if (times[slot] <= target) {
                    if (target - times[slot] > maxAgeNanos) return false
                    System.arraycopy(frames[slot], 0, out, 0, minOf(out.size, frames[slot].size))
                    return true
                }
                i--
            }
        }
        return false
    }

    fun clear() = synchronized(lock) { head = 0 }
}

/** Convertit un tampon PCM entrelacé en échantillons mono flottants entre -1 et 1. Renvoie le nombre d'échantillons. */
object PcmMono {
    fun convert(buffer: ByteBuffer, encoding: Int, channels: Int, out: FloatArray): Int {
        if (channels <= 0) return 0
        val src = buffer.duplicate().order(ByteOrder.nativeOrder())
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return 0 // 24/32 bits entiers : rares, on n'affiche rien plutôt que d'afficher faux
        }
        val frames = minOf(src.remaining() / (bytesPerSample * channels), out.size)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += if (encoding == C.ENCODING_PCM_16BIT) src.short / 32768f else src.float
            }
            out[f] = sum / channels
        }
        return frames
    }
}

/** Écoute les tampons audio décodés (sans les modifier) et alimente [SpectrumBus]. */
@UnstableApi
class SpectrumTap : TeeAudioProcessor.AudioBufferSink {
    private var analyzer: SpectrumAnalyzer? = null
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private val mono = FloatArray(16_384)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.channels = channelCount
        this.encoding = encoding
        analyzer = if (sampleRateHz > 0 && (encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT)) {
            SpectrumAnalyzer(sampleRateHz)
        } else {
            null
        }
        SpectrumBus.clear()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val a = analyzer ?: return
        var remaining = buffer.duplicate()
        // Un tampon peut dépasser la taille de travail : on le traite par morceaux.
        val bytesPerFrame = (if (encoding == C.ENCODING_PCM_16BIT) 2 else 4) * channels
        val chunkBytes = mono.size * bytesPerFrame
        while (remaining.hasRemaining()) {
            val part = remaining.duplicate()
            if (part.remaining() > chunkBytes) part.limit(part.position() + chunkBytes)
            val n = PcmMono.convert(part, encoding, channels, mono)
            if (n == 0) break
            a.push(mono, n) { levels -> SpectrumBus.publish(levels, System.nanoTime()) }
            remaining.position(remaining.position() + n * bytesPerFrame)
        }
    }
}
