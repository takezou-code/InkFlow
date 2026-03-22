import re

with open('app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Replace deleteConfirmIndex
old_delete_conf = '''    // Index of the page pending delete confirmation; null = no dialog
    var deleteConfirmIndex by remember { mutableStateOf<Int?>(null) }'''

new_delete_conf = '''    // Index of the page(s) pending delete confirmation; null = no dialog
    var deleteConfirmIndices by remember { mutableStateOf<List<Int>?>(null) }
    
    // Bulk selection state
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedPages by remember { mutableStateOf(setOf<Int>()) }
    val context = androidx.compose.ui.platform.LocalContext.current'''

content = content.replace(old_delete_conf, new_delete_conf)

# Process AlertDialog for deletion
old_dialog = '''    deleteConfirmIndex?.let { index ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteConfirmIndex = null },
            title = { Text("刪除頁面") },
            text = { Text("確定要刪除第  頁嗎？此操作無法還原。") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        onDeletePage(index)
                        deleteConfirmIndex = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("刪除")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteConfirmIndex = null }) {
                    Text("取消")
                }
            }
        )
    }'''

new_dialog = '''    deleteConfirmIndices?.let { indices ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteConfirmIndices = null },
            title = { Text("刪除頁面") },
            text = { 
                if (indices.size == 1) Text("確定要刪除第  頁嗎？此操作無法還原。") 
                else Text("確定要刪除這  頁嗎？此操作無法還原。") 
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        onDeletePages(indices)
                        deleteConfirmIndices = null
                        isSelectionMode = false
                        selectedPages = emptySet()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("刪除")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteConfirmIndices = null }) {
                    Text("取消")
                }
            }
        )
    }'''

content = content.replace(old_dialog, new_dialog)

# Now FULLSCREEN structure
old_fullscreen_start = '''        if (sidebarMode == SidebarMode.FULLSCREEN) {
            // Fullscreen Grid
            Column(Modifier.fillMaxSize()) {
                // Header in fullscreen
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("頁面總覽", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { onModeChange(SidebarMode.NORMAL) }) {
                        Icon(Icons.Default.Close, contentDescription = "Close Fullscreen Grid")
                    }
                }
                
                LazyVerticalGrid('''

new_fullscreen_start = '''        if (sidebarMode == SidebarMode.FULLSCREEN) {
            // Fullscreen Grid
            Column(Modifier.fillMaxSize()) {
                // Header in fullscreen
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSelectionMode) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { 
                                isSelectionMode = false 
                                selectedPages = emptySet()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel Selection")
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("已選取  頁", style = MaterialTheme.typography.titleMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.TextButton(
                                onClick = { 
                                    if (selectedPages.size == pageCount) selectedPages = emptySet() 
                                    else selectedPages = (0 until pageCount).toSet() 
                                }
                            ) {
                                Text(if (selectedPages.size == pageCount) "取消全選" else "全選")
                            }
                            Spacer(Modifier.width(16.dp))
                            androidx.compose.material3.FilledTonalButton(
                                onClick = { android.widget.Toast.makeText(context, "批次複製功能開發中", android.widget.Toast.LENGTH_SHORT).show() },
                                enabled = selectedPages.isNotEmpty()
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("複製")
                            }
                            Spacer(Modifier.width(8.dp))
                            androidx.compose.material3.FilledTonalButton(
                                onClick = { android.widget.Toast.makeText(context, "批次匯出功能開發中", android.widget.Toast.LENGTH_SHORT).show() },
                                enabled = selectedPages.isNotEmpty()
                            ) {
                                Icon(Icons.Default.FileUpload, contentDescription = "Export", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("匯出")
                            }
                            Spacer(Modifier.width(8.dp))
                            androidx.compose.material3.Button(
                                onClick = { if (selectedPages.isNotEmpty()) deleteConfirmIndices = selectedPages.toList() },
                                enabled = selectedPages.isNotEmpty(),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("刪除")
                            }
                        }
                    } else {
                        Text("頁面總覽", style = MaterialTheme.typography.titleLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.TextButton(onClick = { isSelectionMode = true }) {
                                Text("選取")
                            }
                            IconButton(onClick = { onModeChange(SidebarMode.NORMAL) }) {
                                Icon(Icons.Default.Close, contentDescription = "Close Fullscreen Grid")
                            }
                        }
                    }
                }
                
                LazyVerticalGrid('''

content = content.replace(old_fullscreen_start, new_fullscreen_start)

# Now modify the grid items in FULLSCREEN
old_grid_box = '''                        Box(modifier = Modifier.combinedClickable(
                            onClick = {
                                onPageSelected(index)
                                onModeChange(SidebarMode.NORMAL)
                            },
                            onLongClick = { if (pageCount > 1 && !isPageOperationInProgress) deleteConfirmIndex = index }
                        )) {
                            PageThumbnail(
                                pageIndex = index,
                                bitmap = thumb,
                                strokes = strokes,
                                imageAnnotations = images,
                                textAnnotations = texts,
                                isSelected = index == currentPageIndex,
                                boxModifier = Modifier.fillMaxWidth().aspectRatio(pdfViewModel.getPageAspectRatio(index))
                            )
                        }'''

new_grid_box = '''                        Box(modifier = Modifier.combinedClickable(
                            onClick = {
                                if (isSelectionMode) {
                                    if (index in selectedPages) selectedPages -= index else selectedPages += index
                                } else {
                                    onPageSelected(index)
                                    onModeChange(SidebarMode.NORMAL)
                                }
                            },
                            onLongClick = { 
                                if (!isSelectionMode && pageCount > 1 && !isPageOperationInProgress) {
                                    isSelectionMode = true
                                    selectedPages += index
                                }
                            }
                        )) {
                            Box {
                                PageThumbnail(
                                    pageIndex = index,
                                    bitmap = thumb,
                                    strokes = strokes,
                                    imageAnnotations = images,
                                    textAnnotations = texts,
                                    isSelected = isSelectionMode && index in selectedPages || (!isSelectionMode && index == currentPageIndex),
                                    boxModifier = Modifier.fillMaxWidth().aspectRatio(pdfViewModel.getPageAspectRatio(index))
                                        .let { if (isSelectionMode) it.padding(8.dp) else it }
                                )
                                if (isSelectionMode) {
                                    androidx.compose.material3.Checkbox(
                                        checked = index in selectedPages,
                                        onCheckedChange = { chk ->
                                            if (chk) selectedPages += index else selectedPages -= index
                                        },
                                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                                    )
                                }
                            }
                        }'''

content = content.replace(old_grid_box, new_grid_box)

# Now update the list item in NORMAL/COLLAPSED (only LongClick needs changing from deleteConfirmIndex to deleteConfirmIndices)
old_list_box = '''                        Box(
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .combinedClickable(
                                    onClick = { onPageSelected(index) },
                                    onLongClick = { if (pageCount > 1 && !isPageOperationInProgress) deleteConfirmIndex = index }
                                ),'''

new_list_box = '''                        Box(
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .combinedClickable(
                                    onClick = { onPageSelected(index) },
                                    onLongClick = { if (pageCount > 1 && !isPageOperationInProgress) deleteConfirmIndices = listOf(index) }
                                ),'''

content = content.replace(old_list_box, new_list_box)

with open('app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt', 'w', encoding='utf-8') as f:
    f.write(content)

print("Fullscreen mod done.")
