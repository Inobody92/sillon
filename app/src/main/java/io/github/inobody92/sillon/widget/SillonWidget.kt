package io.github.inobody92.sillon.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.unit.ColorProvider
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.inobody92.sillon.MainActivity
import io.github.inobody92.sillon.R
import io.github.inobody92.sillon.playback.PlaybackService
import io.github.inobody92.sillon.ui.SillonDark
import io.github.inobody92.sillon.ui.SillonLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

/** Mêmes couleurs que l'application, clair et sombre (le widget suit le thème du système). */
internal val SillonGlanceColors = ColorProviders(light = SillonLight, dark = SillonDark)

private val COMPACT = DpSize(110.dp, 110.dp)
private val WIDE = DpSize(250.dp, 110.dp)
private val TALL = DpSize(250.dp, 200.dp)

/**
 * Widget de lecture : pochette, titre, artiste et commandes.
 * Petit : la pochette en plein cadre avec un bouton lecture/pause ; large : pochette à gauche, titre et trois boutons ;
 * haut : grande pochette puis titre et boutons.
 */
class SillonWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, WIDE, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        withContext(Dispatchers.IO) { WidgetStore.ensureLoaded(context) }
        provideContent {
            val snapshot by WidgetStore.snapshot.collectAsState()
            val cover by WidgetStore.cover.collectAsState()
            GlanceTheme(colors = SillonGlanceColors) { WidgetContent(snapshot, cover) }
        }
    }
}

class SillonWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SillonWidget()
}

@androidx.compose.runtime.Composable
private fun WidgetContent(s: WidgetSnapshot, cover: Bitmap?) {
    val size = LocalSize.current
    val open = actionStartActivity(MainActivity.openPlayerIntent(LocalContext.current))
    // Tout le fond du widget ramène dans l'application ; les boutons gardent leur propre action.
    val root = GlanceModifier.fillMaxSize().appWidgetBackground()
        .background(GlanceTheme.colors.widgetBackground).cornerRadius(22.dp).clickable(open)
    when {
        size.height >= 180.dp -> Column(root.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val side = minOf(size.width - 28.dp, size.height - 130.dp)
            Cover(cover, GlanceModifier.size(side).clickable(open))
            Spacer(GlanceModifier.height(10.dp))
            Titles(s, open, centered = true)
            Spacer(GlanceModifier.height(8.dp))
            Controls(s, showSkip = true)
        }
        size.width >= 200.dp -> Row(root.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(cover, GlanceModifier.size(size.height - 24.dp).clickable(open))
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                Titles(s, open, centered = false)
                Spacer(GlanceModifier.height(8.dp))
                Controls(s, showSkip = true)
            }
        }
        else -> Box(root.clickable(open), contentAlignment = Alignment.BottomEnd) {
            Cover(cover, GlanceModifier.fillMaxSize())
            Box(GlanceModifier.padding(8.dp)) { PlayButton(s, 44.dp) }
        }
    }
}

@androidx.compose.runtime.Composable
internal fun Cover(bitmap: Bitmap?, modifier: GlanceModifier, radius: Dp = 14.dp) {
    if (bitmap != null) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = "Pochette",
            contentScale = ContentScale.Crop,
            modifier = modifier.cornerRadius(radius),
        )
    } else {
        Box(modifier.background(GlanceTheme.colors.surfaceVariant).cornerRadius(radius), contentAlignment = Alignment.Center) {
            Image(provider = ImageProvider(R.drawable.ic_widget_disc), contentDescription = null, modifier = GlanceModifier.fillMaxSize().padding(10.dp))
        }
    }
}

@androidx.compose.runtime.Composable
private fun Titles(s: WidgetSnapshot, open: Action, centered: Boolean) {
    val align = if (centered) androidx.glance.text.TextAlign.Center else androidx.glance.text.TextAlign.Start
    Column(GlanceModifier.fillMaxWidth().clickable(open), horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text(
            if (s.hasItem) s.title else "Rien en lecture",
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = align),
            maxLines = 1,
        )
        Text(
            if (s.hasItem) s.artist else "Touche lecture pour reprendre",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, textAlign = align),
            maxLines = 1,
        )
    }
}

@androidx.compose.runtime.Composable
private fun Controls(s: WidgetSnapshot, showSkip: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showSkip) {
            IconAction(R.drawable.ic_widget_previous, "Précédent", actionRunCallback<PreviousAction>())
            Spacer(GlanceModifier.width(14.dp))
        }
        PlayButton(s, 44.dp)
        if (showSkip) {
            Spacer(GlanceModifier.width(14.dp))
            IconAction(R.drawable.ic_widget_next, "Suivant", actionRunCallback<NextAction>())
        }
    }
}

@androidx.compose.runtime.Composable
internal fun PlayButton(s: WidgetSnapshot, size: Dp) {
    Box(
        GlanceModifier.size(size).background(GlanceTheme.colors.primary).cornerRadius(size / 2)
            .clickable(actionRunCallback<TogglePlayAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (s.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play),
            contentDescription = if (s.playing) "Pause" else "Lecture",
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
            modifier = GlanceModifier.size(size * 0.55f),
        )
    }
}

@androidx.compose.runtime.Composable
internal fun IconAction(icon: Int, description: String, action: Action, tint: ColorProvider? = null, box: Dp = 38.dp, glyph: Dp = 26.dp) {
    Box(GlanceModifier.size(box).clickable(action), contentAlignment = Alignment.Center) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = description,
            colorFilter = ColorFilter.tint(tint ?: GlanceTheme.colors.onSurface),
            modifier = GlanceModifier.size(glyph),
        )
    }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        WidgetCommands.run(context) { it.seekToPrevious() }
}

class TogglePlayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        WidgetCommands.run(context) { if (it.isPlaying) it.pause() else it.play() }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        WidgetCommands.run(context) { it.seekToNextMediaItem() }
}

/** Envoie une commande au service de lecture (le démarre au besoin) en se connectant comme le fait l'application. */
internal object WidgetCommands {
    suspend fun run(context: Context, block: (MediaController) -> Unit) {
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val controller = try {
            MediaController.Builder(app, token).setApplicationLooper(Looper.getMainLooper()).buildAsync().await()
        } catch (_: Exception) {
            return // service injoignable : rien à faire, le widget garde son dernier état
        }
        try {
            withContext(Dispatchers.Main) {
                // Service qui vient de démarrer : la file d'attente enregistrée se restaure un instant plus tard.
                kotlinx.coroutines.withTimeoutOrNull(4_000) { while (controller.mediaItemCount == 0) delay(100) }
                block(controller)
            }
            delay(500) // laisse partir la commande avant de se déconnecter
        } finally {
            withContext(Dispatchers.Main) { controller.release() }
        }
    }
}
