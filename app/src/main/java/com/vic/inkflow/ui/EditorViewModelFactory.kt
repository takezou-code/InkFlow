package com.vic.inkflow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.vic.inkflow.data.repository.InkFlowRepositories

class EditorViewModelFactory(
    private val repos: InkFlowRepositories,
    private val documentUri: String,
    private val settingsRepository: EditorSettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EditorViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return EditorViewModel(repos, documentUri, settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}