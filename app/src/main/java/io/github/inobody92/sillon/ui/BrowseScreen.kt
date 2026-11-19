package io.github.inobody92.sillon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.inobody92.sillon.data.Artist
import io.github.inobody92.sillon.data.Genre
import io.github.inobody92.sillon.data.SynoClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BrowseState(
    val artists: List<Artist> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class BrowseViewModel(private val client: SynoClient) : ViewModel() {

    private val _state = MutableStateFlow(BrowseState())
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            _state.value = try {
                // Une seule requête par liste : artistes et genres se comptent en centaines, pas en milliers.
                val (artists, genres) = coroutineScope {
                    val a = async { client.artists(0, -1) }
                    val g = async { client.genres() }
                    a.await() to g.await()
                }
                BrowseState(
                    artists = artists.sortedBy { it.name.lowercase() },
                    genres = genres,
                    loading = false,
                )
            } catch (e: Exception) {
                _state.value.copy(loading = false, error = e.message ?: "Erreur de chargement")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    vm: BrowseViewModel,
    onOpenArtist: (String) -> Unit,
    onOpenGenre: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 = artistes, 1 = genres

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = { TopAppBar(title = { Text("Parcourir") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Artistes") })
                FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Genres") })
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    state.error != null && state.artists.isEmpty() && state.genres.isEmpty() -> Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(state.error!!, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = vm::load) { Text("Réessayer") }
                    }
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    mode == 0 -> NameList(state.artists.map { it.name }, onOpenArtist)
                    else -> NameList(state.genres.map { it.name }, onOpenGenre)
                }
            }
        }
    }
}

/** Liste alphabétique avec un en-tête par initiale. */
@Composable
private fun NameList(names: List<String>, onClick: (String) -> Unit) {
    if (names.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Rien à afficher", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val rows = remember(names) { buildRows(names) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is BrowseRow.Header -> Text(
                    row.letter,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                is BrowseRow.Item -> Row(
                    Modifier.fillMaxWidth().clickable { onClick(row.name) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                row.name.firstOrNull()?.uppercase().orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private sealed interface BrowseRow {
    val key: String

    data class Header(val letter: String) : BrowseRow {
        override val key get() = "h/$letter"
    }

    data class Item(val name: String, val index: Int) : BrowseRow {
        override val key get() = "i/$index/$name"
    }
}

private fun buildRows(names: List<String>): List<BrowseRow> {
    val rows = mutableListOf<BrowseRow>()
    var last = ""
    names.forEachIndexed { index, name ->
        val first = name.firstOrNull()?.uppercaseChar()
        val letter = if (first != null && first.isLetter()) first.toString() else "#"
        if (letter != last) {
            rows += BrowseRow.Header(letter)
            last = letter
        }
        rows += BrowseRow.Item(name, index)
    }
    return rows
}
