# -*- coding: utf-8 -*-
import re

with open('app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt', 'r', encoding='utf-8') as f:
    text = f.read()

idx = text.find('@androidx.compose.runtime.Composable\nfun AiWebPanel')
if idx != -1:
    text = text[:idx]

new_panel = '''
@Composable
fun AiWebPanel(
    fileUri: android.net.Uri?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant)
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxSize()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "AI R",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Close,
                        contentDescription = "Close"
                    )
                }
            }
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (fileUri != null) "w^ϰ:\\n\" else "L",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
            )
        }
    }
}
'''

text += new_panel

with open('app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt', 'w', encoding='utf-8') as f:
    f.write(text)
