package io.github.inobody92.sillon.data

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.inobody92.sillon.playback.EqPreset
import io.github.inobody92.sillon.playback.EqPresets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ce que le lecteur affiche au centre de l'écran de lecture. */
enum class PlayerVisual(val label: String) {
    TURNTABLE("Platine vinyle"),
    EQUALIZER("Égaliseur"),
    COVER("Pochette"),
}

enum class ThemeMode(val label: String) {
    SYSTEM("Suivre le système"),
    LIGHT("Clair"),
    DARK("Sombre"),
}

/**
 * Qualité de lecture. Hors « originale », le NAS convertit le morceau en MP3 à la volée
 * (`SYNO.AudioStation.Stream` / `transcode`).
 */
enum class AudioQuality(val label: String, val bitrateBps: Int) {
    ORIGINAL("Originale", 0),
    HIGH("Haute · MP3 320 kb/s", 320_000),
    MEDIUM("Moyenne · MP3 192 kb/s", 192_000),
    LOW("Économique · MP3 128 kb/s", 128_000),
}

/** Réglages de l'application, partagés entre l'interface et le service de lecture (même processus). */
object AppSettings {

    private lateinit var prefs: SharedPreferences

    private val _theme = MutableStateFlow(ThemeMode.SYSTEM)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _wifiQuality = MutableStateFlow(AudioQuality.ORIGINAL)
    val wifiQuality: StateFlow<AudioQuality> = _wifiQuality.asStateFlow()

    private val _mobileQuality = MutableStateFlow(AudioQuality.ORIGINAL)
    val mobileQuality: StateFlow<AudioQuality> = _mobileQuality.asStateFlow()

    private val _playerVisual = MutableStateFlow(PlayerVisual.TURNTABLE)
    val playerVisual: StateFlow<PlayerVisual> = _playerVisual.asStateFlow()

    private val _eqEnabled = MutableStateFlow(false)
    val eqEnabled: StateFlow<Boolean> = _eqEnabled.asStateFlow()

    /** Identifiant du préréglage actif, ou [EqPresets.CUSTOM_ID] dès qu'un curseur a été touché. */
    private val _eqPreset = MutableStateFlow(EqPresets.NEUTRAL_ID)
    val eqPreset: StateFlow<String> = _eqPreset.asStateFlow()

    private val _eqGains = MutableStateFlow(EqPresets.all.first().gains)
    val eqGains: StateFlow<List<Float>> = _eqGains.asStateFlow()

    private val _dynamicColors = MutableStateFlow(false)
    val dynamicColors: StateFlow<Boolean> = _dynamicColors.asStateFlow()

    private val _autoDownloadLikes = MutableStateFlow(true)
    val autoDownloadLikes: StateFlow<Boolean> = _autoDownloadLikes.asStateFlow()

    private val _cacheMaxMb = MutableStateFlow(1024)
    val cacheMaxMb: StateFlow<Int> = _cacheMaxMb.asStateFlow()

    private val _downloadWifiOnly = MutableStateFlow(true)
    val downloadWifiOnly: StateFlow<Boolean> = _downloadWifiOnly.asStateFlow()

