import re

with open('old.kt', 'r', encoding='utf-16') as f:
    old_text = f.read()

idx = old_text.find('private suspend fun LazyListState.animateScrollToCenter')
# get until the end of old_text, or basically find the proper end
end_idx = old_text.rfind('}') + 1

# Actually old.kt didn't have AiWebPanel at the bottom? Let's check where old.kt ends.
# Oh, we don't know. Let me just extract animateScrollToCenter cleanly

func_text = '''
private suspend fun androidx.compose.foundation.lazy.LazyListState.animateScrollToCenter(index: Int) {
    scrollToItem(index)
    val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    val delta = (itemInfo.offset + itemInfo.size / 2 - viewportHeight / 2).toFloat()
    animateScrollBy(delta)
}
'''

# Read current 
with open('app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt', 'r', encoding='utf-8') as f:
    text = f.read()

# remove anything from 'private suspend fun LazyListState.animateScrollToCenter' to the end.
idx_curr = text.find('private suspend fun LazyListState.animateScrollToCenter')
if idx_curr == -1:
    idx_curr = text.find('private suspend fun androidx.compose.foundation.lazy.LazyListState.animateScrollToCenter')

if idx_curr != -1:
    text = text[:idx_curr]


clean_panel = func_text + '''

@androidx.compose.runtime.Composable
fun AiWebPanel(
    fileUri: android.net.Uri?,
    onClose: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .androidx.compose.foundation.layout.fillMaxSize()
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier.padding(16.dp).fillMaxSize()
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                modifier = androidx.compose.ui.Modifier.fillMaxWidth()
            ) {
                androidx.compose.material3.Text(
                    text = "AI Parser Preview",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                )
                androidx.compose.material3.IconButton(onClick = onClose) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Close,
                        contentDescription = "Close"
                    )
                }
            }
            androidx.compose.material3.HorizontalDivider()
            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
            androidx.compose.material3.Text(
                text = if (fileUri != null) "File:\\n\" else "No file",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
            )
        }
    }
}
'''

with open('app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt', 'w', encoding='utf-8') as f:
    f.write(text + clean_panel)

