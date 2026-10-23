package com.vic.inkflow.ui

import com.vic.inkflow.DesktopSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModel {

    data class UiState(
        val pairingCode: String = ""
    )

    sealed interface SettingsEvent {
        data class SetPairingCode(val code: String) : SettingsEvent
    }

    private val _state = MutableStateFlow(UiState(pairingCode = DesktopSettings.pairingCode ?: ""))
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.SetPairingCode -> {
                _state.value = _state.value.copy(pairingCode = event.code)
                DesktopSettings.pairingCode = event.code
            }
        }
    }
}
