import re

file_path = 'app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    text = f.read()

# 1. Update button sizes
text = text.replace('val toolButtonSize = 36.dp', 'val toolButtonSize = 32.dp')
text = text.replace('val utilityButtonSize = 38.dp', 'val utilityButtonSize = 34.dp')

# 2. Outer Row - make the whole toolbar slightly shorter
old_outer_row = """        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {"""

new_outer_row = """        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {"""
text = text.replace(old_outer_row, new_outer_row)

# 3. First Surface (Left)
old_left = """            Surface(
                shape = RoundedCornerShape(24.dp),
                color = shellColor,
                border = BorderStroke(1.dp, borderColor)
            ) {
                Row(
                    modifier = Modifier.padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {"""
new_left = """            Surface(
                modifier = Modifier.fillMaxHeight(),
                shape = RoundedCornerShape(24.dp),
                color = shellColor,
                border = BorderStroke(1.dp, borderColor)
            ) {
                Row(
                    modifier = Modifier.fillMaxHeight().padding(start = 6.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {"""
text = text.replace(old_left, new_left)

# 4. Second Surface (Center)
old_center = """            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                color = shellColor,
                border = BorderStroke(1.dp, borderColor)
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {"""
new_center = """            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(24.dp),
                color = shellColor,
                border = BorderStroke(1.dp, borderColor)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {"""
text = text.replace(old_center, new_center)

# 5. Third Surface (Right)
old_right = """            Surface(
                shape = RoundedCornerShape(24.dp),
                color = shellColor,
                border = BorderStroke(1.dp, borderColor)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {"""
new_right = """            Surface(
                modifier = Modifier.fillMaxHeight(),
                shape = RoundedCornerShape(24.dp),
                color = shellColor,
                border = BorderStroke(1.dp, borderColor)
            ) {
                Row(
                    modifier = Modifier.fillMaxHeight().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {"""
text = text.replace(old_right, new_right)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(text)

print('Toolbar resized successfully')
