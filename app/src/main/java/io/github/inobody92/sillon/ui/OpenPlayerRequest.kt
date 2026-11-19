package io.github.inobody92.sillon.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Demande « ouvre l'écran de lecture » venue de la notification ou du widget ; consommée une fois par l'interface. */
object OpenPlayerRequest {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() { _pending.value = true }
    fun clear() { _pending.value = false }
}
