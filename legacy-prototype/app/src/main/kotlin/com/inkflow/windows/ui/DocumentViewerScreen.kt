package com.inkflow.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.inkflow.windows.data.Document

@Composable
fun DocumentViewerScreen(
    document: Document,
    onBack: () -> Unit,
    onAiAction: () -> Unit
) {
    var currentPage by remember { mutableStateOf(0) }
    var showAiPanel by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(document.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAiPanel = !showAiPanel }) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.SmartToy,
                            contentDescription = "AI 助手"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Row(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            // PDF 檢視區域（暫時用佔位符）
            Box(
                modifier = Modifier.weight(1f)
                    .background(Color.LightGray),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text("PDF 預覽區域")
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("頁碼：${currentPage + 1} / ${document.pageCount}")
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (currentPage > 0) currentPage-- }) {
                            Text("上一頁")
                        }
                        Button(onClick = { if (currentPage < document.pageCount - 1) currentPage++ }) {
                            Text("下一頁")
                        }
                    }
                }
            }
            
            // AI 側邊欄
            if (showAiPanel) {
                AiAssistantPanel(
                    onAiAction = onAiAction,
                    onClose = { showAiPanel = false }
                )
            }
        }
    }
}

@Composable
fun AiAssistantPanel(
    onAiAction: () -> Unit,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier.width(300.dp).fillMaxHeight()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("AI 助手", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Close,
                        contentDescription = "關閉"
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // AI 功能按鈕（待開發）
            OutlinedButton(
                onClick = onAiAction,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("智能截圖")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedButton(
                onClick = onAiAction,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("手寫轉文字")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedButton(
                onClick = onAiAction,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("生成摘要")
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            Text(
                "AI 功能開發中...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
