import re

with open('app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt', 'r', encoding='utf-8') as f:
    text = f.read()

idx = text.find('private suspend fun LazyListState.animateScrollToCenter')
end_idx = text.find('}', idx) + 1

cleaned_text = text[:end_idx]

clean_panel = '''

@androidx.compose.runtime.Composable
fun AiWebPanel(
    fileUri: android.net.Uri?,
    onClose: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxSize()
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
    f.write(cleaned_text + clean_panel)

