import re

with open('app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt', 'r', encoding='utf-8') as f:
    text = f.read()

idx1 = text.find('@androidx.compose.runtime.Composable\nfun AiWebPanel')
idx2 = text.find('@Composable\nfun AiWebPanel')
if idx1 != -1 and idx2 != -1:
    idx = min(idx1, idx2)
elif idx1 != -1:
    idx = idx1
else:
    idx = idx2

if idx != -1:
    text = text[:idx]

new_panel = '''
@androidx.compose.runtime.Composable
fun AiWebPanel(
    fileUri: android.net.Uri?,
    onClose: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .androidx.compose.foundation.background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant)
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
                    text = "AI Parser",
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
    f.write(text + new_panel)

