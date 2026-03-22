with open('app/src/main/java/com/vic/inkflow/ui/PdfViewModel.kt', 'r', encoding='utf-8') as f:
    text = f.read()

import re
text = re.sub(r'import\s+', '\nimport ', text)
text = text.replace('\x00', '')

with open('app/src/main/java/com/vic/inkflow/ui/PdfViewModel.kt', 'w', encoding='utf-8') as f:
    f.write(text)
