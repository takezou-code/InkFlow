package com.inkflow.windows

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.inkflow.windows.data.Document
import com.inkflow.windows.network.LocalSyncManager
import com.inkflow.windows.ui.HomeScreen
import com.inkflow.windows.ui.DocumentViewerScreen

enum class AppScreen {
    HOME,
    DOCUMENT_VIEWER
}

fun main() = application {
    val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)
    
    Window(
        onCloseRequest = ::exitApplication,
        title = "InkFlow - Windows",
        state = windowState
    ) {
        MaterialTheme {
            AppContent()
        }
    }
}

@Composable
@Preview
fun AppContent() {
    var currentScreen by remember { mutableStateOf(AppScreen.HOME) }
    var selectedDocument by remember { mutableStateOf<Document?>(null) }
    
    // 模擬數據
    val documents = remember {
        listOf(
            Document(1, "筆記範例 1.pdf", "/path/to/file1.pdf", "工作", pageCount = 10),
            Document(2, "會議記錄.pdf", "/path/to/file2.pdf", "工作", pageCount = 5),
            Document(3, "學習筆記.pdf", "/path/to/file3.pdf", "學習", pageCount = 15)
        )
    }
    
    val categories = remember { listOf("工作", "學習", "個人") }
    var selectedCategory by remember { mutableStateOf("全部") }
    
    // 本地同步管理器
    val syncManager = remember { LocalSyncManager() }
    
    LaunchedEffect(Unit) {
        // 啟動設備發現和廣播
        syncManager.startDiscovery()
        syncManager.startBroadcast("windows-001", "我的電腦")
        syncManager.startServer()
    }
    
    when (currentScreen) {
        AppScreen.HOME -> {
            HomeScreen(
                documents = documents,
                categories = categories,
                selectedCategory = selectedCategory,
                onCategorySelected = { selectedCategory = it },
                onDocumentClick = { doc ->
                    selectedDocument = doc
                    currentScreen = AppScreen.DOCUMENT_VIEWER
                },
                onAddDocument = { /* TODO: 實現新增文件 */ }
            )
        }
        AppScreen.DOCUMENT_VIEWER -> {
            selectedDocument?.let { doc ->
                DocumentViewerScreen(
                    document = doc,
                    onBack = { currentScreen = AppScreen.HOME },
                    onAiAction = { /* TODO: 實現 AI 功能 */ }
                )
            }
        }
    }
}
