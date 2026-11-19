package io.github.inobody92.sillon.playback

import android.media.audiofx.Equalizer
import androidx.media3.common.C

/**
 * Égaliseur d'Android branché sur la session audio du lecteur : il agit sur tout ce qui sort (haut-parleur,
 * Bluetooth, voiture). Se reconfigure tout seul quand la session audio change.
 */
class EqualizerEffect {
    private var equalizer: Equalizer? = null
    private var session = C.AUDIO_SESSION_ID_UNSET
    private var enabled = false
    private var gains: List<Float> = EqPresets.all.first().gains

    fun attach(sessionId: Int) {
        if (sessionId == session && equalizer != null) return
        release()
        session = sessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == 0) return
        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        apply()
    }

    fun update(enabled: Boolean, gains: List<Float>) {
        this.enabled = enabled
        this.gains = gains
        apply()
    }

    private fun apply() {
        val eq = equalizer ?: return
        runCatching {
            if (!enabled) {
                eq.enabled = false
                return
            }
            val range = eq.bandLevelRange // millibels : [min, max]
            val bands = eq.numberOfBands.toInt()
            // getCenterFreq renvoie des milli-hertz.
            val centers = List(bands) { eq.getCenterFreq(it.toShort()) / 1000f }
            val levels = EqPresets.resample(centers, gains, range[0] / 100f, range[1] / 100f)
            levels.forEachIndexed { band, db -> eq.setBandLevel(band.toShort(), (db * 100).toInt().toShort()) }
            eq.enabled = true
        }
    }

    fun release() {
        runCatching { equalizer?.release() }
        equalizer = null
    }
}
