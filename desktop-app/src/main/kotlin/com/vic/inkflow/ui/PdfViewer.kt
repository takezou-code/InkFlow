package com.vic.inkflow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.ShapeLine
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.awaitFirstDown
import com.vic.inkflow.data.StrokeEntity
import com.vic.inkflow.data.PointEntity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.abs
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.StrokeWithPoints
import com.vic.inkflow.data.TextAnnotationEntity
import com.vic.inkflow.data.TextMetrics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.focus.focusRequester

import com.vic.inkflow.util.PageBox
import com.vic.inkflow.util.PdfManager
import com.vic.inkflow.util.PdfResult
import com.vic.inkflow.util.RenderedPage
import com.vic.inkflow.util.ShapeGeometry
import com.vic.inkflow.util.UndoStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size

private val logger = KotlinLogging.logger {}

/**
 * Read-only PDF viewer: rendered page + the tablet's strokes on top.
 *
 * ## The coordinate problem this solves
 *
 * Strokes arrive in the tablet's **model space** — PDF points, i.e. roughly
 * 0..595 x 0..842 for A4 portrait. The page is drawn at whatever size fills the
 * viewport, so drawing `point.x, point.y` straight onto the canvas puts every stroke at
 * the wrong scale, jammed into one corner.
 *
 * The fix is a single explicit transform, and it is the *only* place that converts model
 * space to screen space. `zoom = 1` means "fit the page to the window":
 *
 * ```
 * fitScale = min(viewportWidth / pageWidthPt, viewportHeight / pageHeightPt)
 * scale    = fitScale * zoom                      // screen pixels per PDF point
 * pageW    = pageWidthPt  * scale                 // drawn size
 * originX  = (viewportWidth  - pageW) / 2 + panX  // drawn position, centred then panned
 * bitmap:  drawImage(dstSize = (pageW, pageH)) inside translate(originX, originY)
 * ink:     (modelX - box.originX) * scale         inside the SAME translate block
 * ```
 *
 * The image and the ink share one `translate` and one `scale`, so they cannot drift
 * apart — there is no second set of arithmetic to get wrong. The page box used for both
 * is the one carried by the raster itself, so "the page was re-read between rendering and
 * drawing" is not a failure mode. (The old code computed absolute screen positions and
 * then subtracted the origin again inside the translate block; same result, one more
 * place to be wrong.)
 *
 * Rendering goes through `ImageBitmap` rather than a Swing panel: the PDF page and the ink
 * must live in the same Compose coordinate space, and a Swing interop layer cannot
 * participate in Compose's transform/pan/zoom.
 *
 * ## Page index ownership
 *
 * `PdfViewer` owns the current page. The incoming [pageIndex] seeds it and is adopted
 * again only when it changes to something this composable did not request, so a parent
 * that merely holds the value in state cannot fight the user's navigation, while a parent
 * that wants to drive navigation still can. The parent learns the page *count* through
 * [onPageCountChange] (0 when the document could not be opened) and can observe every
 * navigation through [onPageChange].
 *
 * ## Gestures
 *
 * - drag / pinch — pan and zoom, about the pinch centroid. Rotation is locked out so a
 *   rotated page can never disagree with its ink.
 * - Ctrl (or Cmd) + wheel — zoom about the pointer, one step per notch: the PDF reader
 *   convention, and the only wheel binding that touches zoom.
 * - wheel — pan vertically, Shift + wheel — pan horizontally, about 72 px per notch. Plain
 *   wheel is deliberately pan rather than page turn: scrolling is how you move around
 * * inside a page, and jumping whole pages on every notch makes a long document
 *   unusable. Page turn therefore lives on the keys and the nav bar, where it is explicit.
 * - PageUp / PageDown, arrow keys, Home / End — page turn (the canvas takes focus on open)
 *
 * ## Rendering resolution
 *
 * The raster DPI is derived from the current zoom rather than fixed, so zooming in redraws
 * the page at the resolution the screen actually needs (see [PdfManager.renderDpiFor]).
 * It is quantised and debounced, and serialised behind a mutex, because a pinch that
 * re-rasterises per frame would queue more CPU work than it can retire.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PdfViewer(
    documentUri: String,
    pageIndex: Int,
    databaseManager: DatabaseManager,
    modifier: Modifier = Modifier,
    onPageCountChange: (Int) -> Unit = {},
    onPageChange: (Int) -> Unit = {},
    /**
     * v5 proposal outbox. When non-null, every local edit is also filed as an
     * id-level op against the last synced version, so the next sync can push it to
     * the tablet for arbitration. Null means local-only (the old pull-only
     * behaviour): edits show up immediately but are never pushed and the next pull
     * can discard them.
     */
    proposalQueue: com.vic.inkflow.sync.ProposalQueue? = null,
    /**
     * Turns on the ink toolbar and lets the user draw on the page.
     *
     * Off by default. With a [proposalQueue] (v5) local edits are filed as
     * proposals and pushed for tablet arbitration on the next sync; without one
     * they live in the desktop database only and the next pull can discard them.
     * Drawing is therefore opt-in per call site rather than something the reader
     * discovers by accident.
     */
    editable: Boolean = false,
    /** Fired after a stroke is committed, so the caller can refresh counts. */
    onInkChanged: () -> Unit = {},
    /**
     * Bumped by the caller after every background sync pass. The ink and text
     * loaders below are keyed on it, so synced content appears without reopening
     * the document. Without this the canvas shows whatever was in the database
     * when the page was opened — a sync landing mid-reading is silently invisible.
     */
    refreshToken: Int = 0,
    /**
     * File name shown as a compact chip at the head of the toolbar. The top app
     * bar that used to carry it is gone (vertical space belongs to the page),
     * so the title rides here where the document actually is.
     */
    documentTitle: String = "",
    /** Whether the AI panel shell is open; drives the toolbar AI button state. */
    isAiPanelOpen: Boolean = false,
    /** Toggles the AI panel shell. Reserved UI only — no backend call. */
    onToggleAiPanel: () -> Unit = {}
) {
    // ── Which page ───────────────────────────────────────────────────────────
    // Seeded from the incoming pageIndex, and reset whenever the document changes.
    var requestedPage by remember(documentUri) { mutableIntStateOf(pageIndex.coerceAtLeast(0)) }
    LaunchedEffect(pageIndex) {
        if (pageIndex != requestedPage) requestedPage = pageIndex.coerceAtLeast(0)
    }
    var pageCount by remember(documentUri) { mutableIntStateOf(0) }

    // ── Page raster ─────────────────────────────────────────────────────────
    // Keyed on the page, so a page turn clears them: page 2's ink drawn over page 1's
    // bitmap for a frame would be worse than a brief spinner.
    var pageBox by remember(documentUri, requestedPage) { mutableStateOf<PageBox?>(null) }
    var rendered by remember(documentUri, requestedPage) { mutableStateOf<RenderedPage?>(null) }
    var renderedDpi by remember(documentUri, requestedPage) { mutableFloatStateOf(0f) }
    var loadError by remember(documentUri, requestedPage) { mutableStateOf<String?>(null) }

    // ── Ink ─────────────────────────────────────────────────────────────────
    var strokes by remember(documentUri, requestedPage, refreshToken) {
        mutableStateOf<List<StrokeWithPoints>>(emptyList())
    }

    // ── Text annotations ────────────────────────────────────────────────────
    // Separate list rather than folded into `strokes`: a note has no points and
    // no width, and forcing it into the envelope renderer would invent geometry
    // for it. Its own list also keeps the ink fast path from re-measuring text
    // metrics on every pointer move.
    var texts by remember(documentUri, requestedPage, refreshToken) {
        mutableStateOf<List<TextAnnotationEntity>>(emptyList())
    }

    /**
     * The note the user is currently typing, before it exists in the database.
     *
     * Held as state so the caret and the growing box render at keystroke speed,
     * with the same split as [liveStroke]: one database write on commit.
     */
    var textDraft by remember { mutableStateOf<String?>(null) }

    /** Anchor of the in-progress note, in model units. Null when not typing. */
    var textDraftAnchor by remember { mutableStateOf<Offset?>(null) }

    // ── Drawing ─────────────────────────────────────────────────────────────
    // Kept as plain state next to `strokes` rather than in a ViewModel: the ink
    // tool has no lifetime beyond this composable, and the durable copy always
    // goes through the database.
    var tool by remember { mutableStateOf(InkTool.Pen) }
    var inkColour by remember { mutableIntStateOf(0xFF121826.toInt()) }
    // Export runs off the UI thread: a long document must not freeze the reader while
    // PDFBox stamps every page, and the status stays visible under the toolbar.
    val exportScope = rememberCoroutineScope()
    var exporting by remember(documentUri) { mutableStateOf(false) }
    var exportMessage by remember(documentUri) { mutableStateOf<String?>(null) }
    // The stroke being drawn right now. Held separately from `strokes` so the
    // canvas can show an uncommitted stroke at pointer speed while the database
    // write happens once, on release.
    var liveStroke by remember { mutableStateOf<List<Offset>?>(null) }

    // Per-point width of the stroke in progress, in **pixels**. A mouse reports no
    // pressure at all, so the width is derived from speed instead: a pen held
    // still is a slow pen and lays down more ink. Without this, everything drawn
    // with a mouse is a flat hairline and cannot tell it apart from the tablet's
    // pressure-varying ink.
    var liveStrokeWidths by remember { mutableStateOf<List<Float>>(emptyList()) }

    // ── Shapes ──────────────────────────────────────────────────────────────
    // The subtype is picked in the toolbar before the drag, the way the tablet does
    // it, so one gesture handler covers all four shapes instead of four handlers
    // competing over the same pointer stream.
    var shapeSubType by remember { mutableStateOf(com.vic.inkflow.util.ShapeType.RECT) }

    /**
     * The shape being dragged right now, in model units.
     *
     * Separate from `liveStroke` because a shape previews as a primitive outline,
     * not through the envelope renderer. Below a couple of pixels a shape has no
     * visible form, so the preview is discarded rather than committed.
     */
    var liveShape by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }

    // A pointerInput coroutine captures the values in scope when it *starts*, and
    // it only restarts when its keys change. `box` and `scale` are null/zero on
    // the first composition and only become valid once the page finishes
    // rendering — with keys of (document, page, editable) the handler therefore
    // kept calling a converter closed over a null box, so every press was
    // rejected as "outside the page" and the tool looked completely dead.
    //
    // These forward to the real functions below, which are declared further down
    // and read `box`/`scale` at call time; rememberUpdatedState re-points them at
    // the current composition so a gesture always uses the transform of the
    // frame it is actually in.
    // ── View transform ──────────────────────────────────────────────────────
    // Explicit states, not `by` delegates: the render pipeline reads these from a
    // coroutine, where only a stable reference to the state can be observed.
    val zoom = remember(documentUri) { mutableFloatStateOf(1f) }
    val pan = remember(documentUri) { mutableStateOf(Offset.Zero) }
    val viewport = remember(documentUri) { mutableStateOf(Size.Zero) }

    val pageReady = pageBox != null

    /**
     * Zoom -> render resolution. The policy (what DPI a given zoom deserves) lives in
     * [PdfManager]; this only feeds it the current geometry.
     *
     * Reading `zoom`/`viewport` here, from inside a `snapshotFlow`, is what makes the
     * render pipeline react to pinch and to window resizes. It uses the metadata box
     * rather than the rendered one, because it has to decide the DPI *before* the raster
     * exists — that is the whole reason page geometry is read separately.
     */
    fun desiredDpi(): Float = PdfManager.renderDpiFor(
        pageWidthPt = pageBox?.widthPt ?: 0f,
        pageHeightPt = pageBox?.heightPt ?: 0f,
        viewportWidthPx = viewport.value.width,
        viewportHeightPx = viewport.value.height,
        zoom = zoom.floatValue
    )

    // ── Metadata: page count and page geometry, without rasterising ─────────
    // Cheap, and it must come first: the DPI to render at depends on the page size and
    // the viewport, and on an out-of-range page it is the only thing that can report the
    // real count, which is what lets a stale index be pulled back into range instead of
    // wedging the viewer on an error screen.
    LaunchedEffect(documentUri, requestedPage) {
        when (val info = withContext(Dispatchers.IO) {
            PdfManager.readPageInfo(documentUri, requestedPage)
        }) {
            is PdfResult.Ok -> {
                pageBox = info.value.box
                loadError = null
                if (pageCount != info.value.pageCount) {
                    pageCount = info.value.pageCount
                    onPageCountChange(info.value.pageCount)
                }
                info.value.clampedFrom?.let { stale ->
                    val target = stale.coerceIn(0, info.value.pageCount - 1)
                    logger.info { "Page ${stale + 1} does not exist; showing page ${target + 1} instead" }
                    requestedPage = target
                    onPageChange(target)
                }
            }
            is PdfResult.Err -> {
                pageBox = null
                loadError = info.message
                // Unconditional: the parent's count belongs to the *previous* document, and
                // a guarded "only if it changed" call would leave it there — the viewer would
                // look like a 40 page document that cannot be turned.
                pageCount = 0
                onPageCountChange(0)
            }
        }
    }

    // ── Raster ──────────────────────────────────────────────────────────────
    // PDFBox is blocking and CPU-heavy, so every render runs on Dispatchers.IO, and the
    // effect is keyed on the page only — a pinch must not restart it.
    //
    // The mutex is what makes the debounce a real throttle. `collectLatest` cancels the
    // *suspended* previous iteration, but PDFBox is a blocking call, so a cancelled render
    // keeps burning a core until it returns; without serialising, one slow pinch at high
    // DPI would queue up to a dozen 8 MP renders whose results are all discarded. Locking
    // drops the queued ones on the floor while they are still waiting, and the loser
    // re-renders at the final zoom once the fingers stop.
    val renderMutex = remember(documentUri) { Mutex() }
    LaunchedEffect(documentUri, requestedPage, pageReady) {
        if (!pageReady) return@LaunchedEffect
        var renderedOnce = false
        snapshotFlow { PdfManager.dpiBucket(desiredDpi()) }
            .collectLatest { bucket ->
                if (renderedOnce) {
                    // collectLatest cancels the pending delay, so this is a debounce: a
                    // continuous pinch emits nothing until the fingers stop moving.
                    delay(RENDER_SETTLE_MS)
                    if (PdfManager.dpiBucket(desiredDpi()) != bucket) return@collectLatest
                }
                renderedOnce = true
                val dpi = PdfManager.dpiForBucket(bucket)
                val target = pageBox?.let { PdfManager.effectiveRenderDpi(dpi, it) } ?: dpi
                if (!PdfManager.shouldRerender(renderedDpi, target)) return@collectLatest
                val page = requestedPage
                val result = renderMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    withContext(Dispatchers.IO) {
                        PdfManager.renderPage(documentUri, page, dpi)
                    }
                }
                when (result) {
                    is PdfResult.Ok -> {
                        rendered = result.value
                        renderedDpi = result.value.dpi
                        loadError = null
                    }
                    is PdfResult.Err -> {
                        loadError = result.message
                        logger.warn {
                            "Render failed for $documentUri page ${page + 1}: ${result.message}"
                        }
                    }
                }
            }
    }

    // ── Ink ─────────────────────────────────────────────────────────────────
    // Keyed on refreshToken as well as the page: a background sync writes rows
    // straight into the database without touching this composable's state, so
    // without the token the canvas would keep showing whatever it loaded at open.
    LaunchedEffect(documentUri, requestedPage, refreshToken) {
        strokes = runCatching {
            withContext(Dispatchers.IO) {
                databaseManager.getStrokesForPage(documentUri, requestedPage)
            }
        }
            .onFailure { logger.error(it) { "Failed to load strokes for page ${requestedPage + 1}" } }
            .getOrDefault(emptyList())
    }

    // ── Text ────────────────────────────────────────────────────────────────
    LaunchedEffect(documentUri, requestedPage, refreshToken) {
        texts = runCatching {
            withContext(Dispatchers.IO) {
                databaseManager.getTextAnnotationsForPage(documentUri, requestedPage)
            }
        }
            .onFailure { logger.error(it) { "Failed to load text for page ${requestedPage + 1}" } }
            .getOrDefault(emptyList())
    }

    // ── model -> screen transform ───────────────────────────────────────────
    // Derived from the raster itself, never from a separately-read page box: the ink is
    // positioned against the exact geometry of the bitmap underneath it, so "the crop box
    // was re-read between the render and the draw" cannot drift the two apart.
    val frame = rendered
    val box = frame?.box
    val viewportW = viewport.value.width
    val viewportH = viewport.value.height
    // fitScale is screen pixels per PDF point at zoom = 1 ("fit to window").
    val fitScale = if (box != null && box.widthPt > 0f && box.heightPt > 0f && viewportW > 0f && viewportH > 0f) {
        minOf(viewportW / box.widthPt, viewportH / box.heightPt)
    } else 1f
    val scale = fitScale * zoom.floatValue
    val pageWidthPx = (box?.widthPt ?: 0f) * scale
    val pageHeightPx = (box?.heightPt ?: 0f) * scale
    // Centre the page in the viewport, then apply the pan.
    val originX = (viewportW - pageWidthPx) / 2f + pan.value.x
    val originY = (viewportH - pageHeightPx) / 2f + pan.value.y

    /**
     * Stop the page being flung off-screen.
     *
     * Because the page is always centred, the pan range that still keeps it touching all
     * four viewport edges is exactly `|page - viewport| / 2`. When the page fits, that
     * collapses to zero — a fit-to-window view should not pan at all, and letting it
     * drift is how a reader ends up showing an empty grey rectangle with no way back.
*/
    // A pointerInput coroutine captures the values in scope when it *starts*, and
    // it only restarts when its keys change. ox and scale are null/zero on
    // the first composition and only become valid once the page finishes
    // rendering — with keys of (document, page, editable) the handler therefore
    // kept calling a converter closed over a null box, so every press was
    // rejected as "outside the page" and the tool looked completely dead.
    //
    // These forward to the real functions below, which are declared further down
    // and read ox/scale at call time; rememberUpdatedState re-points them at
    // the current composition so a gesture always uses the transform of the
    // frame it is actually in.
    val toModel by rememberUpdatedState<(Offset) -> Offset?> { p ->
        // ox is null until the page raster arrives; until then there is no page
        // to draw on and every press is correctly ignored.
        val b = box
        if (b == null) null else screenToModel(p, b, scale, originX, originY)
    }

    fun clampPan(next: Offset, pageW: Float = pageWidthPx, pageH: Float = pageHeightPx): Offset {
        val halfW = ((pageW - viewportW) / 2f).coerceAtLeast(0f)
        val halfH = ((pageH - viewportH) / 2f).coerceAtLeast(0f)
        return Offset(next.x.coerceIn(-halfW, halfW), next.y.coerceIn(-halfH, halfH))
    }

    /**
     * Zoom by [factor], keeping the model point under [focal] (viewport coordinates) there.
     *
     * `screen = (viewport - page) / 2 + pan + model * scale`, so a point at screen
     * position [focal] has model position `(focal - origin) / scale`. Measuring against
     * the *current* origin — pan included — is what makes a zoom on an already-panned page
     * stay on the spot under the pointer; solving for the pan that puts that same model
     * point back under [focal] is the whole of the anchor maths.
     */
    fun zoomBy(factor: Float, focal: Offset) {
        if (!factor.isFinite() || factor <= 0f) return
        val target = (zoom.floatValue * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val oldScale = fitScale * zoom.floatValue
        val newScale = fitScale * target
        val w = box?.widthPt ?: 0f
        val h = box?.heightPt ?: 0f
        // The model point under the focal point, measured from the page box origin.
        val anchorX = if (oldScale > 0f) (focal.x - originX) / oldScale else 0f
        val anchorY = if (oldScale > 0f) (focal.y - originY) / oldScale else 0f
        zoom.floatValue = target
        if (w <= 0f || h <= 0f) return
        val newW = w * newScale
        val newH = h * newScale
        pan.value = clampPan(
            Offset(
                focal.x - (viewportW - newW) / 2f - anchorX * newScale,
                focal.y - (viewportH - newH) / 2f - anchorY * newScale
            ),
            newW,
            newH
        )
    }

    val lastPage = max(pageCount - 1, 0)
    fun goToPage(page: Int) {
        if (page !in 0..lastPage || page == requestedPage) return
        requestedPage = page
        onPageChange(page)
    }

    /**
     * Persists a finished stroke and puts it on screen.
     *
     * `docY` is deliberately left null. It is the tablet's continuous-canvas
     * coordinate (`pageIndex * stride + boundsTop`), and the tablet backfills it
     * lazily using the live model height when the document is opened. Computing a
     * guess here would need a stride this side does not have, and a wrong stride
     * puts the stroke somewhere else entirely on the tablet — a wrong answer that
     * is worse than no answer.
     *
     * The database write is the commit point: on success the stroke goes into
     * [strokes] so it appears without waiting for a reload, and on failure it is
     * dropped rather than left on screen as ink that is not saved anywhere.
     */
    // History is per document and per viewer instance. Switching documents must
    // discard it: an undo entry referencing strokes from another document would
    // resurrect content that is not there.
    val undoStack = remember(documentUri) { UndoStack<InkEdit>() }

    /**
     * Snapshot of a drag in progress, folded into one history entry on release.
     *
     * A dedicated class rather than a nested Tuple because the shape is now
     * (strokes, notes, totalDx, totalDy) — no Quadruple exists, and unpacking four
     * positional fields of a Tuple leaves the reader guessing which float is which axis.
     */
    data class DragState(
        val strokes: List<StrokeWithPoints>,
        val texts: List<TextAnnotationEntity>,
        val dx: Float,
        val dy: Float
    )

    // ── Selection ────────────────────────────────────────────────────────────
    // Ids rather than the objects: the selection has to survive the list being
    // rebuilt after every edit, and identity is what the ink is keyed on anyway.
    var selectedIds by remember(documentUri) { mutableStateOf<Set<String>>(emptySet()) }

    // Note ids are a disjoint namespace from stroke ids, so a note and a stroke
    // can never collide in one set — which is what makes a mixed selection
    // (drag across both, delete together, undo once) fall out for free.
    var selectedTextIds by remember(documentUri) { mutableStateOf<Set<String>>(emptySet()) }

    /** Rubber-band rectangle during a selection drag, in model units. */
    var selectionRect by remember { mutableStateOf<Rect?>(null) }

    /**
     * The in-progress drag: the pre-drag snapshots plus the distance travelled so far.
     *
     * A drag writes the database on every frame it moves, so without this the history
     * would gain one entry per frame and a single drag would take dozens of undos to
     * reverse. [DragState] is a dedicated class rather than a nested Tuple because the
     * shape is now (strokes, notes, totalDx, totalDy) — no Quadruple exists, and
     * unpacking four positional fields of a Tuple leaves the reader guessing which
     * float is which axis.
     */
    var lastMove by remember {
        mutableStateOf<DragState?>(null)
    }

    /**
     * Strokes whose bounds intersect [r].
     *
     * Bounds-based, not envelope-exact. A rectangle selection that only caught fully
     * enclosed strokes would skip a long stroke the user plainly dragged across, which
     * reads as a broken tool rather than a strict one.
     */
    fun strokesIntersecting(r: Rect): List<StrokeWithPoints> = strokes.filter { swp ->
        val s = swp.stroke
        r.left <= maxOf(s.boundsLeft, s.boundsRight) &&
            r.right >= minOf(s.boundsLeft, s.boundsRight) &&
            r.top <= maxOf(s.boundsTop, s.boundsBottom) &&
            r.bottom >= minOf(s.boundsTop, s.boundsBottom)
    }

    /**
     * Notes whose box intersects [r], in model units.
     *
     * Uses [TextMetrics.bounds] so the hit box is exactly what gets drawn. A
     * separate, slightly different box here is the reason a note can be plainly
     * visible and still refuse to be selected.
     */
    fun textsIntersecting(r: Rect): List<TextAnnotationEntity> = texts.filter { t ->
        val tb = TextMetrics.bounds(t.modelX, t.modelY, t.text, t.fontSize) ?: return@filter false
        r.left <= tb[2] && r.right >= tb[0] && r.top <= tb[3] && r.bottom >= tb[1]
    }

    fun selectByRect(r: Rect, additive: Boolean) {
        val hits = strokesIntersecting(r).map { it.stroke.id }.toSet()
        val textHits = textsIntersecting(r).map { it.id }.toSet()
        selectedIds = if (additive) selectedIds + hits else hits
        selectedTextIds = if (additive) selectedTextIds + textHits else textHits
    }

    /**
     * Bounding box of everything selected, strokes and notes together.
     *
     * Note bounds are unioned with the stroke bounds rather than kept separate so
     * the selection outline wraps a mixed selection in one rectangle — two nested
     * boxes would read as two unrelated selections.
     */
    fun selectionBounds(): Rect? {
        val sel = strokes.filter { it.stroke.id in selectedIds }
        val selText = texts.filter { it.id in selectedTextIds }
        if (sel.isEmpty() && selText.isEmpty()) return null

        val boxes = mutableListOf<Rect>()
        if (sel.isNotEmpty()) {
            boxes += Rect(
                sel.minOf { minOf(it.stroke.boundsLeft, it.stroke.boundsRight) },
                sel.minOf { minOf(it.stroke.boundsTop, it.stroke.boundsBottom) },
                sel.maxOf { maxOf(it.stroke.boundsLeft, it.stroke.boundsRight) },
                sel.maxOf { maxOf(it.stroke.boundsTop, it.stroke.boundsBottom) }
            )
        }
        selText.forEach { t ->
            TextMetrics.bounds(t.modelX, t.modelY, t.text, t.fontSize)?.let { b ->
                boxes += Rect(b[0], b[1], b[2], b[3])
            }
        }
        if (boxes.isEmpty()) return null
        return Rect(
            boxes.minOf { it.left }, boxes.minOf { it.top },
            boxes.maxOf { it.right }, boxes.maxOf { it.bottom }
        )
    }

    /**
     * Total selected, ink and notes together.
     *
     * Every guard reads this instead of `selectedIds.isEmpty()`: a selection can
     * legitimately consist of notes alone, and checking the stroke set would report
     * "nothing selected" while a note is plainly outlined — which reads as the
     * delete key being broken.
     */
    val selectionCount: Int = selectedIds.size + selectedTextIds.size

    /**
     * Rewrites [moving] translated by ([dx], [dy]) and persists each under its existing
     * id.
     *
     * The id has to survive: `saveStroke` is INSERT OR REPLACE, so rewriting the same
     * rows is a move. Minting new ids instead would make the tablet see a deletion plus
     * an unrelated addition, and the stroke would lose its identity across sync.
     */
    fun translateStrokes(
        moving: List<StrokeWithPoints>,
        dx: Float,
        dy: Float
    ): List<StrokeWithPoints> = moving.map { swp ->
        swp.copy(
            stroke = swp.stroke.copy(
                boundsLeft = swp.stroke.boundsLeft + dx,
                boundsTop = swp.stroke.boundsTop + dy,
                boundsRight = swp.stroke.boundsRight + dx,
                boundsBottom = swp.stroke.boundsBottom + dy
            ),
            points = swp.points.map { p -> p.copy(x = p.x + dx, y = p.y + dy) }
        )
    }

    /**
     * Moves the whole selection — strokes and notes together — by an incremental
     * ([dx], [dy]).
     *
     * The delta is incremental on purpose. The drag gesture records where the
     * pointer was the previous frame, so every call is one step, not the total
     * travelled so far. Feeding the running total in here instead would apply the
     * whole displacement again on each frame: the selection accelerates away and
     * `lastMove` ends up holding the state from an arbitrary mid-drag frame rather
     * than the original.
     */
    fun moveSelectionBy(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return

        val moving = strokes.filter { it.stroke.id in selectedIds }
        val movingText = texts.filter { it.id in selectedTextIds }
        if (moving.isEmpty() && movingText.isEmpty()) return

        // Capture the pre-drag state once. Re-snapshotting on every frame would make
        // undo replay from wherever the last successful frame happened to land.
        val origin = lastMove ?: DragState(
            strokes = moving.map { it.copy() },
            texts = movingText.map { it.copy() },
            dx = 0f,
            dy = 0f
        ).also { lastMove = it }

        val after = translateStrokes(moving, dx, dy)
        val afterText = movingText.map { it.copy(modelX = it.modelX + dx, modelY = it.modelY + dy) }
        runCatching {
            after.forEach { databaseManager.saveStroke(it.stroke, it.points) }
            afterText.forEach { databaseManager.saveTextAnnotation(it) }
        }.onSuccess {
            if (moving.isNotEmpty()) {
                strokes = strokes.map { s -> after.firstOrNull { it.stroke.id == s.stroke.id } ?: s }
            }
            if (afterText.isNotEmpty()) {
                texts = texts.map { t -> afterText.firstOrNull { it.id == t.id } ?: t }
            }
            // Accumulate the total so undo can reverse the whole drag in one step.
            lastMove = origin.copy(dx = origin.dx + dx, dy = origin.dy + dy)
            onInkChanged()
        }.onFailure {
            logger.error(it) { "Failed to move ${moving.size} stroke(s) / ${movingText.size} note(s)" }
        }
    }

    /**
     * Files local edits into the v5 proposal queue.
     *
     * Called next to every undo push, on success only — the queue mirrors what is
     * actually in the database, never what was attempted. A null queue means
     * local-only mode and skips silently.
     */
    fun recordOps(ops: List<com.vic.inkflow.sync.ProposalOp>) {
        val q = proposalQueue ?: return
        ops.forEach { op ->
            when (q.record(documentUri, op)) {
                com.vic.inkflow.sync.ProposalQueue.RecordResult.STALE_DROPPED ->
                    logger.info { "Older desktop edits for ${documentUri.substringAfterLast('/')} were overwritten by a pull" }
                else -> Unit
            }
        }
    }

    /**
     * Collapses the drag's incremental writes into one undoable move.
     *
     * A drag that ends where it started moves nothing, so it must not leave an
     * entry behind — otherwise an undo would appear to do nothing and the user
     * would have to press it twice.
     */
    fun commitMove() {
        val m = lastMove
        lastMove = null
        if (m == null) return
        if (m.dx == 0f && m.dy == 0f) return
        undoStack.push(InkEdit.Move(m.strokes, m.texts, m.dx, m.dy))
        // The moved objects' final state is already in the lists; filing it as
        // upserts (one per object, not per frame) is what makes a drag one proposal.
        recordOps(
            strokes.filter { it.stroke.id in selectedIds }.map {
                com.vic.inkflow.sync.ProposalOp(
                    op = com.vic.inkflow.sync.ProposalOp.UPSERT_STROKE, stroke = it
                )
            } + texts.filter { it.id in selectedTextIds }.map {
                com.vic.inkflow.sync.ProposalOp(
                    op = com.vic.inkflow.sync.ProposalOp.UPSERT_TEXT, text = it
                )
            }
        )
    }

    /**
     * Deletes the selection — strokes and notes together — as one undoable step.
     *
     * Ink and text are separate tables, so this is two deletes in one command. Doing
     * them as one history entry is what stops "select everything, delete, undo" from
     * bringing back the notes on the second press.
     */
    fun deleteSelection() {
        val doomed = strokes.filter { it.stroke.id in selectedIds }
        val doomedText = texts.filter { it.id in selectedTextIds }
        if (doomed.isEmpty() && doomedText.isEmpty()) return
        runCatching {
            doomed.forEach { databaseManager.deleteStroke(it.stroke.id) }
            doomedText.forEach { databaseManager.deleteTextAnnotation(it.id) }
        }.onSuccess {
            strokes = strokes - doomed.toSet()
            texts = texts - doomedText.toSet()
            selectedIds = emptySet()
            selectedTextIds = emptySet()
            undoStack.push(InkEdit.Erase(doomed, doomedText))
            recordOps(
                doomed.map {
                    com.vic.inkflow.sync.ProposalOp(
                        op = com.vic.inkflow.sync.ProposalOp.DELETE_STROKE, id = it.stroke.id
                    )
                } + doomedText.map {
                    com.vic.inkflow.sync.ProposalOp(
                        op = com.vic.inkflow.sync.ProposalOp.DELETE_TEXT, id = it.id
                    )
                }
            )
            onInkChanged()
        }.onFailure {
            logger.error(it) { "Failed to delete selection" }
        }
    }

    /**
     * Writes the note being typed, then clears the draft.
     *
     * A blank note is discarded rather than stored: an empty annotation has no
     * bounding box, so it cannot be selected and draws nothing — a row that exists
     * but is unreachable is worse than no row.
     */
    fun commitText() {
        val draft = textDraft
        val anchor = textDraftAnchor
        textDraft = null
        textDraftAnchor = null
        if (draft == null || anchor == null || draft.isBlank()) return

        val note = TextAnnotationEntity(
            documentUri = documentUri,
            pageIndex = requestedPage,
            docY = null,
            text = draft,
            modelX = anchor.x,
            // modelY is the BASELINE. Offsetting by the font size here is what puts
            // the visible glyphs where the user clicked rather than a line lower.
            modelY = anchor.y + TEXT_SIZE_PT,
            fontSize = TEXT_SIZE_PT,
            colorArgb = inkColour
        )
        runCatching { databaseManager.saveTextAnnotation(note) }
            .onSuccess {
                texts = texts + note
                undoStack.push(InkEdit.AddText(note))
                recordOps(
                    listOf(
                        com.vic.inkflow.sync.ProposalOp(
                            op = com.vic.inkflow.sync.ProposalOp.UPSERT_TEXT, text = note
                        )
                    )
                )
                onInkChanged()
            }.onFailure {
                logger.error(it) { "Failed to save note on page ${requestedPage + 1}" }
            }
    }

    /** Discards the note in progress without writing anything. */
    fun cancelText() {
        textDraft = null
        textDraftAnchor = null
    }

    /**
     * Export the current document's annotations into a new PDF.
     *
     * The save dialog runs on the UI thread because it is modal; only the PDFBox
     * stamping runs off-thread. Resolving the source and model size happens before
     * the dialog, so a missing body fails fast instead of after the user has picked
     * a destination.
     */
    fun exportNow() {
        if (exporting) return
        val src = PdfManager.resolveFile(documentUri)
        if (src == null) {
            exportMessage = "找不到原始 PDF，無法匯出"
            return
        }
        val mW = pageBox?.widthPt ?: box?.widthPt ?: 0f
        val mH = pageBox?.heightPt ?: box?.heightPt ?: 0f
        val base = documentUri.substringAfterLast('/').substringBeforeLast('.').ifEmpty { "document" }
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = "匯出 PDF"
            selectedFile = java.io.File("$base-annotated.pdf")
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("PDF 檔案", "pdf")
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showSaveDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return
        var dest = chooser.selectedFile ?: return
        if (!dest.name.lowercase().endsWith(".pdf")) {
            dest = java.io.File(dest.parent, dest.name + ".pdf")
        }

        exporting = true
        exportMessage = null
        exportScope.launch(Dispatchers.IO) {
            runCatching {
                com.vic.inkflow.util.PdfExporter.exportFromDatabase(
                    databaseManager, documentUri, src, dest, mW, mH
                )
            }.onSuccess { pages ->
                withContext(Dispatchers.Main) {
                    exporting = false
                    exportMessage = "已匯出 $pages 頁：${dest.absolutePath}"
                }
            }.onFailure { e ->
                logger.error(e) { "Failed to export $documentUri" }
                withContext(Dispatchers.Main) {
                    exporting = false
                    exportMessage = "匯出失敗：${e.message ?: e::class.simpleName}"
                }
            }
        }
    }

/**
     * Persists a shape as a two-point stroke, exactly as the tablet does.
     *
     * Two points and a `shapeType` tag is the whole representation — no separate
     * table. That is what lets shapes sync without any protocol change, and it is
     * why the bounds are computed with min/max here rather than read off the
     * points: RECT and CIRCLE are drawn from the bounds, so a shape dragged
     * bottom-to-top must still store `top < bottom` or it renders as a sliver.
     *
     * `docY` is left null for the same reason ink is: the tablet backfills it
     * lazily using the live model height, and a stride guessed here would place
     * the shape on the wrong page.
     */
    fun commitShape(start: Offset, end: Offset) {
        val b = box ?: return
        val stroke = StrokeEntity(
            documentUri = documentUri,
            pageIndex = requestedPage,
            docY = null,
            color = inkColour,
            strokeWidth = INK_WIDTH_PT,
            boundsLeft = minOf(start.x, end.x),
            boundsTop = minOf(start.y, end.y),
            boundsRight = maxOf(start.x, end.x),
            boundsBottom = maxOf(start.y, end.y),
            isHighlighter = false,
            shapeType = shapeSubType.name
        )
        val points = listOf(
            PointEntity(id = 1L, strokeId = stroke.id, x = start.x, y = start.y, width = INK_WIDTH_PT),
            PointEntity(id = 2L, strokeId = stroke.id, x = end.x, y = end.y, width = INK_WIDTH_PT)
        )
        runCatching { databaseManager.saveStroke(stroke, points) }
            .onSuccess {
                val saved = StrokeWithPoints(stroke, points)
                strokes = strokes + saved
                undoStack.push(InkEdit.Add(saved))
                recordOps(
                    listOf(
                        com.vic.inkflow.sync.ProposalOp(
                            op = com.vic.inkflow.sync.ProposalOp.UPSERT_STROKE, stroke = saved
                        )
                    )
                )
                onInkChanged()
            }
            .onFailure {
                logger.error(it) { "Failed to save shape on page ${requestedPage + 1}" }
            }
    }

    fun commitStroke(pts: List<Offset>, modelWidths: List<Float>) {
        val b = box ?: return
        val stroke = StrokeEntity(
            documentUri = documentUri,
            pageIndex = requestedPage,
            docY = null,
            color = inkColour,
            strokeWidth = INK_WIDTH_PT,
            boundsLeft = pts.minOf { it.x },
            boundsTop = pts.minOf { it.y },
            boundsRight = pts.maxOf { it.x },
            boundsBottom = pts.maxOf { it.y },
            isHighlighter = tool == InkTool.Highlighter
        )
        val points = pts.mapIndexed { i, p ->
            PointEntity(
                id = (i + 1).toLong(),
                strokeId = stroke.id,
                x = p.x,
                y = p.y,
                // Per-sample drawn width, exactly like the tablet. Storing the base
                // width here instead would make the stroke render as a flat
                // hairline on both devices.
                width = modelWidths.getOrNull(i) ?: INK_WIDTH_PT
            )
        }
        runCatching { databaseManager.saveStroke(stroke, points) }
            .onSuccess {
                val saved = StrokeWithPoints(stroke, points)
                strokes = strokes + saved
                // Recorded only after the write succeeded, so undo never tries to
                // reverse something that is not in the database.
                undoStack.push(InkEdit.Add(saved))
                recordOps(
                    listOf(
                        com.vic.inkflow.sync.ProposalOp(
                            op = com.vic.inkflow.sync.ProposalOp.UPSERT_STROKE, stroke = saved
                        )
                    )
                )
                onInkChanged()
            }
            .onFailure {
                logger.error(it) { "Failed to save stroke on page ${requestedPage + 1}" }
            }
    }

    /**
     * Removes the strokes a single eraser drag touched.
     *
     * Hit testing is against each stroke's stored bounds, which is a rectangle rather
     * than the true envelope — cheap, and generous enough for a mouse. Deletes are
     * per stroke id, so overlapping bounds on the same page are all caught.
     */
    fun eraseAt(pts: List<Offset>) {
        if (pts.isEmpty()) return
        val hit = strokes.filter { swp ->
            val s = swp.stroke
            val x0 = minOf(s.boundsLeft, s.boundsRight)
            val x1 = maxOf(s.boundsLeft, s.boundsRight)
            val y0 = minOf(s.boundsTop, s.boundsBottom)
            val y1 = maxOf(s.boundsTop, s.boundsBottom)
            pts.any { p -> p.x >= x0 && p.x <= x1 && p.y >= y0 && p.y <= y1 }
        }
        // Notes are erased by ink too, matching the tablet: an eraser should not be able
        // to pass straight through a word and leave it behind.
        val hitText = texts.filter { t ->
            val tb = TextMetrics.bounds(t.modelX, t.modelY, t.text, t.fontSize) ?: return@filter false
            pts.any { p -> p.x >= tb[0] && p.x <= tb[2] && p.y >= tb[1] && p.y <= tb[3] }
        }
        if (hit.isEmpty() && hitText.isEmpty()) return
        runCatching {
            hit.forEach { databaseManager.deleteStroke(it.stroke.id) }
            hitText.forEach { databaseManager.deleteTextAnnotation(it.id) }
        }
            .onSuccess {
                strokes = strokes - hit.toSet()
                texts = texts - hitText.toSet()
                undoStack.push(InkEdit.Erase(hit, hitText))
                recordOps(
                    hit.map {
                        com.vic.inkflow.sync.ProposalOp(
                            op = com.vic.inkflow.sync.ProposalOp.DELETE_STROKE, id = it.stroke.id
                        )
                    } + hitText.map {
                        com.vic.inkflow.sync.ProposalOp(
                            op = com.vic.inkflow.sync.ProposalOp.DELETE_TEXT, id = it.id
                        )
                    }
                )
                onInkChanged()
            }
            .onFailure {
                logger.error(it) { "Failed to erase on page ${requestedPage + 1}" }
            }
    }

    fun undo() {
        when (val e = undoStack.popUndo()) {
            null -> return
            is InkEdit.Add -> runCatching {
                databaseManager.deleteStroke(e.stroke.stroke.id)
            }.onSuccess {
                strokes = strokes - e.stroke
                // Undo is a real write too: without its own op the queue would still
                // say "upsert" for a stroke that no longer exists, and the tablet
                // would end up with a ghost the desktop does not have.
                recordOps(
                    listOf(
                        com.vic.inkflow.sync.ProposalOp(
                            op = com.vic.inkflow.sync.ProposalOp.DELETE_STROKE, id = e.stroke.stroke.id
                        )
                    )
                )
                onInkChanged()
            }.onFailure {
                logger.error(it) { "Undo failed" }
                // Put it back so history does not claim an undo that did not happen.
                undoStack.push(e)
            }

            is InkEdit.Erase -> runCatching {
                e.strokes.forEach { databaseManager.saveStroke(it.stroke, it.points) }
                e.texts.forEach { databaseManager.saveTextAnnotation(it) }
            }.onSuccess {
                strokes = strokes + e.strokes
                texts = texts + e.texts
                recordOps(
                    e.strokes.map {
                        com.vic.inkflow.sync.ProposalOp(
                            op = com.vic.inkflow.sync.ProposalOp.UPSERT_STROKE, stroke = it
                        )
                    } + e.texts.map {
                        com.vic.inkflow.sync.ProposalOp(
                            op = com.vic.inkflow.sync.ProposalOp.UPSERT_TEXT, text = it
                        )
                    }
                )
                onInkChanged()
            }.onFailure {
                logger.error(it) { "Undo of erase failed" }
                undoStack.push(e)
            }

            is InkEdit.AddText -> runCatching {
                databaseManager.deleteTextAnnotation(e.note.id)
            }.onSuccess {
                texts = texts.filterNot { it.id == e.note.id }
                recordOps(
                    listOf(
                        com.vic.inkflow.sync.ProposalOp(
                            op = com.vic.inkflow.sync.ProposalOp.DELETE_TEXT, id = e.note.id
                        )
                    )
                )
                onInkChanged()
            }.onFailure {
                logger.error(it) { "Undo of note add failed" }
                undoStack.push(e)
            }

            // Undo restores the pre-drag snapshot VERBATIM — with no delta applied.
            //
            // The snapshot is the position before the drag, so undo is a restore, not
            // a reverse translation. Subtracting the drag total from the snapshot
            // would move the content that far PAST where it started, which is a
            // silent misplacement rather than an obvious failure.
            //
            // [dx]/[dy] are carried so redo can replay the drag from the same base.
            is InkEdit.Move -> runCatching {
                e.originals.forEach { databaseManager.saveStroke(it.stroke, it.points) }
                e.texts.forEach { databaseManager.saveTextAnnotation(it) }
            }.onSuccess {
                strokes = strokes.map { s -> e.originals.firstOrNull { it.stroke.id == s.stroke.id } ?: s }
                texts = texts.map { t -> e.texts.firstOrNull { it.id == t.id } ?: t }
                recordOps(
                    e.originals.map {
                        com.vic.inkflow.sync.ProposalOp(
                            op = com.vic.inkflow.sync.ProposalOp.UPSERT_STROKE, stroke = it
                        )
                    } + e.texts.map {
                        com.vic.inkflow.sync.ProposalOp(
                            op = com.vic.inkflow.sync.ProposalOp.UPSERT_TEXT, text = it
                        )
                    }
                )
                onInkChanged()
            }.onFailure {
                logger.error(it) { "Undo of move failed" }
                undoStack.push(e)
            }
        }
    }

    fun redo() {
        when (val e = undoStack.popRedo()) {
            null -> return
            is InkEdit.Add -> runCatching {
                databaseManager.saveStroke(e.stroke.stroke, e.stroke.points)
            }.onSuccess {
                strokes = strokes + e.stroke
                recordOps(
                    listOf(
                        com.vic.inkflow.sync.ProposalOp(
                            op = com.vic.inkflow.sync.ProposalOp.UPSERT_STROKE, stroke = e.stroke
                        )
                    )
                )
                onInkChanged()
            }.onFailure {
                logger.error(it) { "Redo failed" }
                undoStack.push(e)
            }

            is InkEdit.Erase -> runCatching {
                e.strokes.forEach { databaseManager.deleteStroke(it.stroke.id) }
                e.texts.forEach { databaseManager.deleteTextAnnotation(it.id) }
            }.onSuccess {
                strokes = strokes - e.strokes.toSet()
                texts = texts - e.texts.toSet()
                recordOps(
                    e.strokes.map {
                        com.vic.inkflow.sync.ProposalOp(
                            op = com.vic.inkflow.sync.ProposalOp.DELETE_STROKE, id = it.stroke.id
                        )
                    } + e.texts.map {
                        com.vic.inkflow.sync.ProposalOp(
                            op = com.vic.inkflow.sync.ProposalOp.DELETE_TEXT, id = it.id
                        )
                    }
                )
                onInkChanged()
            }.onFailure {
                logger.error(it) { "Redo of erase failed" }
                undoStack.push(e)
            }

            is InkEdit.AddText -> runCatching {
                databaseManager.saveTextAnnotation(e.note)
            }.onSuccess {
                texts = texts + e.note
                recordOps(
                    listOf(
                        com.vic.inkflow.sync.ProposalOp(
                            op = com.vic.inkflow.sync.ProposalOp.UPSERT_TEXT, text = e.note
                        )
                    )
                )
                onInkChanged()
            }.onFailure {
                logger.error(it) { "Redo of note add failed" }
                undoStack.push(e)
            }

            // Redo replays the drag from the same pre-drag snapshot the undo restored, so
            // repeated undo/redo cycles converge instead of accumulating error.
            is InkEdit.Move -> runCatching {
                translateStrokes(e.originals, e.dx, e.dy)
                    .forEach { databaseManager.saveStroke(it.stroke, it.points) }
                e.texts.forEach {
                    databaseManager.saveTextAnnotation(
                        it.copy(modelX = it.modelX + e.dx, modelY = it.modelY + e.dy)
                    )
                }
            }.onSuccess {
                val fwd = translateStrokes(e.originals, e.dx, e.dy)
                strokes = strokes.map { s -> fwd.firstOrNull { it.stroke.id == s.stroke.id } ?: s }
                texts = texts.map { t ->
                    e.texts.firstOrNull { it.id == t.id }
                        ?.let { it.copy(modelX = it.modelX + e.dx, modelY = it.modelY + e.dy) } ?: t
                }
                recordOps(
                    fwd.map {
                        com.vic.inkflow.sync.ProposalOp(
                            op = com.vic.inkflow.sync.ProposalOp.UPSERT_STROKE, stroke = it
                        )
                    } + e.texts.map {
                        com.vic.inkflow.sync.ProposalOp(
                            op = com.vic.inkflow.sync.ProposalOp.UPSERT_TEXT,
                            text = it.copy(modelX = it.modelX + e.dx, modelY = it.modelY + e.dy)
                        )
                    }
                )
                onInkChanged()
            }.onFailure {
                logger.error(it) { "Redo of move failed" }
                undoStack.push(e)
            }
        }
    }

    // Declared after commitStroke so it captures that local function directly.
    val commitStrokeNow by rememberUpdatedState<(List<Offset>, List<Float>) -> Unit> { pts, px ->
        commitStroke(pts, px)
    }

    // Same indirection for the eraser, for the same reason: the pointer coroutine
    // must call the current-frame function, not the one captured at gesture start.
    val eraseNow by rememberUpdatedState<(List<Offset>) -> Unit> { pts ->
        eraseAt(pts)
    }

    val undoNow by rememberUpdatedState<() -> Unit> { undo() }
    val redoNow by rememberUpdatedState<() -> Unit> { redo() }

    // Selection helpers need the same indirection for the same reason as the ink ones:
    // the pointer coroutine must call the current-frame function, not the one captured
    // when the gesture started.
    val selectionBoundsNow by rememberUpdatedState<() -> Rect?> { selectionBounds() }
    val moveSelectionNow by rememberUpdatedState<(Float, Float) -> Unit> { dx, dy ->
        moveSelectionBy(dx, dy)
    }
    val commitMoveNow by rememberUpdatedState<() -> Unit> { commitMove() }
    val selectByRectNow by rememberUpdatedState<(Rect, Boolean) -> Unit> { r, add ->
        selectByRect(r, add)
    }
    val deleteSelectionNow by rememberUpdatedState<() -> Unit> { deleteSelection() }

    // Text needs the same treatment, plus a separate one for "the user asked to
    // start writing here" — the pointer coroutine only knows about geometry.
    val commitTextNow by rememberUpdatedState<() -> Unit> { commitText() }
    val cancelTextNow by rememberUpdatedState<() -> Unit> { cancelText() }
    val commitShapeNow by rememberUpdatedState<(Offset, Offset) -> Unit> { a, b -> commitShape(a, b) }

    val focusRequester = remember { FocusRequester() }
    val textFieldFocus = remember { FocusRequester() }

    // Notes are composables, not draw calls, so their font size has to be
    // converted from model units to density-independent units.
    val density = LocalDensity.current

    // While a note is being typed the canvas keeps its own gesture handler live, so
    // a stray drag could otherwise pan the page out from under the caret. The text
    // field is a sibling overlay, not a child of the canvas, so the two never
    // compete for the same events.
    val textFieldFocused = textDraft != null

    /**
     * Where the note editor goes on screen.
     *
     * Placed at the click point in *model* units and then mapped through the same
     * transform as the ink, so it lands under the cursor at any zoom. Positioning it
     * in screen coordinates instead would make the editor drift away from the click
     * as soon as the user zoomed.
     */
    val textEditorPos: Offset? = remember(textDraftAnchor, box, scale, originX, originY, viewportW, viewportH) {
        val anchor = textDraftAnchor
        if (anchor == null || box == null) {
            null
        } else {
            val sx = anchor.x * scale + (originX - box.originX * scale)
            val sy = anchor.y * scale + (originY - box.originY * scale)
            // Keep the editor on screen when the user clicks near an edge; otherwise
            // the caret sits outside the window and typing appears to do nothing.
            Offset(sx.coerceIn(8f, (viewportW - 8f).coerceAtLeast(8f)), sy.coerceIn(8f, (viewportH - 8f).coerceAtLeast(8f)))
        }
    }

    Box(modifier = modifier.fillMaxSize().then(ReaderBackdrop(InkThemeState.darkMode))) {

        // ── Page + ink, one transformable surface ───────────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewport.value = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(documentUri) {
                    // One handler for pinch + drag, so the two can never fight over the
                    // same pointer stream. detectTransformGestures reports the gesture
                    // centroid, which is what makes pinch-zoom keep the pinched point
                    // under the fingers.
                    detectTransformGestures(panZoomLock = true) { centroid, panDelta, zoomFactor, _ ->
                        if (zoomFactor != 1f) zoomBy(zoomFactor, centroid)
                        if (panDelta != Offset.Zero) pan.value = clampPan(pan.value + panDelta)
                    }
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    var deltaX = 0f
                    var deltaY = 0f
                    event.changes.forEach {
                        deltaX += it.scrollDelta.x
                        deltaY += it.scrollDelta.y
                    }
                    if (deltaX == 0f && deltaY == 0f) return@onPointerEvent
                    val mods = event.keyboardModifiers
                    // A scroll event can carry an unspecified position; letting that NaN
                    // into the zoom anchor would poison `pan` and blank the page.
                    val focal = event.changes.firstOrNull()?.position
                        ?.takeIf { it.isSpecified && it.x.isFinite() && it.y.isFinite() }
                        ?: Offset(viewportW / 2f, viewportH / 2f)
                    val handled = if (mods.isCtrlPressed || mods.isMetaPressed) {
                        // Ctrl + wheel is the PDF reader convention for zoom: one step per
                        // notch, in the direction the wheel turns.
                        zoomBy(if (deltaY > 0f) 1f / WHEEL_ZOOM_STEP else WHEEL_ZOOM_STEP, focal)
                        true
                    } else {
                        // scrollDelta is in AWT wheel units, not pixels — a mouse notch is
                        // WHEEL_NOTCH_UNITS, so it has to be scaled to pixels here or a
                        // single notch would fling the page across the screen. A trackpad
                        // sends small continuous values and pans smoothly for free.
                        val step = Offset(deltaX, deltaY) / WHEEL_NOTCH_UNITS * WHEEL_PAN_PX
                        pan.value = clampPan(
                            pan.value + if (mods.isShiftPressed) Offset(step.y, step.x) else step
                        )
                        true
                    }
                    if (handled) event.changes.forEach { it.consume() }
                }
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // Undo/redo first, and before page navigation: these are the keys a person
                    // reaches for mid-stroke, and PageUp/PageDown are also plain arrows.
                    // `event.isCtrlPressed` is pointer API and does not exist on a
                    // KeyEvent — the keyboard modifiers live on the event too but under
                    // `keyEvent.isCtrlPressed` in `androidx.compose.ui.input.key`.
                    val ctrl = event.isCtrlPressed || event.isMetaPressed
                    if (ctrl) {
                        when {
                            event.key == Key.Z && event.isShiftPressed -> { redoNow(); return@onPreviewKeyEvent true }
                            event.key == Key.Z -> { undoNow(); return@onPreviewKeyEvent true }
                            event.key == Key.Y -> { redoNow(); return@onPreviewKeyEvent true }
                        }
                    }
                    when (event.key) {
                        Key.PageDown, Key.DirectionRight, Key.DirectionDown -> {
                            goToPage(requestedPage + 1); true
                        }

                        Key.PageUp, Key.DirectionLeft, Key.DirectionUp -> {
                            goToPage(requestedPage - 1); true
                        }

                        Key.MoveHome -> {
                            goToPage(0); true
                        }

                        Key.MoveEnd -> {
                            goToPage(lastPage); true
                        }

                        // Selection keys only when there is something selected, so
                        // Backspace still means "delete selection" rather than
                        // silently swallowing the key while the tool is on the pen.
                        Key.Delete, Key.Backspace -> {
                            if (selectionCount == 0) false else { deleteSelectionNow(); true }
                        }

                        // Escape is overloaded: while a note is being typed it must
                        // cancel the note, because a stray Escape should not throw
                        // away the selection the user just made.
                        Key.Escape -> {
                            when {
                                textFieldFocused -> { cancelTextNow(); true }
                                selectionCount == 0 -> false
                                else -> {
                                    selectedIds = emptySet()
                                    selectedTextIds = emptySet()
                                    true
                                }
                            }
                        }

                        // Ctrl+A must work with an empty selection — requiring one
                        // made the shortcut unreachable on its very first use.
                        Key.A -> {
                            if (!ctrl) {
                                false
                            } else {
                                selectedIds = strokes.map { it.stroke.id }.toSet()
                                selectedTextIds = texts.map { it.id }.toSet()
                                true
                            }
                        }

                        else -> false
                    }
                }
                .pointerInput(documentUri, requestedPage, editable) {
                    // LAST in the chain on purpose: a pointerInput modifier that is
                    // further from the content receives events first, so this one
                    // sees the press before the pan/zoom handler above and can
                    // consume it. With it first it was last to be called, the
                    // transform gesture had already started panning the page, and
                    // drawing appeared to do nothing at all.
                    if (!editable) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // `toModel` is the current-frame converter, not the one
                        // captured when this coroutine started — see above.
                        val start = toModel(down.position)
                            ?: return@awaitEachGesture // press landed in the margin
                        down.consume()
                        val pts = mutableListOf(Offset(start.x, start.y))
                        val modelWidths = mutableListOf(INK_WIDTH_PT)
                        var lastTime = System.currentTimeMillis()
                        // The eraser follows the same pointer path as the pen and
                        // differs only in what happens on release. Branching here keeps
                        // one gesture handler, so the two tools cannot fight over the
                        // same pointer stream the way a separate handler would.
                        val erasing = tool == InkTool.Eraser
                        val selecting = tool == InkTool.Select
                        val shaping = tool == InkTool.Shape
                        if (shaping) {
                            // Two endpoints, live preview, commit on release. No
                            // smoothing and no per-point width: a shape's geometry
                            // IS the drag, and any "helpfulness" here would make the
                            // stored result disagree with what was on screen.
                            liveShape = start to start
                            var end = start
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.pressed } ?: break
                                val m = toModel(ch.position) ?: continue
                                end = m
                                liveShape = start to m
                                ch.consume()
                            }
                            liveShape = null
                            val moved = ShapeGeometry.lineLength(start.x, start.y, end.x, end.y)
                            // Reject an accidental click: a zero-length shape has no
                            // geometry and would leave an unselectable row behind.
                            if (moved * scale >= MIN_SHAPE_PX) commitShapeNow(start, end)
                            return@awaitEachGesture
                        }
                        if (selecting) {
                            // A press inside the current selection moves it; a press
                            // anywhere else rubber-bands a new one. Decided on the press,
                            // not mid-drag, so a gesture cannot change meaning halfway
                            // through and leave a half-moved selection behind.
                            val box = selectionBoundsNow()
                            val moving = box != null && selectionCount > 0 &&
                                start.x >= box.left && start.x <= box.right &&
                                start.y >= box.top && start.y <= box.bottom
                            val idsAtPress = selectedIds
                            val textIdsAtPress = selectedTextIds
                            val wasMoving = moving
                            var endX = start.x
                            var endY = start.y
                            var additive = false
                            selectionRect = Rect(start.x, start.y, start.x, start.y)

                            // Track the previous model position so each frame moves the
                            // selection by one step. Passing the displacement from the
                            // press instead would re-apply the whole travelled distance
                            // on every frame — the selection runs away and the stored
                            // undo baseline ends up being a mid-drag frame.
                            var prevX = start.x
                            var prevY = start.y

                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.pressed } ?: break
                                val mm = toModel(ch.position) ?: continue
                                additive = ev.keyboardModifiers.isShiftPressed
                                if (moving) {
                                    moveSelectionNow(mm.x - prevX, mm.y - prevY)
                                    prevX = mm.x
                                    prevY = mm.y
                                } else {
                                    endX = mm.x
                                    endY = mm.y
                                    selectionRect = Rect(start.x, start.y, mm.x, mm.y)
                                }
                                ch.consume()
                            }

                            selectionRect = null
                            if (wasMoving) {
                                commitMoveNow()
                                selectedIds = idsAtPress
                                selectedTextIds = textIdsAtPress
                            } else {
                                selectByRectNow(
                                    Rect(
                                        minOf(start.x, endX), minOf(start.y, endY),
                                        maxOf(start.x, endX), maxOf(start.y, endY)
                                    ),
                                    additive
                                )
                            }
                            return@awaitEachGesture
                        }
                        if (tool == InkTool.Text) {
                            // Click-to-type. There is no drag and no preview: the
                            // caret goes where the user clicked and the note is
                            // committed by the field itself.
                            textDraft = ""
                            textDraftAnchor = start
                            return@awaitEachGesture
                        }
                        if (!erasing) {
                            liveStroke = pts
                            liveStrokeWidths = modelWidths
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.pressed } ?: break
                            val m = toModel(change.position)
                            if (m != null) {
                                // Drop sub-pixel jitter: a mouse emits a point per
                                // pixel of travel, and every one of them becomes a
                                // row in `points` that then syncs to the tablet.
                                val last = pts.last()
                                if (abs(m.x - last.x) + abs(m.y - last.y) > 0.35f) {
                                    val now = System.currentTimeMillis()
                                    // Same width model the tablet applies, so a mouse
                                    // stroke is byte-for-byte the shape a
                                    // pressureless stylus would produce there.
                                    val w = com.vic.inkflow.util.InkWidthModel.widthFor(
                                        previous = com.vic.inkflow.util.StrokePoint(
                                            last.x, last.y, modelWidths.last()
                                        ),
                                        posX = m.x,
                                        posY = m.y,
                                        dtMs = now - lastTime,
                                        baseWidth = INK_WIDTH_PT,
                                        isHighlighter = tool == InkTool.Highlighter
                                    )
                                    lastTime = now
                                    pts.add(Offset(m.x, m.y))
                                    modelWidths.add(w)
                                    change.consume()
                                }
                            }
                        }
                        liveStroke = null
                        liveStrokeWidths = emptyList()
                        if (pts.size >= 2) {
                            if (erasing) eraseNow(pts) else commitStrokeNow(pts, modelWidths)
                        }
                    }
                }
        ) {
            val img = frame?.bitmap
            val b = box
            if (img != null && b != null) {
                translate(originX, originY) {
                    drawImage(
                        image = img,
                        dstSize = IntSize(
                            max(1, pageWidthPx.roundToInt()),
                            max(1, pageHeightPx.roundToInt())
                        )
                    )
                    // Ink shares the image's transform exactly: same scale, same origin, and
                    // the same page box, so pinch and drag move the two together by
                    // construction rather than by two sets of arithmetic agreeing.
for (swp in strokes) {
                            drawStroke(swp, scale, b)
                        }

                        // Notes, drawn above the ink and in the same translate.
                        //
                        // The top edge comes from TextMetrics (baseline minus ascent)
                        // rather than from modelY directly: modelY is a baseline, and
                        // Compose's drawText wants a top-left. Getting this wrong drops
                        // every note exactly one line below where it was clicked.
                        

                        // Selection outline, drawn inside the same translate as the ink
                        // so it tracks zoom and pan with the strokes by construction.
                        // The colour is a fixed indigo rather than the live scheme so
                        // the outline reads as "UI" and not as part of the drawing.
                        selectionBoundsNow()?.let { sb ->
                            drawRect(
                                color = Color(0xFF6366F1).copy(alpha = 0.95f),
                                topLeft = Offset(sb.left * scale - b.originX * scale, sb.top * scale - b.originY * scale),
                                size = androidx.compose.ui.geometry.Size(sb.width * scale, sb.height * scale),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f * scale)
                            )
                        }
                        // Rubber band: dashed-looking by dashing the four edges cheaply
                        // with a translucent fill instead of a stroke, which at this
                        // size reads the same and costs one draw call.
                        selectionRect?.let { rr ->
                            drawRect(
                                color = Color(0xFF6366F1).copy(alpha = 0.14f),
                                topLeft = Offset(rr.left * scale - b.originX * scale, rr.top * scale - b.originY * scale),
                                size = androidx.compose.ui.geometry.Size(rr.width * scale, rr.height * scale)
                            )
                            drawRect(
                                color = Color(0xFF6366F1).copy(alpha = 0.8f),
                                topLeft = Offset(rr.left * scale - b.originX * scale, rr.top * scale - b.originY * scale),
                                size = androidx.compose.ui.geometry.Size(rr.width * scale, rr.height * scale),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f * scale)
                            )
                        }
                // The stroke currently under the pointer. Drawn through the same
                    // transform as everything else so it cannot drift from the
                    // committed path it is about to become.
                    //
                    // Mapping is page pixels ((model - origin) * scale) with NO added
                    // viewport origin: this block already runs inside
                    // translate(originX, originY), exactly like drawStroke. Adding the
                    // origin again is what used to throw the preview a full viewport
                    // away from the cursor — visible as "no preview at all" whenever
                    // the page was centred with a margin.
                    liveStroke?.takeIf { it.isNotEmpty() }?.let { pts ->
                        val screenPts = pts.map {
                            Offset(
                                (it.x - b.originX) * scale,
                                (it.y - b.originY) * scale
                            )
                        }
                        drawPath(
                            path = com.vic.inkflow.util.EnvelopeUtils.generateEnvelopePath(
                                screenPts.mapIndexed { i, o ->
                                    com.vic.inkflow.util.StrokePoint(
                                        o.x, o.y,
                                        liveStrokeWidths.getOrNull(i) ?: (INK_WIDTH_PT * scale)
                                    )
                                }
                            ),
color = Color(inkColour).copy(
                                  alpha = if (tool == InkTool.Highlighter) HIGHLIGHTER_ALPHA else 1f
                              )
                          )
                      }

                      // Shape preview, drawn through the same translate as
                      // everything else. Rendered as a primitive outline rather than
                      // through the envelope path, which is what it will be
                      // committed as — so the preview cannot differ from the result.
                      //
                      // Same mapping as the pen preview above: page pixels, no added
                      // viewport origin (the translate already provides it).
                      liveShape?.let { (a, z) ->
                          val sa = Offset((a.x - b.originX) * scale, (a.y - b.originY) * scale)
                          val sz = Offset((z.x - b.originX) * scale, (z.y - b.originY) * scale)
                          val sw = (INK_WIDTH_PT * scale).coerceAtLeast(MIN_INK_PX)
                          val previewStyle = androidx.compose.ui.graphics.drawscope.Stroke(
                              width = sw,
                              cap = androidx.compose.ui.graphics.StrokeCap.Round,
                              join = androidx.compose.ui.graphics.StrokeJoin.Round
                          )
                          val ink = Color(inkColour)
                          when (shapeSubType) {
                              com.vic.inkflow.util.ShapeType.RECT -> drawRect(
                                  color = ink,
                                  topLeft = Offset(minOf(sa.x, sz.x), minOf(sa.y, sz.y)),
                                  size = androidx.compose.ui.geometry.Size(
                                      abs(sz.x - sa.x), abs(sz.y - sa.y)
                                  ),
                                  style = previewStyle
                              )

                              com.vic.inkflow.util.ShapeType.CIRCLE -> drawOval(
                                  color = ink,
                                  topLeft = Offset(minOf(sa.x, sz.x), minOf(sa.y, sz.y)),
                                  size = androidx.compose.ui.geometry.Size(
                                      abs(sz.x - sa.x), abs(sz.y - sa.y)
                                  ),
                                  style = previewStyle
                              )

                              com.vic.inkflow.util.ShapeType.LINE -> drawLine(
                                  color = ink, start = sa, end = sz, strokeWidth = sw,
                                  cap = androidx.compose.ui.graphics.StrokeCap.Round
                              )

                              com.vic.inkflow.util.ShapeType.ARROW -> {
                                  drawLine(
                                      color = ink, start = sa, end = sz, strokeWidth = sw,
                                      cap = androidx.compose.ui.graphics.StrokeCap.Round
                                  )
                                  val head = ShapeGeometry.arrowHead(
                                      a.x, a.y, z.x, z.y,
                                      ShapeGeometry.arrowHeadSize(INK_WIDTH_PT)
                                  )
                                  // The barbs come back in model coordinates, so only
                                  // their RELATIVE vector may be added to the
                                  // page-pixel tip. Adding the absolute model
                                  // position instead plants the head somewhere near
                                  // the page origin rather than at the arrow tip.
                                  drawLine(
                                      color = ink, start = sz,
                                      end = Offset(sz.x + (head.first.x - z.x) * scale, sz.y + (head.first.y - z.y) * scale),
                                      strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round
                                  )
                                  drawLine(
                                      color = ink, start = sz,
                                      end = Offset(sz.x + (head.second.x - z.x) * scale, sz.y + (head.second.y - z.y) * scale),
                                      strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round
                                  )
                              }
                          }
                      }
                  }
            }
        }

        // ── Notes layer ───────────────────────────────────────────────────────
        // Real Text composables rather than draw calls on the canvas.
        //
        // Two reasons, both practical. DrawScope has no drawText in this Compose
        // version, and reaching for Skia directly would mean reimplementing font
        // fallback — which is exactly what breaks when CJK and Latin share a note.
        // As composables the notes also get proper text shaping and stay crisp at
        // any DPI for free.
        //
        // The layer sits above the canvas and uses the same model->screen mapping,
        // so notes track zoom and pan with the ink. `pointerInput` is absent on
        // purpose: a note must never intercept a drag meant for the page beneath it.
        val noteBox = box
        if (noteBox != null && texts.isNotEmpty()) {
            texts.forEach { t ->
                val tb = TextMetrics.bounds(t.modelX, t.modelY, t.text, t.fontSize)
                    ?: return@forEach
                Text(
                    text = t.text,
                    modifier = Modifier.offset {
                        IntOffset(
                            (tb[0] * scale + (originX - noteBox.originX * scale)).roundToInt(),
                            (tb[1] * scale + (originY - noteBox.originY * scale)).roundToInt()
                        )
                    },
                    color = Color(t.colorArgb),
                    fontSize = with(density) { (t.fontSize * scale).toSp() },
                    // No width cap: the note's own width IS its model width, and
                    // wrapping it here would desynchronise the drawn text from the
                    // box used for hit-testing and for syncing.
                    softWrap = false,
                    lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified
                )
            }
        }

        // Derived, not stored: a missing file, an encrypted file and a slow render are all
        // "nothing to show yet", and only the error state should be able to clear this.
        val loading = loadError == null && rendered == null
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        if (loadError != null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "無法開啟 PDF：$loadError",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // ── Page navigation ─────────────────────────────────────────────────
        if (pageCount > 0) {
            PageNavBar(
                pageIndex = requestedPage,
                pageCount = pageCount,
                onPrevious = { goToPage(requestedPage - 1) },
                onNext = { goToPage(requestedPage + 1) },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // ── Ink toolbar ────────────────────────────────────────────────────
        // Only shown when [editable]. Top-start so it does not collide with the
        // page navigator at the bottom.
        // ── Note editor ───────────────────────────────────────────────────────
        // A plain borderless field rather than a Material outlined one: it has to
        // sit directly on the page and read as part of the annotation, not as a
        // dialog that happened to land on the paper.
        //
        // Commit on focus loss as well as on Enter, because clicking the next word
        // to keep typing is the natural gesture and it must not discard the note.
        if (editable && textDraft != null && textEditorPos != null) {
            val draft = textDraft ?: ""
            OutlinedTextField(
                value = draft,
                onValueChange = { textDraft = it },
                modifier = Modifier
                    .offset { IntOffset(textEditorPos.x.roundToInt(), textEditorPos.y.roundToInt()) }
                    .width(280.dp)
                    .focusRequester(textFieldFocus),
                placeholder = { Text("輸入文字，Enter 完成", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color(inkColour)),
                singleLine = false,
                maxLines = 4,
                shape = RoundedCornerShape(8.dp),
                // Container follows the theme, not a hardcoded white: on a dark
                // backdrop a white box glares, and with a dark ink colour the text
                // inside it becomes unreadable. surfaceVariant keeps contrast with
                // both the page and the typed ink in either mode.
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            LaunchedEffect(draft) {
                // Ask for focus as soon as the field exists. Done in an effect keyed
                // on the draft so a recomposition from typing does not steal focus
                // from something the user has since clicked into.
                runCatching { textFieldFocus.requestFocus() }
            }
        }

        if (editable && rendered != null && loadError == null) {
            InkToolbar(
                tool = tool,
                colour = inkColour,
                onToolChange = { tool = it },
                onColourChange = { inkColour = it },
                shapeSubType = shapeSubType,
                onShapeSubTypeChange = { shapeSubType = it },
                canUndo = undoStack.canUndo,
                canRedo = undoStack.canRedo,
                selectionCount = selectionCount,
                onUndo = { undoNow() },
                onRedo = { redoNow() },
                onDeleteSelection = { deleteSelectionNow() },
                onClear = {
                    // Clear covers the whole document, not the page, and it wipes
                    // notes too. It is the one destructive control with no natural
                    // inverse, so it pushes an undo entry like everything else —
                    // "clear" being un-undoable is how people lose work they did not
                    // mean to lose.
                    val doomedAll = strokes
                    val doomedAllText = texts
                    runCatching {
                        databaseManager.deleteStrokesForDocument(documentUri)
                        databaseManager.deleteTextAnnotationsForDocument(documentUri)
                    }
                        .onSuccess {
                            strokes = emptyList()
                            texts = emptyList()
                            selectedIds = emptySet()
                            selectedTextIds = emptySet()
                            undoStack.push(InkEdit.Erase(doomedAll, doomedAllText))
                            recordOps(
                                doomedAll.map {
                                    com.vic.inkflow.sync.ProposalOp(
                                        op = com.vic.inkflow.sync.ProposalOp.DELETE_STROKE, id = it.stroke.id
                                    )
                                } + doomedAllText.map {
                                    com.vic.inkflow.sync.ProposalOp(
                                        op = com.vic.inkflow.sync.ProposalOp.DELETE_TEXT, id = it.id
                                    )
                                }
                            )
                            onInkChanged()
                        }
                        .onFailure {
                            logger.error(it) { "Failed to clear ink on ${documentUri.substringAfterLast('/')}" }
                        }
                },
                exporting = exporting,
                onExport = { exportNow() },
                exportStatus = exportMessage,
                documentTitle = documentTitle,
                isAiPanelOpen = isAiPanelOpen,
                onToggleAiPanel = onToggleAiPanel,
                modifier = Modifier.align(Alignment.TopStart)
            )
        }
    }

    // Keyboard navigation needs focus. Requested once per document, so a later click
    // into another panel is not fought over.
    LaunchedEffect(documentUri) {
        runCatching { focusRequester.requestFocus() }
    }
}

/**
 * What an undoable edit did, in terms the desktop can actually reverse.
 *
 * Ink and notes both appear here because both tables exist on the desktop now. The
 * tablet's `DrawCommand` is still not reusable wholesale — it carries shape and image
 * branches with no desktop counterpart — but the history *discipline* is shared through
 * `UndoStack`: one gesture is one entry, and every branch has a matching inverse.
 */
private sealed interface InkEdit {
    /** A finished stroke was inserted. Undo deletes it; redo re-inserts it. */
    data class Add(val stroke: StrokeWithPoints) : InkEdit

    /** A note was inserted. Undo deletes it; redo re-inserts it. */
    data class AddText(val note: com.vic.inkflow.data.TextAnnotationEntity) : InkEdit

    /**
     * One eraser gesture (or one selection delete) removed these items.
     *
     * Grouped per gesture rather than per object: a single drag across a paragraph
     * should come back in one press, which is what the tablet's `EraseGesture` does.
     * Strokes and notes travel together because the user asked for one action —
     * splitting them would need two undos to reverse one gesture.
     */
    data class Erase(
        val strokes: List<StrokeWithPoints>,
        val texts: List<com.vic.inkflow.data.TextAnnotationEntity> = emptyList()
    ) : InkEdit

    /**
     * A selection was dragged by [dx]/[dy] model units.
     *
     * [originals] and [texts] are snapshots taken once at the start of the drag, which
     * is what undo restores. Snapshots rather than references on purpose: a later edit
     * must not be able to mutate what the history is holding.
     *
     * [dx]/[dy] are the TOTAL for the whole drag, not one frame. A drag writes to the
     * database on every frame it moves, and folding that into a single entry requires
     * the total — otherwise a selection that travelled 300pt over 30 frames would have
     * its 30th frame's 10pt step recorded, and undo would only move it back 10pt.
     *
     * Stored as a delta rather than an "after" snapshot because a move is exactly
     * invertible — replaying the same translation forward or back cannot drift the way
     * storing two independently-computed states could.
     */
    data class Move(
        val originals: List<StrokeWithPoints>,
        val texts: List<com.vic.inkflow.data.TextAnnotationEntity>,
        val dx: Float,
        val dy: Float
    ) : InkEdit
}

enum class InkTool { Pen, Highlighter, Eraser, Select, Text, Shape }

/**
 * Note font size in **model units** (PDF points), so it scales with the page exactly
 * like the ink does. Matches the tablet's `text_annotations.fontSize` default of 16.
 */
private const val TEXT_SIZE_PT = 16f

/**
 * Stroke width in **model units** (PDF points), not pixels, so it scales with the
 * page exactly the way the tablet's does — a 2pt pen is 2pt at any zoom.
 */
private const val INK_WIDTH_PT = 1.6f

/**
 * Floating ink toolbar: tool, colour, and a destructive clear.
 *
 * Clear is the only destructive control and it is labelled, not an icon-only
 * button, because it throws away every stroke on the document with no undo.
 */
@Composable
private fun InkToolbar(
    tool: InkTool,
    colour: Int,
    onToolChange: (InkTool) -> Unit,
    onColourChange: (Int) -> Unit,
    shapeSubType: com.vic.inkflow.util.ShapeType,
    onShapeSubTypeChange: (com.vic.inkflow.util.ShapeType) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    selectionCount: Int,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDeleteSelection: () -> Unit,
    onClear: () -> Unit,
    exporting: Boolean,
    onExport: () -> Unit,
    exportStatus: String?,
    documentTitle: String,
    isAiPanelOpen: Boolean,
    onToggleAiPanel: () -> Unit,
    modifier: Modifier = Modifier
) {
    // One row, period: tools, history, colours, actions and AI ride a single
    // horizontal pill that scrolls instead of wrapping. A wrapping toolbar cost
    // a second row of page space and read as clutter; scrolling costs nothing
    // because the primary tools sit at the head of the row.
    //
    // Near-opaque on purpose: a translucent pill over body text fails both
    // ways — the page shows through and neither layer stays legible. The rim
    // and sheen from glassDressing keep the glass read without the washout.
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val pillShape = RoundedCornerShape(24.dp)
        Row(
            modifier = modifier
                .padding(12.dp)
                .clip(pillShape)
                .background(
                    MaterialTheme.colorScheme.surface.copy(
                        alpha = if (InkThemeState.darkMode) 0.88f else 0.92f
                    ),
                    pillShape
                )
                .glassDressing(isDark = InkThemeState.darkMode, shape = pillShape)
                .clip(pillShape)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // The document title lives here now that the top app bar is gone: a
            // compact chip that shrinks to an ellipsis instead of shoving tools
            // off the row.
            if (documentTitle.isNotBlank()) {
                Text(
                    documentTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp).padding(end = 4.dp)
                )
            }
            InkToolIcon(
                icon = Icons.Rounded.Brush,
                description = "筆",
                selected = tool == InkTool.Pen,
                onClick = { onToolChange(InkTool.Pen) }
            )
            InkToolIcon(
                icon = Icons.Rounded.Highlight,
                description = "螢光筆",
                selected = tool == InkTool.Highlighter,
                onClick = { onToolChange(InkTool.Highlighter) }
            )
            InkToolIcon(
                icon = Icons.Rounded.DeleteSweep,
                description = "橡皮擦",
                selected = tool == InkTool.Eraser,
                onClick = { onToolChange(InkTool.Eraser) }
            )
            InkToolIcon(
                icon = Icons.Rounded.Gesture,
                description = if (selectionCount > 0) "選取（$selectionCount）" else "選取",
                selected = tool == InkTool.Select,
                badgeCount = selectionCount,
                onClick = { onToolChange(InkTool.Select) }
            )
            InkToolIcon(
                icon = Icons.Rounded.TextFields,
                description = "文字",
                selected = tool == InkTool.Text,
                onClick = { onToolChange(InkTool.Text) }
            )
            InkToolIcon(
                icon = Icons.Rounded.ShapeLine,
                description = "形狀",
                selected = tool == InkTool.Shape,
                onClick = { onToolChange(InkTool.Shape) }
            )
            androidx.compose.material3.VerticalDivider(
                modifier = Modifier.height(24.dp).padding(horizontal = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            )
            InkToolIcon(
                icon = Icons.Rounded.Undo,
                description = "復原",
                selected = false,
                enabled = canUndo,
                onClick = onUndo
            )
            InkToolIcon(
                icon = Icons.Rounded.Redo,
                description = "重做",
                selected = false,
                enabled = canRedo,
                onClick = onRedo
            )
            if (selectionCount > 0) {
                // Delete lives here rather than only on the keyboard: the selection is a
                // mode, and a control that exists only as a shortcut is a control most
                // people never find.
                InkToolIcon(
                    icon = Icons.Rounded.Backspace,
                    description = "刪除選取",
                    selected = false,
                    onClick = onDeleteSelection
                )
            }

        // Shape subtypes ride the same row while the shape tool is armed — no
        // second row, no popup. They scroll with everything else.
        if (tool == InkTool.Shape) {
            androidx.compose.material3.VerticalDivider(
                modifier = Modifier.height(24.dp).padding(horizontal = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            )
            com.vic.inkflow.util.ShapeType.entries.forEach { st ->
                GlassOptionChip(
                    text = SHAPE_LABELS[st] ?: st.name,
                    selected = shapeSubType == st,
                    onClick = { onShapeSubTypeChange(st) },
                    isDark = InkThemeState.darkMode
                )
            }
        }

        androidx.compose.material3.VerticalDivider(
            modifier = Modifier.height(24.dp).padding(horizontal = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        )
            INK_PALETTE.forEach { swatch ->
                val chosen = colour == swatch
                // Every swatch keeps a faint ring: without it the near-black ink
                // dot vanishes on a dark toolbar and looks like a missing colour.
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(swatch))
                        .border(
                            width = if (chosen) 2.5.dp else 1.dp,
                            color = if (chosen) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            shape = CircleShape
                        )
                        .clip(CircleShape)
                        .clickable { onColourChange(swatch) }
                )
            }
            Spacer(Modifier.width(6.dp))
            GlassTextButton(
                text = if (exporting) "匯出中…" else "匯出 PDF",
                onClick = onExport,
                enabled = !exporting
            )
            GlassTextButton(
                text = "清除",
                onClick = onClear,
                color = MaterialTheme.colorScheme.error
            )
            // AI rides the same chain: one entry point, always visible, never a
            // second floating control. The backend stays reserved — the button
            // only opens and closes the panel shell.
            androidx.compose.material3.VerticalDivider(
                modifier = Modifier.height(24.dp).padding(horizontal = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            )
            InkToolIcon(
                icon = Icons.Rounded.AutoAwesome,
                description = "AI 助手",
                selected = isAiPanelOpen,
                onClick = onToggleAiPanel
            )
        }
        // Export feedback lives under the toolbar rather than in a dialog: a modal
        // would interrupt reading, while a missing success message leaves the user
        // unsure whether the file was actually written.
        exportStatus?.let { status ->
            Text(
                status,
                modifier = Modifier.padding(bottom = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

/**
 * One icon tool button, in the tablet's language: the icon carries the meaning,
 * the selection pill carries the state, and the tint follows the theme.
 *
 * Icon-only on purpose — six text labels plus undo/redo is what overflowed the old
 * row on a normal window. The description survives for screen readers and tooltips.
 */
@Composable
private fun InkToolIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    selected: Boolean,
    enabled: Boolean = true,
    badgeCount: Int = 0,
    onClick: () -> Unit
) {
    val isDark = InkThemeState.darkMode
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.glassSelectionPill(CircleShape) else Modifier)
            .glassClickable(onClick = onClick, shape = CircleShape, enabled = enabled),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = glassContentColor(isDark).copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(22.dp)
        )
        // Selection count rides on the icon rather than in a label: the row stays
        // compact and the number is visible exactly where the mode is armed.
        if (badgeCount > 0) {
            Text(
                text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                    .padding(horizontal = 3.dp)
            )
        }
    }
}

/** Labels for the shape subtypes, in the tablet's terms. */
private val SHAPE_LABELS: Map<com.vic.inkflow.util.ShapeType, String> = mapOf(
    com.vic.inkflow.util.ShapeType.RECT to "矩形",
    com.vic.inkflow.util.ShapeType.CIRCLE to "橢圓",
    com.vic.inkflow.util.ShapeType.LINE to "直線",
    com.vic.inkflow.util.ShapeType.ARROW to "箭頭"
)

private val INK_PALETTE = listOf(
    0xFF121826.toInt(), // ink black
    0xFFDC2626.toInt(), // red
    0xFF2563EB.toInt(), // blue
    0xFF059669.toInt()  // green
)

/**
 * The reading surface.
 *
 * Deliberately **opaque**, and the one surface in this reader that is not glass.
 * Every other panel floats *above* the document, which is what makes the material
 * read as a layer; putting translucency on the page itself costs the contrast the
 * ink needs to stay legible and looks like a bug rather than a style.
 *
 * A flat `surfaceVariant` fill is replaced with a very soft vertical gradient, so
 * the area behind the paper reads as a lit surface instead of a grey slab — while
 * still being unambiguously a backdrop.
 */
@Composable
private fun ReaderBackdrop(isDark: Boolean): Modifier = Modifier.background(
    Brush.verticalGradient(
        0f to (if (isDark) Color(0xFF0B1120) else Color(0xFFEDEFF6)),
        1f to (if (isDark) Color(0xFF060911) else Color(0xFFE2E6F0))
    )
)

/**
 * Screen (viewport) coordinates -> model coordinates (PDF points).
 *
 * The exact inverse of `screen = (viewport - page) / 2 + pan + model * scale`,
 * i.e. `model = (screen - origin) / scale`, plus the page box origin because points
 * are stored relative to the CropBox.
 *
 * Top-level and pure rather than a closure inside the composable, for two reasons:
 * the drawing gesture has to read the transform of the frame it is in (a captured
 * `box` is null on the first composition, which is exactly what made the tool look
 * dead), and a stroke that is *drawn* in the right place but *stored* somewhere
 * else is a silent bug that no screenshot can reveal.
 *
 * Returns null outside the page rectangle. Without that guard a stroke begun in
 * the grey margin would be extrapolated into the page, and the tablet would later
 * draw ink where nobody ever pointed.
 */
internal fun screenToModel(
    screen: Offset,
    box: PageBox,
    scale: Float,
    originX: Float,
    originY: Float
): Offset? {
    if (scale <= 0f) return null
    val x = (screen.x - originX) / scale + box.originX
    val y = (screen.y - originY) / scale + box.originY
    return if (x in 0f..box.widthPt && y in 0f..box.heightPt) Offset(x, y) else null
}

/**
 * One stroke, drawn through the tablet's own envelope routine.
 *
 * `points.width` is the real drawn width (velocity-derived on the tablet), while
 * `stroke.strokeWidth` is only the base width the user picked. Using the latter
 * would flatten every brush stroke to a hairline, so the per-sample widths are
 * what get rendered.
 *
 * A stroke whose samples all carry the same width still goes through the
 * envelope — it costs one path fill and keeps a single code path, which matters
 * more here than the micro-optimisation would.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStroke(
      swp: StrokeWithPoints,
      scale: Float,
      box: PageBox
  ) {
      val pts = swp.points
      if (pts.isEmpty()) return

      // Shapes branch out here, before the envelope renderer.
      //
      // A shape is stored as two points plus a type tag, and it is a stroked
      // outline — not a variable-width polygon. Feeding it to generateEnvelopePath
      // turns a rectangle into a thin lens between its two corners, which is why
      // the tablet never routes shapes through that path either.
      val shapeType = com.vic.inkflow.util.ShapeGeometry.parseType(swp.stroke.shapeType)
      if (shapeType != null) {
          drawShape(swp, shapeType, scale, box)
          return
      }

      val strokePoints = pts.map {
        com.vic.inkflow.util.StrokePoint(
            x = (it.x - box.originX) * scale,
            y = (it.y - box.originY) * scale,
            width = (it.width.takeIf { w -> w > 0f } ?: swp.stroke.strokeWidth)
                .coerceAtLeast(MIN_INK_PT)
                    .times(scale)
                    .coerceAtLeast(MIN_INK_PX)
        )
    }
    val ink = Color(swp.stroke.color)
    drawPath(
        path = com.vic.inkflow.util.EnvelopeUtils.generateEnvelopePath(strokePoints),
        color = if (swp.stroke.isHighlighter) ink.copy(alpha = HIGHLIGHTER_ALPHA) else ink
    )
}

/**
   * Draw one shape, matching the tablet's `drawShapeOnCanvas`.
   *
   * The split that matters: RECT and CIRCLE use the box, LINE and ARROW use the two
   * endpoints **in stored order**. Sorting the endpoints would flip the direction of
   * every shape drawn upwards — visible, but not something a user would report,
   * since the shape is still there.
   */
  private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShape(
      swp: StrokeWithPoints,
      type: com.vic.inkflow.util.ShapeType,
      scale: Float,
      box: PageBox
  ) {
      val ink = Color(swp.stroke.color)
      val width = com.vic.inkflow.util.ShapeGeometry.strokeWidthFor(type, swp.stroke.strokeWidth)
          .times(scale).coerceAtLeast(MIN_INK_PX)
      val style = androidx.compose.ui.graphics.drawscope.Stroke(
          width = width, cap = androidx.compose.ui.graphics.StrokeCap.Round,
          join = androidx.compose.ui.graphics.StrokeJoin.Round
      )

      fun px(mx: Float, my: Float) = Offset(
          (mx - box.originX) * scale, (my - box.originY) * scale
      )

      val modelPts = swp.points.map {
          com.vic.inkflow.util.StrokePoint(it.x, it.y, it.width)
      }
      val b = com.vic.inkflow.util.ShapeGeometry.boundsOf(modelPts)

      when (type) {
          com.vic.inkflow.util.ShapeType.RECT -> drawRect(
              color = ink,
              topLeft = px(b[0], b[1]),
              size = androidx.compose.ui.geometry.Size((b[2] - b[0]) * scale, (b[3] - b[1]) * scale),
              style = style
          )

          com.vic.inkflow.util.ShapeType.CIRCLE -> drawOval(
              color = ink,
              topLeft = px(b[0], b[1]),
              size = androidx.compose.ui.geometry.Size((b[2] - b[0]) * scale, (b[3] - b[1]) * scale),
              style = style
          )

          com.vic.inkflow.util.ShapeType.LINE -> {
              val e = com.vic.inkflow.util.ShapeGeometry.endpoints(modelPts)
              drawLine(
                  color = ink,
                  start = px(e[0], e[1]), end = px(e[2], e[3]),
                  strokeWidth = width, cap = androidx.compose.ui.graphics.StrokeCap.Round
              )
          }

          com.vic.inkflow.util.ShapeType.ARROW -> {
              val e = com.vic.inkflow.util.ShapeGeometry.endpoints(modelPts)
              val a = px(e[0], e[1])
              val z = px(e[2], e[3])
              drawLine(
                  color = ink, start = a, end = z,
                  strokeWidth = width, cap = androidx.compose.ui.graphics.StrokeCap.Round
              )
              // The head size is computed in model units and scaled with everything
              // else, so the arrow does not keep a constant pixel size while the
              // page zooms.
              val head = com.vic.inkflow.util.ShapeGeometry.arrowHead(
                  e[0], e[1], e[2], e[3],
                  com.vic.inkflow.util.ShapeGeometry.arrowHeadSize(swp.stroke.strokeWidth)
              )
              drawLine(
                  color = ink, start = z, end = px(head.first.x, head.first.y),
                  strokeWidth = width, cap = androidx.compose.ui.graphics.StrokeCap.Round
              )
              drawLine(
                  color = ink, start = z, end = px(head.second.x, head.second.y),
                  strokeWidth = width, cap = androidx.compose.ui.graphics.StrokeCap.Round
              )
          }
      }
  }

  /** Matches the tablet's highlighter opacity. */
  private const val HIGHLIGHTER_ALPHA = 0.35f

/** Never let ink vanish entirely when zoomed far out. */
private const val MIN_INK_PX = 0.6f

/** Smallest sensible drawn width in model units, mirroring the tablet's floor. */
  private const val MIN_INK_PT = 0.05f

  /**
   * Shortest shape worth committing, in **pixels**.
   *
   * A click with the shape tool produces a zero-length shape with no geometry: it
   * draws nothing, cannot be selected, and adds a row that only confuses a later
   * export. Thresholding in pixels rather than model units keeps the rule at "bigger
   * than a tap" regardless of zoom level.
   */
  private const val MIN_SHAPE_PX = 3f

/**
 * One model-space point -> pixels inside the page's own rectangle, i.e. the coordinate
 * space the bitmap is drawn in. Subtracting the box origin keeps the ink aligned on a
 * page whose CropBox does not start at (0, 0).
 */
private fun PointEntity.toPagePixel(scale: Float, box: PageBox): Offset =
    Offset((x - box.originX) * scale, (y - box.originY) * scale)

/**
 * Bottom bar with previous/next and the page counter.
 *
 * Floats over the page, so it takes the shared glass treatment: a translucent pill
 * the document stays readable through. It was `surface` at 92% alpha, which is
 * nearly opaque and therefore just covered the page.
 *
 * Both buttons are disabled at the ends of the document instead of silently doing
 * nothing, and the counter shows the total so the position inside a 900 page document is
 * obvious without scrolling.
 */
@Composable
private fun PageNavBar(
    pageIndex: Int,
    pageCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier = modifier
            .padding(16.dp)
            .widthIn(min = 240.dp)
            .glassDressing(isDark = InkThemeState.darkMode, shape = shape)
            .clip(shape)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        IconButton(onClick = onPrevious, enabled = pageIndex > 0) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "上一頁")
        }
        Text(
            "第 ${pageIndex + 1} 頁 / 共 $pageCount 頁",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onNext, enabled = pageIndex < pageCount - 1) {
            Icon(Icons.Default.ChevronRight, contentDescription = "下一頁")
        }
    }
}

/**
 * Zoom bounds, relative to fit-to-window. `1.0` is "the whole page on screen", which is the
 * state a reopened document starts in. `0.25` is an overview — the page with its
 * neighbours visible — and is the floor because a reader who has zoomed out this far is
 * orienting themselves, not reading. `8.0` is where 6 pt body text is comfortably legible;
 * past that the render DPI cap of 300 means the extra zoom would only enlarge an already
 * soft bitmap, so stopping here costs nothing and bounds the worst-case raster.
 */
private const val MIN_ZOOM = 0.25f
private const val MAX_ZOOM = 8.0f

/** Zoom multiplier per wheel notch, so Ctrl + wheel tracks how a mouse wheel feels. */
private const val WHEEL_ZOOM_STEP = 1.15f

/**
 * AWT reports one mouse wheel notch as this many units (`MouseWheelEvent.WHEEL_DELTA`),
 * and Compose Desktop passes that value straight through as `scrollDelta`. Converting to
 * pixels has to divide by it. A precision trackpad sends small fractional deltas instead,
 * which fall out as fine-grained panning for free.
 */
private const val WHEEL_NOTCH_UNITS = 120f

/** Pixels of pan per wheel notch. */
private const val WHEEL_PAN_PX = 72f

/**
 * How long the zoom must hold still before a re-render starts. Long enough that a pinch
 * never triggers a render mid-gesture, short enough that the sharper raster appears while
 * the user is still looking at it.
 */
private const val RENDER_SETTLE_MS = 160L
