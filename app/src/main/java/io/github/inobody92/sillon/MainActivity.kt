package io.github.inobody92.sillon

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inobody92.sillon.data.AppSettings
import io.github.inobody92.sillon.data.Likes
import io.github.inobody92.sillon.data.OfflineStore
import io.github.inobody92.sillon.data.ThemeMode
import io.github.inobody92.sillon.ui.AuthState
import io.github.inobody92.sillon.ui.LoggedInHost
import io.github.inobody92.sillon.ui.LoginScreen
import io.github.inobody92.sillon.ui.OfflineHost
import io.github.inobody92.sillon.ui.MainViewModel
import io.github.inobody92.sillon.ui.PlayerViewModel
import io.github.inobody92.sillon.ui.SillonTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(applicationContext)
        OfflineStore.init(applicationContext)
        Likes.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            val themeMode by AppSettings.theme.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Les icônes de la barre d'état et de navigation suivent le thème choisi, pas celui du système.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(
                        Color.argb(0xe6, 0xFF, 0xFF, 0xFF),
                        Color.argb(0x80, 0x1b, 0x1b, 0x1b),
                    ) { dark },
                )
                onDispose {}
            }
            SillonTheme(dark) {
                Surface(Modifier.fillMaxSize()) {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    when (val s = state) {
                        AuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        is AuthState.LoggedOut -> LoginScreen(
                            state = s,
                            initialUrl = viewModel.lastUrl,
                            initialAccount = viewModel.lastAccount,
                            initialInsecure = viewModel.lastInsecure,
                            onLogin = viewModel::login,
                            modifier = Modifier.safeDrawingPadding(),
                        )
                        AuthState.Offline -> OfflineHost(playerViewModel, onRetry = viewModel::retryConnection)
                        is AuthState.LoggedIn -> LoggedInHost(s.client, playerViewModel, onLogout = viewModel::logout)
                    }
                }
            }
        }
    }
}
