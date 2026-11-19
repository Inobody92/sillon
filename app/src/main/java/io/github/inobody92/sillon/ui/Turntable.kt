package io.github.inobody92.sillon.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Géométrie du bras de lecture, en unités du rayon du disque (centre du disque = origine, y vers le bas).
 *
 * Le bras pivote autour de [PX],[PY] (en haut à droite). Sa pointe décrit un arc qui traverse le disque du bord
 * ([R_OUT]) à l'étiquette ([R_IN]) ; l'angle du bras est interpolé linéairement entre les deux, ce qui en fait un
 * curseur approximatif (comme un vrai bras, la distance parcourue sur le disque n'est pas strictement linéaire).
 */
internal object Arm {
    const val PX = 0.919f
    const val PY = -0.919f
    const val LEN = 1.20f
    const val R_OUT = 0.95f
    const val R_IN = 0.38f

    private val pivotDistance = hypot(PX, PY)
    private val towardCenter = atan2(-PY, -PX)

    /** Angle du bras (radians) pour que la pointe soit à la distance [r] du centre (loi des cosinus). */
    private fun angleAt(r: Float): Float {
        val c = (pivotDistance * pivotDistance + LEN * LEN - r * r) / (2f * pivotDistance * LEN)
        return towardCenter + acos(c.coerceIn(-1f, 1f))
    }

    private val outAngle = angleAt(R_OUT)
    private val inAngle = angleAt(R_IN)

    /** Angle du bras pour une progression [t] de 0 (début du morceau) à 1 (fin). */
    fun angleFor(t: Float): Float = outAngle + (inAngle - outAngle) * t

    /** Progression correspondant à un angle de bras donné (le doigt tire le bras : seule la direction compte). */
    fun progressFor(angle: Float): Float = ((outAngle - angle) / (outAngle - inAngle)).coerceIn(0f, 1f)
}

/** Vitesse d'un 45 tours : 45 tours par minute, soit 1,33 s par rotation. */
private const val RPM = 45
private const val SPIN_MS = 60_000 / RPM
private const val DEG_PER_SEC = 360f * RPM / 60f

private const val SPIN_UP_MS = 600
private const val SPIN_DOWN_MS = 900

/**
 * Démarrage et arrêt à accélération constante : la vitesse part de zéro (ou y arrive) en ligne droite, et
 * se raccorde sans à-coup à la rotation uniforme. Pour une accélération constante, l'angle parcouru est
 * vitesse x durée / 2.
 */
private val SpinUpEasing = Easing { t -> t * t }
private val SpinDownEasing = Easing { t -> 1f - (1f - t) * (1f - t) }
private const val SPIN_UP_DEG = DEG_PER_SEC * SPIN_UP_MS / 1000f / 2f
private const val SPIN_DOWN_DEG = DEG_PER_SEC * SPIN_DOWN_MS / 1000f / 2f

/**
 * Platine vinyle : le disque tourne pendant la lecture et s'arrête en pause, la pochette fait office d'étiquette,
 * et le bras avance vers le centre avec le morceau. On peut attraper le bras pour se déplacer dans le morceau ;
 * toucher le disque lance ou met en pause.
 */
@Composable
fun Turntable(
    artwork: String?,
    imageLoader: ImageLoader,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Respecte le réglage Android « supprimer les animations » : le disque reste alors immobile.
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    val angle = remember { Animatable(0f) }
    LaunchedEffect(playing, reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        if (playing) {
            // Démarrage progressif, puis rotation continue à vitesse constante.
            angle.animateTo(angle.value + SPIN_UP_DEG, tween(SPIN_UP_MS, easing = SpinUpEasing))
            while (true) {
                angle.snapTo(angle.value % 360f)
                angle.animateTo(angle.value + 360f, tween(SPIN_MS, easing = LinearEasing))
            }
        } else {
            // Le disque ralentit avant de s'arrêter, comme un plateau qu'on freine.
            angle.animateTo(angle.value + SPIN_DOWN_DEG, tween(SPIN_DOWN_MS, easing = SpinDownEasing))
        }
    }

    val target = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(target, tween(450, easing = LinearEasing), label = "bras")
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val shown = dragProgress ?: animatedProgress
    val shownState = rememberUpdatedState(shown)
    val durationState = rememberUpdatedState(durationMs)

    val plinth = MaterialTheme.colorScheme.surfaceContainerHigh
    val armColor = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val vinyl = Color(0xFF15100F)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(plinth)
            .semantics {
                contentDescription = "Platine vinyle. Fais glisser le bras pour avancer ou reculer dans le morceau."
                stateDescription = "${formatTime(positionMs)} sur ${formatTime(durationMs)}"
            }
            .pointerInput(Unit) {
                var grabbing = false
                detectDragGestures(
                    onDragStart = { start ->
                        val w = size.width.toFloat()
                        val g = geometry(w)
                        val t = shownState.value
                        val a = Arm.angleFor(t)
                        val pivot = Offset(g.cx + Arm.PX * g.r, g.cy + Arm.PY * g.r)
                        val tip = pivot + Offset(cos(a), sin(a)) * (Arm.LEN * g.r)
                        // On n'attrape le bras que si le doigt est près de lui (sinon c'est un simple geste sur le disque).
                        grabbing = distanceToSegment(start, pivot, tip) < 0.30f * g.r
                        if (grabbing) dragProgress = Arm.progressFor(angleOf(start, pivot))
                    },
                    onDrag = { change, _ ->
                        if (grabbing) {
                            val g = geometry(size.width.toFloat())
                            val pivot = Offset(g.cx + Arm.PX * g.r, g.cy + Arm.PY * g.r)
                            dragProgress = Arm.progressFor(angleOf(change.position, pivot))
                            change.consume()
                        }
                    },
                    onDragEnd = {
                        val p = dragProgress
                        if (grabbing && p != null && durationState.value > 0) onSeek((p * durationState.value).toLong())
                        grabbing = false
                        dragProgress = null
                    },
                    onDragCancel = {
                        grabbing = false
                        dragProgress = null
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val g = geometry(size.width.toFloat())
                    if (hypot(tap.x - g.cx, tap.y - g.cy) <= g.r) onTogglePlay()
                }
            },
    ) {
        val sizePx = with(LocalDensity.current) { maxWidth.toPx() }
        val g = geometry(sizePx)
        val labelR = g.r * 0.34f

        // Plateau : disque noir, sillons, reflets fixes (la lumière ne tourne pas avec le disque).
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(g.cx, g.cy)
            drawCircle(Color.Black.copy(alpha = 0.30f), radius = g.r * 1.03f, center = center + Offset(0f, g.r * 0.02f))
            drawCircle(vinyl, radius = g.r, center = center)
            for (k in 0..11) {
                val rr = g.r * (0.42f + 0.048f * k)
                drawCircle(
                    Color.White.copy(alpha = if (k % 3 == 0) 0.075f else 0.035f),
                    radius = rr,
                    center = center,
                    style = Stroke(width = 1f),
                )
            }
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent,
                        Color.Transparent, Color.White.copy(alpha = 0.07f), Color.Transparent,
                    ),
                    center = center,
                ),
                radius = g.r,
                center = center,
            )
            drawCircle(Color.Black.copy(alpha = 0.45f), radius = labelR * 1.07f, center = center)
        }

        // Étiquette : la pochette, qui tourne avec le disque.
        Box(
            Modifier
                .offset { IntOffset((g.cx - labelR).roundToInt(), (g.cy - labelR).roundToInt()) }
                .size(with(LocalDensity.current) { (labelR * 2).toDp() })
                .graphicsLayer { rotationZ = angle.value }
                .clip(CircleShape),
        ) {
            CoverImage(url = artwork, imageLoader = imageLoader, contentDescription = null, modifier = Modifier.fillMaxSize())
        }

        // Axe central et bras de lecture.
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(g.cx, g.cy)
            drawCircle(Color(0xFFD6CDCD), radius = g.r * 0.028f, center = center)

            val a = Arm.angleFor(shown)
            val dir = Offset(cos(a), sin(a))
            val pivot = Offset(g.cx + Arm.PX * g.r, g.cy + Arm.PY * g.r)
            val tip = pivot + dir * (Arm.LEN * g.r)
            val tail = pivot - dir * (0.10f * g.r)
            val shadow = Offset(g.r * 0.03f, g.r * 0.05f)

            // Ombre portée du bras sur le disque.
            drawLine(Color.Black.copy(alpha = 0.30f), tail + shadow, tip + shadow, strokeWidth = g.r * 0.05f, cap = StrokeCap.Round)
            // Tube, contrepoids, socle.
            drawLine(armColor, tail, tip - dir * (0.14f * g.r), strokeWidth = g.r * 0.045f, cap = StrokeCap.Round)
            drawCircle(armColor.copy(alpha = 0.85f), radius = g.r * 0.075f, center = tail)
            drawCircle(armColor, radius = g.r * 0.11f, center = pivot)
            drawCircle(plinth, radius = g.r * 0.055f, center = pivot)
            // Cellule et pointe (couleur d'accent : repère visuel de la position).
            drawLine(Color(0xFF2A2323), tip - dir * (0.16f * g.r), tip, strokeWidth = g.r * 0.085f, cap = StrokeCap.Round)
            drawCircle(accent, radius = g.r * 0.026f, center = tip)
        }
    }
}

private class Geometry(val cx: Float, val cy: Float, val r: Float)

/** Disposition du plateau dans un carré de côté [w] : le disque est centré et occupe presque toute la largeur ; le pivot du bras est collé dans le coin haut droit, il ne prend donc aucune place sur les côtés. */
private fun geometry(w: Float) = Geometry(cx = w * 0.50f, cy = w * 0.50f, r = w * 0.43f)

/**
 * Angle de la direction pivot -> doigt, ramené dans [0, 2π[. Sans cela, `atan2` renvoie un angle négatif dès que le
 * doigt passe au-dessus de l'horizontale du pivot, et le bras saute de l'autre côté du cercle.
 */
internal fun angleOf(point: Offset, pivot: Offset): Float {
    val a = atan2(point.y - pivot.y, point.x - pivot.x)
    return if (a < 0f) a + 2f * Math.PI.toFloat() else a
}

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val len2 = ab.x * ab.x + ab.y * ab.y
    if (len2 == 0f) return hypot(p.x - a.x, p.y - a.y)
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
    val proj = Offset(a.x + ab.x * t, a.y + ab.y * t)
    return hypot(p.x - proj.x, p.y - proj.y)
}
