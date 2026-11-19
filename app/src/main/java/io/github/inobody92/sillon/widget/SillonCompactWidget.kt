package io.github.inobody92.sillon.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.unit.ColorProvider
import io.github.inobody92.sillon.MainActivity
import io.github.inobody92.sillon.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Widget compact 2×2 : la pochette en plein cadre et, par-dessus, une barre flottante translucide avec précédent,
 * lecture/pause et suivant. Sur une cellule très étroite, seul le bouton lecture/pause reste. Toucher la pochette
 * ramène dans l'application.
 */
class SillonCompactWidget : GlanceAppWidget() {

    // Taille réelle du widget : la barre de commandes s'adapte à la largeur que le lanceur donne à deux cellules.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        withContext(Dispatchers.IO) { WidgetStore.ensureLoaded(context) }
        provideContent {
            val snapshot by WidgetStore.snapshot.collectAsState()
            val cover by WidgetStore.cover.collectAsState()
            GlanceTheme(colors = SillonGlanceColors) { CompactContent(snapshot, cover) }
        }
    }
}

class SillonCompactWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SillonCompactWidget()
}

@Composable
private fun CompactContent(s: WidgetSnapshot, cover: Bitmap?) {
    val open = actionStartActivity(MainActivity.openPlayerIntent(LocalContext.current))
    val showSkip = LocalSize.current.width >= 128.dp
    Box(
        GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(22.dp).clickable(open),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Cover(cover, GlanceModifier.fillMaxSize(), radius = 22.dp)
        Box(GlanceModifier.padding(8.dp)) {
            Row(
                GlanceModifier.background(ColorProvider(Color(0xB3000000))).cornerRadius(24.dp)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val white = ColorProvider(Color.White)
                if (showSkip) {
                    IconAction(R.drawable.ic_widget_previous, "Précédent", actionRunCallback<PreviousAction>(), tint = white, box = 34.dp, glyph = 24.dp)
                    Spacer(GlanceModifier.width(4.dp))
                }
                PlayButton(s, 38.dp)
                if (showSkip) {
                    Spacer(GlanceModifier.width(4.dp))
                    IconAction(R.drawable.ic_widget_next, "Suivant", actionRunCallback<NextAction>(), tint = white, box = 34.dp, glyph = 24.dp)
                }
            }
        }
    }
}
