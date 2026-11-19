package io.github.inobody92.sillon.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spectre en [bands] bandes de fréquences réparties en échelle logarithmique (comme l'oreille), calculé par
 * transformée de Fourier d'une fenêtre glissante. Aucune dépendance à Android : il se teste sur un ordinateur.
 *
 * Chaque image est un tableau de niveaux entre 0 et 1, après compensation de la pente naturelle de la musique
 * (+3 dB par octave : sans elle, les graves écrasent tout et les aigus restent éteints).
 */
class SpectrumAnalyzer(
    private val sampleRate: Int,
    val bands: Int = DEFAULT_BANDS,
    private val fftSize: Int = 4096,
    private val hop: Int = 1024,
) {
    private val ring = FloatArray(fftSize)
    private var write = 0
    private var total = 0L
    private var sinceFrame = 0

    private val window = FloatArray(fftSize) { 0.5f * (1f - cos(2.0 * PI * it / (fftSize - 1)).toFloat()) }
    private val windowSum = window.sum()
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)

    // Table de Fourier précalculée et ordre des bits (FFT itérative en place).
    private val cosTable = FloatArray(fftSize / 2) { cos(2.0 * PI * it / fftSize).toFloat() }
    private val sinTable = FloatArray(fftSize / 2) { -sin(2.0 * PI * it / fftSize).toFloat() }
    private val bitReverse = IntArray(fftSize).also { table ->
        val bits = Integer.numberOfTrailingZeros(fftSize)
        for (i in 0 until fftSize) table[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Fréquence centrale de chaque bande (Hz). */
    val centerFrequencies = FloatArray(bands)
    private val firstBin = IntArray(bands)
    private val lastBin = IntArray(bands)
    private val tiltDb = FloatArray(bands)
    private val levels = FloatArray(bands)

    init {
        require(fftSize and (fftSize - 1) == 0) { "La taille de la FFT doit être une puissance de 2" }
        val binWidth = sampleRate.toFloat() / fftSize
        val maxFrequency = minOf(MAX_HZ, sampleRate / 2f * 0.95f)
        val ratio = (maxFrequency / MIN_HZ).toDouble()
        for (b in 0 until bands) {
            val lo = MIN_HZ * ratio.pow(b.toDouble() / bands).toFloat()
            val hi = MIN_HZ * ratio.pow((b + 1).toDouble() / bands).toFloat()
            centerFrequencies[b] = sqrt(lo * hi)
            val first = (lo / binWidth).toInt().coerceAtLeast(1)
            // Une bande plus étroite qu'une case de la FFT prend au moins cette case.
            firstBin[b] = first
            lastBin[b] = maxOf(first, (hi / binWidth).toInt() - 1).coerceAtMost(fftSize / 2 - 1)
            tiltDb[b] = TILT_DB_PER_OCTAVE * log2(centerFrequencies[b] / TILT_REFERENCE_HZ)
        }
    }

    /**
     * Ajoute [count] échantillons mono (entre -1 et 1). Chaque fois qu'une nouvelle image est prête (toutes les
     * [hop] échantillons), [onFrame] reçoit les niveaux ; le tableau est réutilisé : à copier si on le garde.
     */
    fun push(samples: FloatArray, count: Int, onFrame: (FloatArray) -> Unit) {
        for (i in 0 until count) {
            ring[write] = samples[i]
            write = (write + 1) % fftSize
            total++
            sinceFrame++
            if (total >= fftSize && sinceFrame >= hop) {
                sinceFrame = 0
                compute()
                onFrame(levels)
            }
        }
    }

    fun reset() {
        ring.fill(0f)
        write = 0
        total = 0
        sinceFrame = 0
        levels.fill(0f)
    }

    private fun compute() {
        // La fenêtre de Hann évite que le bord de la fenêtre étale le signal sur tout le spectre.
        for (i in 0 until fftSize) {
            re[bitReverse[i]] = ring[(write + i) % fftSize] * window[i]
            im[bitReverse[i]] = 0f
        }
        var size = 2
        while (size <= fftSize) {
            val half = size / 2
            val step = fftSize / size
            var start = 0
            while (start < fftSize) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val tr = re[j + half] * wr - im[j + half] * wi
                    val ti = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - tr
                    im[j + half] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
        for (b in 0 until bands) {
            var peak = 0f
            for (bin in firstBin[b]..lastBin[b]) {
                val magnitude = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                if (magnitude > peak) peak = magnitude
            }
            // Amplitude d'une sinusoïde équivalente : 1,0 pour une sinusoïde pleine échelle.
            val amplitude = 2f * peak / windowSum
            val db = 20f * log10(amplitude + 1e-9f) + tiltDb[b]
            levels[b] = ((db - FLOOR_DB) / (CEIL_DB - FLOOR_DB)).coerceIn(0f, 1f)
        }
    }

    companion object {
        const val DEFAULT_BANDS = 24
        const val MIN_HZ = 50f
        const val MAX_HZ = 16_000f
        const val TILT_DB_PER_OCTAVE = 2.5f
        const val TILT_REFERENCE_HZ = 100f
        const val FLOOR_DB = -52f
        const val CEIL_DB = -2f
    }
}
