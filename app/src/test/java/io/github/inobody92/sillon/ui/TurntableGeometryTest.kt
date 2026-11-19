package io.github.inobody92.sillon.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class TurntableGeometryTest {

    private val pivot = Offset(Arm.PX, Arm.PY)

    private fun tip(t: Float): Offset {
        val a = Arm.angleFor(t)
        return Offset(pivot.x + Arm.LEN * cos(a), pivot.y + Arm.LEN * sin(a))
    }

    @Test fun laPointeVaDuBordDuDisqueAuBordDeLEtiquette() {
        assertEquals(Arm.R_OUT, hypot(tip(0f).x, tip(0f).y), 0.01f)
        assertEquals(Arm.R_IN, hypot(tip(1f).x, tip(1f).y), 0.01f)
    }

    @Test fun laPointeSeRapprocheDuCentreAuFilDuMorceau() {
        var previous = Float.MAX_VALUE
        for (i in 0..10) {
            val d = hypot(tip(i / 10f).x, tip(i / 10f).y)
            assertTrue("la pointe doit toujours avancer vers le centre", d < previous)
            previous = d
        }
    }

    @Test fun progressionEtAngleSontReciproques() {
        for (i in 0..10) {
            val t = i / 10f
            assertEquals(t, Arm.progressFor(Arm.angleFor(t)), 0.001f)
        }
    }

    /**
     * Régression : `atan2` renvoie un angle négatif quand le doigt passe au-dessus de l'horizontale du pivot,
     * ce qui faisait sauter la lecture en fin de morceau dès qu'on ramenait le bras vers l'extérieur.
     */
    @Test fun unDoigtAuDessusDuPivotDonneLaBonneProgression() {
        for (i in 0..10) {
            val t = i / 10f
            val finger = tip(t) // le doigt est sur la pointe du bras
            assertEquals("progression $t", t, Arm.progressFor(angleOf(finger, pivot)), 0.001f)
        }
        // Cas précis du bug : doigt à gauche et légèrement au-dessus du pivot (début de morceau).
        val aboveLeft = Offset(pivot.x - 1f, pivot.y - 0.15f)
        assertTrue(Arm.progressFor(angleOf(aboveLeft, pivot)) < 0.5f)
    }

    @Test fun lesAnglesHorsPlageSontBornes() {
        assertEquals(0f, Arm.progressFor(angleOf(Offset(pivot.x, pivot.y - 1f), pivot)), 0f)
        assertEquals(1f, Arm.progressFor(angleOf(Offset(pivot.x - 1f, pivot.y + 1f), pivot)), 0f)
    }
}
