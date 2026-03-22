import re

with open('app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt', 'r', encoding='utf-8') as f:
    text = f.read()

target = r'if \(!isFullscreen\) \{\s*Box\(Modifier\.weight\(1f\)\.fillMaxHeight\(\)\) \{'

replacement = '''if (!isFullscreen) {
                // Left Panel: AI Parser View
                androidx.compose.animation.AnimatedVisibility(
                    visible = showAiPanel,
                    modifier = Modifier.weight(0.4f).fillMaxHeight()
                ) {
                    AiWebPanel(
                        fileUri = aiFileUri,
                        onClose = {
                            showAiPanel = false
                            aiFileUri = null
                        }
                    )
                }
                if (showAiPanel) {
                    androidx.compose.material3.VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
                }

                // Main Workspace
                val workspaceWeight = if (showAiPanel) 0.6f else 1f
                Box(Modifier.weight(workspaceWeight).fillMaxHeight()) {'''

new_text = re.sub(target, replacement, text, count=1)

with open('app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt', 'w', encoding='utf-8') as f:
    f.write(new_text)

print("Added AiWebPanel.")
