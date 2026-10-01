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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.PointEntity
import com.vic.inkflow.data.StrokeWithPoints
import com.vic.inkflow.util.PageBox
import com.vic.inkflow.util.PdfManager
import com.vic.inkflow.util.PdfResult
import com.vic.inkflow.util.RenderedPage
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
    onPageChange: (Int) -> Unit = {}
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

    val focusRequester = remember { FocusRequester() }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {

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

                        else -> false
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
                        val pts = swp.points
                        if (pts.isEmpty()) continue
                        val first = pts.first().toPagePixel(scale, b)
                        val path = Path()
                        path.moveTo(first.x, first.y)
                        for (i in 1 until pts.size) {
                            val q = pts[i].toPagePixel(scale, b)
                            path.lineTo(q.x, q.y)
                        }
                        val s = swp.stroke
                        val color = Color(s.color)
                        drawPath(
                            path = path,
                            color = if (s.isHighlighter) color.copy(alpha = 0.35f) else color,
                            style = Stroke(
                                // Stroke width is in model units too, so it scales with the ink.
                                width = (s.strokeWidth * scale).coerceAtLeast(0.5f),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
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
    }

    // Keyboard navigation needs focus. Requested once per document, so a later click
    // into another panel is not fought over.
    LaunchedEffect(documentUri) {
        runCatching { focusRequester.requestFocus() }
    }
}

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
    Row(
        modifier = modifier
            .padding(12.dp)
            .widthIn(min = 220.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), MaterialTheme.shapes.small),
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