    /** Idempotent : appelé par l'activité comme par le service, quel que soit celui qui démarre en premier. */
    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("sillon_settings", Context.MODE_PRIVATE)
        _theme.value = read(K_THEME, ThemeMode.SYSTEM)
        _wifiQuality.value = read(K_WIFI, AudioQuality.ORIGINAL)
        _mobileQuality.value = read(K_MOBILE, AudioQuality.ORIGINAL)
        _downloadWifiOnly.value = prefs.getBoolean(K_DL_WIFI, true)
        _cacheMaxMb.value = prefs.getInt(K_CACHE_MB, 1024)
        _autoDownloadLikes.value = prefs.getBoolean(K_AUTO_LIKES, true)
        _dynamicColors.value = prefs.getBoolean(K_DYNAMIC, false)
        _eqEnabled.value = prefs.getBoolean(K_EQ_ON, false)
        _eqPreset.value = prefs.getString(K_EQ_PRESET, null) ?: EqPresets.NEUTRAL_ID
        _eqGains.value = EqPresets.decode(prefs.getString(K_EQ_GAINS, null))
        _playerVisual.value = prefs.getString(K_VISUAL, null)
            ?.let { name -> PlayerVisual.entries.firstOrNull { it.name == name } }
            // Ancien réglage booléen : platine ou pochette seule.
            ?: if (prefs.getBoolean(K_TURNTABLE, true)) PlayerVisual.TURNTABLE else PlayerVisual.COVER
    }

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs.edit().putString(K_THEME, mode.name).apply()
    }

    fun setWifiQuality(quality: AudioQuality) {
        _wifiQuality.value = quality
        prefs.edit().putString(K_WIFI, quality.name).apply()
    }

    fun setPlayerVisual(value: PlayerVisual) {
        _playerVisual.value = value
        prefs.edit().putString(K_VISUAL, value.name).apply()
    }

    fun setEqEnabled(value: Boolean) {
        _eqEnabled.value = value
        prefs.edit().putBoolean(K_EQ_ON, value).apply()
    }

    fun selectEqPreset(preset: EqPreset) = saveEq(preset.id, preset.gains)

    /** Un curseur a bougé : les gains deviennent « personnalisés ». */
    fun setEqBand(index: Int, db: Float) {
        val gains = _eqGains.value.toMutableList()
        gains[index] = db.coerceIn(-EqPresets.MAX_DB, EqPresets.MAX_DB)
        saveEq(EqPresets.CUSTOM_ID, gains)
    }

    private fun saveEq(presetId: String, gains: List<Float>) {
        _eqPreset.value = presetId
        _eqGains.value = gains
        prefs.edit().putString(K_EQ_PRESET, presetId).putString(K_EQ_GAINS, EqPresets.encode(gains)).apply()
    }

    fun setDynamicColors(value: Boolean) {
        _dynamicColors.value = value
        prefs.edit().putBoolean(K_DYNAMIC, value).apply()
    }

    fun setAutoDownloadLikes(value: Boolean) {
        _autoDownloadLikes.value = value
        prefs.edit().putBoolean(K_AUTO_LIKES, value).apply()
    }

    fun setCacheMaxMb(value: Int) {
        _cacheMaxMb.value = value
        prefs.edit().putInt(K_CACHE_MB, value).apply()
    }

    fun setDownloadWifiOnly(value: Boolean) {
        _downloadWifiOnly.value = value
        prefs.edit().putBoolean(K_DL_WIFI, value).apply()
    }

    fun setMobileQuality(quality: AudioQuality) {
        _mobileQuality.value = quality
        prefs.edit().putString(K_MOBILE, quality.name).apply()
    }

    /** Qualité à appliquer maintenant : Wi-Fi / Ethernet d'un côté, données mobiles (ou inconnu) de l'autre. */
    fun currentQuality(context: Context): AudioQuality =
        if (isUnmetered(context)) _wifiQuality.value else _mobileQuality.value

    /** Wi-Fi ou Ethernet : un réseau sur lequel on peut télécharger sans consommer de data mobile. */
    fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        return caps != null &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    private inline fun <reified E : Enum<E>> read(key: String, default: E): E =
        prefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private const val K_THEME = "theme"
    private const val K_WIFI = "quality_wifi"
    private const val K_MOBILE = "quality_mobile"
    private const val K_DL_WIFI = "download_wifi_only"
    private const val K_CACHE_MB = "cache_max_mb"
    private const val K_AUTO_LIKES = "auto_download_likes"
    private const val K_DYNAMIC = "dynamic_colors"
    private const val K_TURNTABLE = "turntable"
    private const val K_VISUAL = "player_visual"
    private const val K_EQ_ON = "eq_enabled"
    private const val K_EQ_PRESET = "eq_preset"
    private const val K_EQ_GAINS = "eq_gains"
}
