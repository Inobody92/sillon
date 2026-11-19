package io.github.inobody92.sillon.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import io.github.inobody92.sillon.data.AppSettings
import java.io.File

/**
 * Cache disque des morceaux déjà lus ou préchargés. Un titre écouté deux fois, ou rejoué après une coupure
 * réseau, ne repasse pas par le NAS. Il vit dans le dossier de cache (Android peut le vider si le téléphone
 * manque de place) et s'efface tout seul, du plus ancien au plus récent, au-delà de la taille choisie.
 */
@UnstableApi
object AudioCache {

    private var cache: SimpleCache? = null

    private fun dir(context: Context) = File(context.applicationContext.cacheDir, "audio")

    /** Une seule instance par dossier est autorisée : le service et les réglages passent par ici. */
    @Synchronized
    fun get(context: Context): SimpleCache =
        cache ?: SimpleCache(
            dir(context).apply { mkdirs() },
            // La taille est lue à la création : un changement dans les réglages s'applique au prochain démarrage.
            LeastRecentlyUsedCacheEvictor(AppSettings.cacheMaxMb.value * 1024L * 1024L),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }

    fun usedBytes(context: Context): Long = get(context).cacheSpace

    /** Vide le cache sans casser une lecture en cours (on retire les entrées une à une). */
    fun clear(context: Context) {
        val c = get(context)
        c.keys.toList().forEach { c.removeResource(it) }
    }
}
