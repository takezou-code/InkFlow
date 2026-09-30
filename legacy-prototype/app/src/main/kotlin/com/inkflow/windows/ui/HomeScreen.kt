package com.inkflow.windows.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inkflow.windows.data.Document

@Composable
fun HomeScreen(
    documents: List<Document>,
    categories: List<String>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    onDocumentClick: (Document) -> Unit,
    onAddDocument: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("InkFlow - 文件庫") },
                actions = {
                    IconButton(onClick = onAddDocument) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Add,
                            contentDescription = "新增文件"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Row(modifier = Modifier.padding(paddingValues)) {
            // 側邊分類欄
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                header = {
                    Text(
                        "分類",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            ) {
                NavigationRailItem(
                    selected = selectedCategory == "全部",
                    onClick = { onCategorySelected("全部") },
                    icon = { Icon(androidx.compose.material.icons.Icons.Default.Folder, contentDescription = "全部") },
                    label = { Text("全部") }
                )
                
                categories.forEach { category ->
                    NavigationRailItem(
                        selected = selectedCategory == category,
                        onClick = { onCategorySelected(category) },
                        icon = { Icon(androidx.compose.material.icons.Icons.Default.FolderOpen, contentDescription = category) },
                        label = { Text(category) }
                    )
                }
            }
            
            // 文件列表
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp)
            ) {
                // 搜索框
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索文件...") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    singleLine = true
                )
                
                val filteredDocs = if (selectedCategory == "全部") {
                    documents.filter { it.title.contains(searchQuery, ignoreCase = true) }
                } else {
                    documents.filter { it.category == selectedCategory && it.title.contains(searchQuery, ignoreCase = true) }
                }
                
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredDocs) { doc ->
                        DocumentCard(
                            document = doc,
                            onClick = { onDocumentClick(doc) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DocumentCard(
    document: Document,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${document.pageCount} 頁 • ${formatDate(document.lastModified)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.Description,
                contentDescription = "PDF 圖標",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val format = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
    return format.format(date)
}
