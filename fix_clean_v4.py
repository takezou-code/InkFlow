with open('app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt', 'r', encoding='utf-8') as f:
    text = f.read()

import re

# We simply replace every '.androidx.compose.foundation.layout.fillMaxSize()' to '.fillMaxSize()'
text = text.replace('.androidx.compose.foundation.layout.fillMaxSize()', '.fillMaxSize()')
text = text.replace('.androidx.compose.ui.Modifier.padding(16.dp)', 'androidx.compose.ui.Modifier.padding(16.dp)')
text = text.replace('.androidx.compose.ui.Modifier.fillMaxWidth()', 'androidx.compose.ui.Modifier.fillMaxWidth()')
text = text.replace('.androidx.compose.ui.Modifier.height(16.dp)', 'androidx.compose.ui.Modifier.height(16.dp)')

with open('app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt', 'w', encoding='utf-8') as f:
    f.write(text)
