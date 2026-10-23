package com.vic.inkflow.ui

import com.vic.inkflow.DesktopSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Screen state holder for the settings screen.
 *
 * ## Why this exists
 *
 * `SettingsView` used to receive `pairingCode` and an `onPairingCodeChange`
 * callback from `InkFlowApp`, and the persistence (`DesktopSettings.pairingCode =`)
 * happened in the callback at the call site. That is the classic "hoist
 * everything to the root" pattern: the root composable owns the state, the
 * screen is a pure renderer, and every keystroke round-trips through the root.
 *
 * The settings screen is the smallest of the three migrations (library, reader,
 * AI) because its state is just one string plus the theme, which already lives
 * in `InkThemeState`. Moving the pairing code into a `StateFlow`-backed holder
 * keeps the persistence logic next to the state it persists, the same shape the
 * library and reader now have.
 *
 * Theme (`darkMode`, `backdropTheme`) stays in `InkThemeState` / `DesktopSettings`
 * because it is read by every screen, not just settings: a settings-only
 * ViewModel would have to push changes back up to the global holder anyway.
 */
class SettingsViewModel {

    data class UiState(
        val pairingCode: String = ""
    )

    private val _state = MutableStateFlow(UiState(pairingCode = DesktopSettings.pairingCode ?: ""))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Update the pairing code and persist immediately.
     *
     * Persisting on every keystroke (rather than on blur) is deliberate: there
     * is no "save" affordance on this screen, and losing the code on a crash
     * would mean re-reading it off the tablet screen.
     */
    fun setPairingCode(code: String) {
        _state.value = _state.value.copy(pairingCode = code)
        DesktopSettings.pairingCode = code
    }
}
