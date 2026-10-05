package com.vic.inkflow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
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
import com.vic.inkflow.util.PageBox
import com.vic.inkflow.util.PdfManager
import com.vic.inkflow.util.PdfResult
import com.vic.inkflow.util.RenderedPage
import com.vic.inkflow.util.UndoStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
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
     * Turns on the ink toolbar and lets the user draw on the page.
     *
     * Off by default because the sync protocol is **pull-only**: the tablet is the
     * only writer (see SYNC_PROTOCOL.md §1), so a stroke made here lands in the
     * desktop database and shows up immediately, but is never pushed back to the
     * tablet and the next pull can discard it. Drawing is therefore opt-in per
     * call site rather than something the reader discovers by accident.
     */
    editable: Boolean = false,
    /** Fired after a stroke is committed, so the caller can refresh counts. */
    onInkChanged: () -> Unit = {}
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
    var strokes by remember(documentUri, requestedPage) {
        mutableStateOf<List<StrokeWithPoints>>(emptyList())
    }

    // ── Drawing ─────────────────────────────────────────────────────────────
    // Kept as plain state next to `strokes` rather than in a ViewModel: the ink
    // tool has no lifetime beyond this composable, and the durable copy always
    // goes through the database.
    var tool by remember { mutableStateOf(InkTool.Pen) }
    var inkColour by remember { mutableIntStateOf(0xFF121826.toInt()) }
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
    LaunchedEffect(documentUri, requestedPage) {
        strokes = runCatching {
            withContext(Dispatchers.IO) {
                databaseManager.getStrokesForPage(documentUri, requestedPage)
            }
        }
            .onFailure { logger.error(it) { "Failed to load strokes for page ${requestedPage + 1}" } }
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

    // ── Selection ────────────────────────────────────────────────────────────
    // Stroke ids rather than the strokes: the selection has to survive the list being
    // rebuilt after every edit, and identity is what the ink is keyed on anyway.
    var selectedIds by remember(documentUri) { mutableStateOf<Set<String>>(emptySet()) }

    /** Rubber-band rectangle during a selection drag, in model units. */
    var selectionRect by remember { mutableStateOf<Rect?>(null) }

    /**
     * The most recent live drag, folded into one history entry on release.
     *
     * A drag writes the database on every frame it moves, so without this the history
     * would gain one entry per frame and a single drag would take dozens of undos to
     * reverse.
     */
    var lastMove by remember { mutableStateOf<Triple<List<StrokeWithPoints>, Float, Float>?>(null) }

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

    fun selectByRect(r: Rect, additive: Boolean) {
        val hits = strokesIntersecting(r).map { it.stroke.id }.toSet()
        selectedIds = if (additive) selectedIds + hits else hits
    }

    /** Bounding box of the current selection, or null when nothing is selected. */
    fun selectionBounds(): Rect? {
        val sel = strokes.filter { it.stroke.id in selectedIds }
        if (sel.isEmpty()) return null
        return Rect(
            sel.minOf { minOf(it.stroke.boundsLeft, it.stroke.boundsRight) },
            sel.minOf { minOf(it.stroke.boundsTop, it.stroke.boundsBottom) },
            sel.maxOf { maxOf(it.stroke.boundsLeft, it.stroke.boundsRight) },
            sel.maxOf { maxOf(it.stroke.boundsTop, it.stroke.boundsBottom) }
        )
    }

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

    /** Commits a live drag as one undoable move. Called repeatedly while dragging. */
    fun moveSelectionBy(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        val moving = strokes.filter { it.stroke.id in selectedIds }
        if (moving.isEmpty()) return
        val before = moving.map { it.copy() }
        val after = translateStrokes(moving, dx, dy)
        runCatching {
            after.forEach { databaseManager.saveStroke(it.stroke, it.points) }
        }.onSuccess {
            strokes = strokes.map { s -> after.firstOrNull { it.stroke.id == s.stroke.id } ?: s }
            lastMove = Triple(before, dx, dy)
            onInkChanged()
        }.onFailure {
            logger.error(it) { "Failed to move ${moving.size} stroke(s)" }
        }
    }

    /** Collapses the drag's incremental writes into one undoable command. */
    fun commitMove() {
        val m = lastMove
        lastMove = null
        if (m == null) return
        undoStack.push(InkEdit.Move(m.first, m.second, m.third))
    }

    /** Deletes the selection as one undoable step. */
    fun deleteSelection() {
        val doomed = strokes.filter { it.stroke.id in selectedIds }
        if (doomed.isEmpty()) return
        runCatching { doomed.forEach { databaseManager.deleteStroke(it.stroke.id) } }
            .onSuccess {
                strokes = strokes - doomed.toSet()
                selectedIds = emptySet()
                undoStack.push(InkEdit.Erase(doomed))
                onInkChanged()
            }
            .onFailure {
                logger.error(it) { "Failed to delete selection" }
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
        if (hit.isEmpty()) return
        runCatching { hit.forEach { databaseManager.deleteStroke(it.stroke.id) } }
            .onSuccess {
                strokes = strokes - hit.toSet()
                undoStack.push(InkEdit.Erase(hit))
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
                onInkChanged()
            }.onFailure {
                logger.error(it) { "Undo failed" }
                // Put it back so history does not claim an undo that did not happen.
                undoStack.push(e)
            }

            is InkEdit.Erase -> runCatching {
                e.strokes.forEach { databaseManager.saveStroke(it.stroke, it.points) }
            }.onSuccess {
                strokes = strokes + e.strokes
                onInkChanged()
            }.onFailure {
                logger.error(it) { "Undo of erase failed" }
                undoStack.push(e)
            }

            // A move is exactly invertible, so undo replays the same translation
            // backwards over the pre-move snapshots.
            is InkEdit.Move -> runCatching {
                translateStrokes(e.originals, -e.dx, -e.dy)
                    .forEach { databaseManager.saveStroke(it.stroke, it.points) }
            }.onSuccess { _ ->
                val back = translateStrokes(e.originals, -e.dx, -e.dy)
                strokes = strokes.map { s -> back.firstOrNull { it.stroke.id == s.stroke.id } ?: s }
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
                onInkChanged()
            }.onFailure {
                logger.error(it) { "Redo failed" }
                undoStack.push(e)
            }

            is InkEdit.Erase -> runCatching {
                e.strokes.forEach { databaseManager.deleteStroke(it.stroke.id) }
            }.onSuccess {
                strokes = strokes - e.strokes.toSet()
                onInkChanged()
            }.onFailure {
                logger.error(it) { "Redo of erase failed" }
                undoStack.push(e)
            }

            is InkEdit.Move -> runCatching {
                translateStrokes(e.originals, e.dx, e.dy)
                    .forEach { databaseManager.saveStroke(it.stroke, it.points) }
            }.onSuccess { _ ->
                val fwd = translateStrokes(e.originals, e.dx, e.dy)
                strokes = strokes.map { s -> fwd.firstOrNull { it.stroke.id == s.stroke.id } ?: s }
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

    val focusRequester = remember { FocusRequester() }

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
                            if (selectedIds.isEmpty()) false else { deleteSelectionNow(); true }
                        }

                        Key.Escape -> {
                            if (selectedIds.isEmpty()) false else { selectedIds = emptySet(); true }
                        }

                        Key.A -> {
                            if (!ctrl || selectedIds.isEmpty()) {
                                false
                            } else {
                                selectedIds = strokes.map { it.stroke.id }.toSet(); true
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
                        if (selecting) {
                            // A press inside the current selection moves it; a press
                            // anywhere else rubber-bands a new one. Decided on the press,
                            // not mid-drag, so a gesture cannot change meaning halfway
                            // through and leave a half-moved selection behind.
                            val box = selectionBoundsNow()
                            val moving = box != null && selectedIds.isNotEmpty() &&
                                start.x >= box.left && start.x <= box.right &&
                                start.y >= box.top && start.y <= box.bottom
                            val idsAtPress = selectedIds
                            val wasMoving = moving
                            var endX = start.x
                            var endY = start.y
                            var additive = false
                            selectionRect = Rect(start.x, start.y, start.x, start.y)

                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.pressed } ?: break
                                val mm = toModel(ch.position) ?: continue
                                additive = ev.keyboardModifiers.isShiftPressed
                                if (moving) {
                                    moveSelectionNow(mm.x - start.x, mm.y - start.y)
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
                    liveStroke?.takeIf { it.isNotEmpty() }?.let { pts ->
                        val screenPts = pts.map {
                            Offset(
                                it.x * scale + (originX - b.originX * scale),
                                it.y * scale + (originY - b.originY * scale)
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
                }
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
        if (editable && rendered != null && loadError == null) {
            InkToolbar(
                tool = tool,
                colour = inkColour,
                onToolChange = { tool = it },
                onColourChange = { inkColour = it },
                canUndo = undoStack.canUndo,
                canRedo = undoStack.canRedo,
                selectionCount = selectedIds.size,
                onUndo = { undoNow() },
                onRedo = { redoNow() },
                onDeleteSelection = { deleteSelectionNow() },
                onClear = {
                    runCatching { databaseManager.deleteStrokesForDocument(documentUri) }
                        .onSuccess {
                            strokes = emptyList()
                            onInkChanged()
                        }
                        .onFailure {
                            logger.error(it) { "Failed to clear ink on ${documentUri.substringAfterLast('/')}" }
                        }
                },
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

/** Which ink tool the toolbar has armed. */
/**
 * What an undoable edit did, in terms the desktop can actually reverse.
 *
 * Only strokes exist here — the desktop has no text or image annotation tables (and
 * the sync protocol carries no verbs for them), so the tablet's far richer
 * `DrawCommand` cannot be reused without copying the parts that do not apply.
 * What *is* shared is `UndoStack`, the history discipline both platforms now follow.
 */
private sealed interface InkEdit {
    /** A finished stroke was inserted. Undo deletes it; redo re-inserts it. */
    data class Add(val stroke: StrokeWithPoints) : InkEdit

    /**
     * One eraser gesture removed these strokes.
     *
     * Grouped per gesture rather than per stroke: a single drag across a paragraph
     * should come back in one press, which is what the tablet's `EraseGesture` does.
     */
    data class Erase(val strokes: List<StrokeWithPoints>) : InkEdit

    /**
     * A selection was dragged by [dx]/[dy] model units.
     *
     * [originals] are snapshots taken before the move, which is what undo restores.
     * A snapshot rather than a reference on purpose: a later edit must not be able to
     * mutate what the history is holding.
     *
     * Stored as a delta rather than an "after" snapshot because a move is exactly
     * invertible — replaying the same translation forward or back cannot drift the way
     * storing two independently-computed states could.
     */
    data class Move(val originals: List<StrokeWithPoints>, val dx: Float, val dy: Float) : InkEdit
}

enum class InkTool { Pen, Highlighter, Eraser, Select }

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
    canUndo: Boolean,
    canRedo: Boolean,
    selectionCount: Int,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDeleteSelection: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(16.dp)
            .glassDressing(isDark = InkThemeState.darkMode, shape = RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        InkToolButton(
            label = "筆",
            selected = tool == InkTool.Pen,
            onClick = { onToolChange(InkTool.Pen) }
        )
        InkToolButton(
            label = "螢光筆",
            selected = tool == InkTool.Highlighter,
            onClick = { onToolChange(InkTool.Highlighter) }
        )
        InkToolButton(
            label = "橡皮擦",
            selected = tool == InkTool.Eraser,
            onClick = { onToolChange(InkTool.Eraser) }
        )
        InkToolButton(
            label = if (selectionCount > 0) "選取($selectionCount)" else "選取",
            selected = tool == InkTool.Select,
            onClick = { onToolChange(InkTool.Select) }
        )
        Spacer(Modifier.width(6.dp))
        InkToolButton(label = "復原", selected = false, enabled = canUndo, onClick = onUndo)
        InkToolButton(label = "重做", selected = false, enabled = canRedo, onClick = onRedo)
        Spacer(Modifier.width(6.dp))
        if (selectionCount > 0) {
            // Delete lives here rather than only on the keyboard: the selection is a
            // mode, and a control that exists only as a shortcut is a control most
            // people never find.
            InkToolButton(label = "刪除選取", selected = false, onClick = onDeleteSelection)
            Spacer(Modifier.width(6.dp))
        }
        INK_PALETTE.forEach { swatch ->
            val chosen = colour == swatch
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(swatch))
                    .then(
                        if (chosen) Modifier.glassDressing(
                            isDark = InkThemeState.darkMode,
                            shape = CircleShape
                        ) else Modifier
                    )
                    .clip(CircleShape)
                    .clickable { onColourChange(swatch) }
            )
        }
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = onClear) {
            Text("清除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun InkToolButton(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = when {
                selected -> MaterialTheme.colorScheme.primary
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

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

/** Matches the tablet's highlighter opacity. */
private const val HIGHLIGHTER_ALPHA = 0.35f

/** Never let ink vanish entirely when zoomed far out. */
private const val MIN_INK_PX = 0.6f

/** Smallest sensible drawn width in model units, mirroring the tablet's floor. */
private const val MIN_INK_PT = 0.05f

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
