package io.github.inobody92.sillon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { Home() }
        }
    }
}

@Composable
private fun Home() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Sillon", style = MaterialTheme.typography.displayMedium)
    }
}
