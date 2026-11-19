package io.github.inobody92.sillon.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/** Redessine tous les widgets de Sillon (grand et compact). */
object Widgets {
    suspend fun updateAll(context: Context) {
        val app = context.applicationContext
        SillonWidget().updateAll(app)
        SillonCompactWidget().updateAll(app)
    }
}
