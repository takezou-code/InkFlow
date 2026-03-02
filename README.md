# InkFlow

A tablet-first PDF annotation and note-taking app for Android, built entirely with Jetpack Compose and Material Design 3.

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
| Language | Kotlin | 2.0.21 |
| UI | Jetpack Compose BOM | 2024.09.00 |
| UI | Material 3 | (via BOM) |
| Navigation | Navigation Compose | 2.7.7 |
| Lifecycle / ViewModel | Lifecycle ViewModel Compose | 2.8.0 |
| Database | Room | 2.7.0 |
| Code generation | KSP | 2.0.21-1.0.28 |
| PDF read/write | PdfBox-Android (tom-roush) | 2.0.27.0 |
| PDF rendering | Android `PdfRenderer` | built-in |
| JSON | Gson | 2.10.1 |
| Build plugin | AGP | 9.0.1 |

**SDK targets**

| | Value |
|---|---|
| `minSdk` | 32 (Android 12L) |
| `targetSdk` | 36 |
| `compileSdk` | 36 |

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
