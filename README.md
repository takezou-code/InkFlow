# InkFlow

A tablet-first PDF reader and handwritten-notebook for Android, built with Jetpack Compose. The point of the app is simple: the AI works where your notes already are. You box something, you ask about it, and the answer ends up back on the page.

---
![IMG_20260408_224011](https://github.com/user-attachments/assets/ac6b68e7-a6fa-49a8-9f0e-575311146733)

---

## AI features

### Box anything, ask about it (Lasso → AI)
Draw a lasso around any region and a bubble offers **AI Parse**: the region — PDF content plus your ink, images, and text — is screenshotted and sent to the assistant.

It works on empty regions too. The bubble anchors to the boxed area itself rather than to selected strokes, so boxing a blank patch of the page still produces a valid capture.

One-tap shortcuts submit automatically (in Traditional Chinese):
- **Explain** — a thorough walkthrough of the content with key points
- **Summarize** — at most five bullet points
- **Translate** — translates pictured text into Traditional Chinese

**Extract to new page** renders the boxed region as an image, inserts a fresh page, and places it ready for further editing.

### Math answers become notes, not screenshots
The built-in Q&A panel watches for math in the conversation (MathML / MathJax / annotation signals). When an answer contains formulas, it takes a different path from plain text:

1. Text is split into blocks; formula regions are pixel-cropped from screenshots (S0/S1/S2 crop pipeline).
2. Blocks carrying TeX source are rendered offline by a bundled **KaTeX engine** (a persistent hidden WebView — no network round-trip, falls back to text on failure).
3. Text and rendered math are laid out together, written into a blank page, and the reader jumps there.

The TeX source is recovered from `data-math` attributes and KaTeX annotations — read out of the page, not guessed by OCR.

### Text import
Prose answers can be imported into notes as cleaned-up paragraphs (`AiTextImport`). Inline `$..$` math containing `\ ^ _` is converted to KaTeX-renderable form; plain dollar amounts are left alone.

## Everything else
Pen, highlighter, eraser, shapes, text, stamps, images; pinch zoom and pan; thumbnail sidebar; insert/delete pages; undo/redo; vector PDF export; `.inkbak` backup and restore. The basics are covered — this README won't enumerate them.

---

## Tech stack

Kotlin 2.4.10 · Compose BOM 2026.08.00 · Room 2.8.4 · PdfBox-Android 2.0.27.0 · AGP 9.3.2 · Gradle 9.7.1 · minSdk 32 / targetSdk 36

---

## Build & run

```bash
git clone https://github.com/takezou-code/InkFlow.git
cd InkFlow
./gradlew assembleDebug
```

Or open the project in Android Studio and run the `app` configuration (a physical tablet on API 32+ is recommended).

---

## Branches

- `beta` — day-to-day development
- `main` — stable (merged only on explicit request)

---

## License

For personal and educational use.
