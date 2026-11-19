package io.github.inobody92.sillon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.inobody92.sillon.ui.AlbumsScreen
import io.github.inobody92.sillon.ui.AuthState
import io.github.inobody92.sillon.ui.LoginScreen
import io.github.inobody92.sillon.ui.MainViewModel
import io.github.inobody92.sillon.ui.SillonTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SillonTheme {
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
                        is AuthState.LoggedIn -> AlbumsScreen(s.client, onLogout = viewModel::logout)
                    }
                }
            }
        }
    }
}
