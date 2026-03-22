import re

with open('app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt', 'r', encoding='utf-8') as f:
    content = f.read()

old_button = '''                    androidx.compose.material3.TextButton(
                        onClick = { onAddPage(currentPageIndex) },
                        enabled = !isPageOperationInProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("新增頁面", style = MaterialTheme.typography.labelMedium)
                    }'''

new_button = '''                    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.TextButton(
                            onClick = { expanded = true },
                            enabled = !isPageOperationInProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("新增頁面", style = MaterialTheme.typography.labelMedium)
                        }
                        
                        androidx.compose.material3.DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("空白紙 (Blank)") },
                                onClick = { 
                                    expanded = false
                                    onAddPage(currentPageIndex) 
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("橫線紙 (Lined)") },
                                onClick = { 
                                    expanded = false
                                    onAddPage(currentPageIndex) 
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("網格紙 (Grid)") },
                                onClick = { 
                                    expanded = false
                                    onAddPage(currentPageIndex) 
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("點狀紙 (Dotted)") },
                                onClick = { 
                                    expanded = false
                                    onAddPage(currentPageIndex) 
                                }
                            )
                        }
                    }'''

modified_content = content.replace(old_button, new_button)

imports = '''import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue\n'''

if 'import androidx.compose.runtime.getValue' not in modified_content:
    modified_content = modified_content.replace('import ', imports + 'import ', 1)

with open('app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt', 'w', encoding='utf-8') as f:
    f.write(modified_content)

print('Modification done.')
