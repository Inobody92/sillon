package io.github.inobody92.sillon.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.inobody92.sillon.data.Album
import io.github.inobody92.sillon.data.SynoClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AlbumsState(
    val albums: List<Album> = emptyList(),
    val loading: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
)

class AlbumsViewModel(private val client: SynoClient) : ViewModel() {

    private val _state = MutableStateFlow(AlbumsState())
    val state: StateFlow<AlbumsState> = _state.asStateFlow()

    init {
        loadMore()
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.endReached) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val page = client.albums(offset = s.albums.size, limit = PAGE_SIZE)
                _state.update {
                    it.copy(
                        albums = it.albums + page,
                        loading = false,
                        endReached = page.size < PAGE_SIZE,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Erreur de chargement") }
            }
        }
    }

    fun retry() = loadMore()

    private companion object {
        const val PAGE_SIZE = 100
    }
}
