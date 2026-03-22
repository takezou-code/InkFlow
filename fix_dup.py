import sys

with open('app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt', 'r', encoding='utf-8') as f:
    text = f.read()

idx1 = text.find('Text("AI ¸ÑªR")')
print("idx1 =", idx1)
if idx1 == -1: sys.exit(1)

btn_start1 = text.rfind('TextButton(', 0, idx1)
btn_end1 = text.find('}', idx1) + 1

idx2 = text.find('Text("AI ¸ÑªR")', btn_end1)
print("idx2 =", idx2)
if idx2 == -1: sys.exit(1)

btn_start2 = text.rfind('TextButton(', 0, idx2)
btn_end2 = text.find('}', idx2) + 1

if text[btn_end1:btn_start2].strip() == '':
    print("They are adjacent. Removing second.")
    text = text[:btn_start2] + text[btn_end2:]
    with open('app/src/main/java/com/vic/inkflow/ui/InkLayerApp.kt', 'w', encoding='utf-8') as f:
        f.write(text)
    print("Fixed adjacent.")
