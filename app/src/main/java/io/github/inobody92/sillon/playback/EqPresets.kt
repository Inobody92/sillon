package io.github.inobody92.sillon.playback

import kotlin.math.ln

/** Un préréglage : un gain en dB à chacune des [EqPresets.REFERENCE_HZ] fréquences de référence. */
data class EqPreset(val id: String, val label: String, val gains: List<Float>)

/**
 * Préréglages de l'égaliseur. Ils sont définis sur 5 fréquences de référence, indépendamment de l'appareil : chaque
 * téléphone expose ses propres bandes (souvent 5, parfois plus), on interpole donc à l'application.
 * Les gains restent modérés (<= +5 dB) : sans marge de réserve, un renforcement trop fort fait saturer.
 */
object EqPresets {
    val REFERENCE_HZ = listOf(60f, 230f, 910f, 3_600f, 14_000f)
    const val CUSTOM_ID = "custom"
    const val NEUTRAL_ID = "neutre"
    const val MAX_DB = 10f

    val all: List<EqPreset> = listOf(
        EqPreset(NEUTRAL_ID, "Neutre", listOf(0f, 0f, 0f, 0f, 0f)),
        EqPreset("basses", "Basses renforcées", listOf(5f, 3f, 0f, 0f, 0f)),
        EqPreset("aigus", "Aigus renforcés", listOf(0f, 0f, 0f, 3f, 5f)),
        EqPreset("voix", "Voix", listOf(-2f, -1f, 2f, 4f, 1f)),
        EqPreset("rock", "Rock", listOf(4f, 2f, -2f, 2f, 4f)),
        EqPreset("pop", "Pop", listOf(-1f, 2f, 4f, 2f, -1f)),
        EqPreset("jazz", "Jazz", listOf(3f, 1f, -1f, 1f, 3f)),
        EqPreset("classique", "Classique", listOf(4f, 2f, -1f, 2f, 3f)),
        EqPreset("electro", "Électro", listOf(5f, 3f, 0f, 2f, 4f)),
        EqPreset("hiphop", "Hip-hop", listOf(5f, 4f, 0f, 1f, 2f)),
        EqPreset("loudness", "Loudness (volume faible)", listOf(4f, 1f, 0f, 1f, 3f)),
    )

    fun byId(id: String): EqPreset? = all.firstOrNull { it.id == id }

    /**
     * Gain à appliquer à chaque bande de fréquence centrale [centerHz], par interpolation linéaire sur l'échelle
     * logarithmique des fréquences entre les points de [gains] (calés sur [REFERENCE_HZ]). Au-delà des extrémités,
     * on garde le gain de la dernière référence. Le résultat est borné à [minDb]..[maxDb].
     */
    fun resample(centerHz: List<Float>, gains: List<Float>, minDb: Float, maxDb: Float): List<Float> {
        require(gains.size == REFERENCE_HZ.size) { "Il faut ${REFERENCE_HZ.size} gains" }
        return centerHz.map { f ->
            val value = when {
                f <= REFERENCE_HZ.first() -> gains.first()
                f >= REFERENCE_HZ.last() -> gains.last()
                else -> {
                    val upper = REFERENCE_HZ.indexOfFirst { it >= f }
                    val lower = upper - 1
                    val t = ln(f / REFERENCE_HZ[lower]) / ln(REFERENCE_HZ[upper] / REFERENCE_HZ[lower])
                    gains[lower] + (gains[upper] - gains[lower]) * t
                }
            }
            value.coerceIn(minDb, maxDb)
        }
    }

    fun encode(gains: List<Float>): String = gains.joinToString(",")

    /** Relit des gains sauvegardés ; en cas de valeur absente ou abîmée, revient au neutre. */
    fun decode(text: String?): List<Float> {
        val parsed = text?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }
        return if (parsed != null && parsed.size == REFERENCE_HZ.size) parsed.map { it.coerceIn(-MAX_DB, MAX_DB) }
        else all.first().gains
    }
}
