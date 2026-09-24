# InkFlow

平板優先的 PDF 閱讀＋手寫筆記 App（Android, Jetpack Compose）。核心賣點是**跟筆記長在一起的 AI**：框選即問、問答直接寫回頁面。

---
![IMG_20260408_224011](https://github.com/user-attachments/assets/ac6b68e7-a6fa-49a8-9f0e-575311146733)

---

## AI 功能

### 框選即問（套索 → AI）
- 套索圈起任何區域，浮動氣泡直接給 **AI 解析**：把該區（含 PDF 底圖＋墨跡＋圖片＋文字）送去問答。
- **空白區也照樣能問**：框裡沒有筆跡時氣泡一樣出現，照樣出圖送 AI（region 錨定，不依賴選中物）。
- 一鍵快捷指令，送圖後自動送出（繁體中文）：**解釋**（詳細解釋＋重點）、**總結**（最多 5 點條列）、**翻譯**（圖中文字翻繁中）。
- **提取成新頁**：把框選區渲染成圖，插一頁新的放好，可再編輯。

### AI 數學筆記管線（問答 → 寫回頁面）
- 內嵌 AI 問答面板（`AiWebPanel`）：對話過程偵測數學含量（`MATHCOUNT`：mathml/mjx/annotation 訊號），含公式的回答走數學管線。
- 文字切塊＋公式像素裁圖（S0/S1/S2 截圖裁切管線）＋ **KaTeX 離線第二引擎**（常駐隱藏 WebView，TeX 源渲染成圖，失敗退回文字）。
- 圖文混合排版後掃描空白頁寫入筆記並自動跳轉：問完的數學直接長在筆記裡，不是貼一張死圖。
- TeX 還原：從 `data-math` / KaTeX annotation 撈回真正的 TeX 源，不是 OCR 猜的。

### AI 文字引入
- 問答文字分段整理後匯入筆記（`AiTextImport`），行內 `$..$` 數學轉 KaTeX 可渲染格式（避開金額誤判）。

## 其他（一覽）
手寫筆＋螢光筆＋橡皮擦＋形狀＋文字＋圖章＋圖片、縮放平移、縮圖側欄、插頁刪頁、undo/redo、PDF 向量匯出、`.inkbak` 備份還原——基本功都有，不贅述。

---

## Tech Stack

Kotlin 2.4.10 · Compose BOM 2026.08.00 · Room 2.8.4 · PdfBox-Android 2.0.27.0 · AGP 9.3.2 · Gradle 9.7.1 · minSdk 32 / targetSdk 36

---

## Build & Run

```bash
git clone https://github.com/takezou-code/InkFlow.git
cd InkFlow
./gradlew assembleDebug
```

Android Studio 開啟後直接跑 `app` 也行（平板實機建議 API 32+）。

---

## 分支

- `beta`：日常開發線
- `main`：穩定線（user 確認才合入）

---

## License

個人與教育用途。
