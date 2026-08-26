# InkFlow

A tablet-first PDF annotation and note-taking app for Android, built entirely with Jetpack Compose and Material Design 3.

---
![IMG_20260408_224011](https://github.com/user-attachments/assets/ac6b68e7-a6fa-49a8-9f0e-575311146733)

---

## Features

### Document Management
- Open existing PDFs or create blank A4 notes
- File cabinet home screen with `NavigationRail` + `LazyVerticalGrid` card layout
- Rename, delete, and reopen documents
- Remembers last-viewed page and scroll position per document

### Drawing Tools

| Tool | Description |
|---|---|
| **Pen** | Freehand ink with quadratic Bézier smoothing |
| **Highlighter** | Semi-transparent overlay (`BlendMode.Multiply`, 40% alpha) |
| **Eraser** | Two-phase AABB + point-to-segment detection; deletes whole strokes |
| **Lasso** | Ray-casting (Even-Odd Rule) selection and move |
| **Shape** | Rectangle, circle, line, and arrow |
| **Text** | Free-placement text annotations with custom font size and color |
| **Stamp** | Oversized emoji stamps |
| **Image** | Photo annotations picked from the gallery |

### Canvas & Rendering
- **Dual-track rendering**: `PdfRenderer` (static PDF layer, `Dispatchers.IO`) + Compose `Canvas` (in-flight strokes)
- History strokes composited into a single Bitmap via `drawWithCache` — no per-recomposition full redraw
- Pinch-to-zoom + two-finger pan via `graphicsLayer`
- All coordinates stored in device-independent **model space** (595 × 842 PDF points), decoupled from screen pixels
- `historical` pointer events consumed for full-fidelity stylus/touch input

### Page Management
- Thumbnail sidebar with smooth `AnimatedVisibility` (tween 300 ms) and A4-aspect previews
- Current page highlighted with a brand-colour border
- Insert blank pages at arbitrary positions or delete pages

### Undo / Redo
- Full Command Pattern with `undoStack` / `redoStack` (`ArrayDeque<DrawCommand>`)
- Commands: `AddStroke`, `RemoveStrokes`, `AddTextAnnotation`, `RemoveTextAnnotation`, `AddImageAnnotation`, `RemoveImageAnnotation`, `MoveStrokes`

### PDF Export
- Exports **vector strokes** as quadratic Bézier curves in PDF content streams (via PdfBox-Android) — not rasterised
- Correct model → PDF coordinate mapping with Y-axis flip (PDF origin = bottom-left)
- Shapes, text, and image annotations all embedded
- Saved to `Downloads/` via `MediaStore`

---

## Tech Stack

| Category | Library | Version |
|---|---|---|
| Language | Kotlin | 2.4.10 |
| UI | Jetpack Compose BOM | 2026.08.00 |
| UI | Material 3 | (via BOM) |
| Navigation | Navigation Compose | 2.9.8 |
| Lifecycle / ViewModel | Lifecycle ViewModel Compose | 2.11.0 |
| Database | Room | 2.8.4 |
| Code generation | KSP | 2.3.11 |
| PDF read/write | PdfBox-Android (tom-roush) | 2.0.27.0 |
| PDF rendering | Android `PdfRenderer` | built-in |
| JSON | Gson | 2.14.0 |
| Build plugin | AGP | 9.3.2 |
| Build tool | Gradle | 9.7.1 |

**SDK targets**

| | Value |
|---|---|
| `minSdk` | 32 (Android 12L) |
| `targetSdk` | 36 |
| `compileSdk` | 37.1 |

---

## Backup & Data Safety

### `.inkbak` backup container (ZIP)

| Entry | Content |
|---|---|
| `manifest.json` | `formatVersion`, timestamps, app version, DB schema version, original working dir, document list |
| `database.db` | Consistent SQLite snapshot (`VACUUM INTO`) of all documents / strokes / annotations / preferences |
| `pdfs/<uuid>.pdf` | Each document's working PDF |
| `images/<uuid>` | Image annotation assets (deduplicated) |

- **Export**: 設定 → 備份與還原 → 匯出全部備份（SAF 選目的地）
- **Restore**: 設定 → 還原備份 → 挑選 `.zip/.inkbak` → 驗證 → 重啟 App 後自動套用
  （資料庫與 PDF 於 Room 開啟前原子交換；跨裝置還原時自動以 SQL `REPLACE` 重寫絕對路徑 URI）

### Crash safety

- Page operations (insert / delete / move / import) follow a journal protocol:
  `backup file → journal(prepared) → atomic PDF mutation → journal(file_done) → DB transaction → clear`
- Process death between the PDF mutation and the DB update is auto-repaired on next launch
  by `PageOpJournal.reconcilePending()`; a pre-op copy is kept for rollback.
- All PDF writes use temp-file + atomic rename; export failures clean up pending MediaStore entries.

---

## Architecture

InkFlow follows a **simplified Clean Architecture + MVVM** pattern:

```
UI (Composables)
    └── ViewModel  (StateFlow, viewModelScope)
            └── Repository / DAO  (Room, Dispatchers.IO)
```

- All public ViewModel state is exposed as `StateFlow` — `LiveData` is not used
- DB and PDF I/O run on `Dispatchers.IO`; eraser/lasso geometry runs on `Dispatchers.Default`
- Multi-entity inserts are wrapped in `@Transaction`; foreign keys use `onDelete = CASCADE`
- PDF page Bitmaps are cached in an `LruCache` capped at 1/8 of max heap
- External PDF URIs are accessed via `ContentResolver.takePersistableUriPermission`

---

## Getting Started

### Prerequisites
- Android Studio Meerkat or newer
- JDK 11+
- A device or emulator running **Android 12L (API 32)** or higher (tablet/large-screen recommended)

### Build & Run
```bash
git clone https://github.com/e24141042-glitch/InkFlow.git
cd InkFlow
./gradlew assembleDebug
```

Or open the project in Android Studio and run the `app` configuration directly.

---

## License

This project is for personal and educational use.
