package io.github.inobody92.sillon.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Redessine le widget après une mise à jour de l'application : Android ne le fait pas toujours, et il resterait
 * vide jusqu'au prochain lancement.
 */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                WidgetStore.ensureLoaded(context)
                SillonWidget().updateAll(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
