package com.vic.inkflow.ui


import android.app.Application

import android.content.Context

import android.graphics.Bitmap

import android.graphics.pdf.PdfRenderer

import android.net.Uri

import android.os.SystemClock

import android.os.ParcelFileDescriptor

import androidx.collection.LruCache

import androidx.lifecycle.AndroidViewModel

import androidx.lifecycle.viewModelScope

import androidx.room.withTransaction

import com.vic.inkflow.data.AppDatabase

import com.vic.inkflow.util.PdfManager

import com.vic.inkflow.util.PageOpJournal

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.CoroutineScope

import kotlinx.coroutines.SupervisorJob

import kotlinx.coroutines.cancel

import kotlinx.coroutines.Job

import kotlinx.coroutines.flow.MutableStateFlow

import kotlinx.coroutines.withContext

import kotlinx.coroutines.flow.StateFlow

import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.launch

import kotlinx.coroutines.sync.Mutex

import kotlinx.coroutines.sync.withLock

import kotlinx.coroutines.runBlocking

import java.io.File

class PdfViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** Upper bound for any rendered page bitmap dimension (ARGB_8888 => ~64 MB max). */
        private const val MAX_RENDER_DIMENSION_PX = 4096

        internal fun remapCurrentPageAfterDeletes(
            currentPageIndex: Int,
            deletedIndices: List<Int>,
            pageCountAfter: Int
        ): Int {
            if (deletedIndices.isEmpty()) return currentPageIndex.coerceIn(0, (pageCountAfter - 1).coerceAtLeast(0))
            val nextPage = deletedIndices.sortedDescending().fold(currentPageIndex) { page, deletedIdx ->
                when {
                    page > deletedIdx -> page - 1
                    page == deletedIdx -> (deletedIdx - 1).coerceAtLeast(0)
                    else -> page
                }
            }
            return nextPage.coerceIn(0, (pageCountAfter - 1).coerceAtLeast(0))
        }

        internal fun affectedStartAfterDeletes(
            deletedIndices: List<Int>,
            pageCountAfter: Int
        ): Int? {
            val minDeleted = deletedIndices.minOrNull() ?: return null
            return if (minDeleted in 0 until pageCountAfter) minDeleted else null
        }

        internal fun affectedStartAfterInsert(
            insertionIndex: Int,
            pageCountAfter: Int
        ): Int? {
            return if (insertionIndex in 0 until pageCountAfter) insertionIndex else null
        }

        /**
         * 多份插頁的起始 cursor（第一份要插在誰後面）。
         * 由正規化後的 insertionIndex 倒推，禁拿 afterIndex 直寫＋禁裸 +1：
         * afterIndex==Int.MAX_VALUE 時 cursor 必須是 currentCount-1（接尾），
         * 直接 afterIndex+=n 會溢位成負數（merge 反轉同族）。
         */
        internal fun multiInsertStartCursor(afterIndex: Int, currentCount: Int): Int {
            val insertionIndex =
                if (afterIndex >= currentCount - 1) currentCount else afterIndex + 1
            return insertionIndex - 1
        }
    }

    private val db by lazy { AppDatabase.getDatabase(getApplication()) }

    private var pdfRenderer: PdfRenderer? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private val renderMutex = Mutex()

    private val _pageCount = MutableStateFlow(0)
    val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    /** Incremented every time thumbnails are invalidated (page insert / delete / re-open).
     *  Consumers can key their `remember()` to this value to force re-fetching fresh flows. */
    private val _thumbnailVersion = MutableStateFlow(0)
    val thumbnailVersion: StateFlow<Int> = _thumbnailVersion.asStateFlow()

    private val _isScrollingFast = MutableStateFlow(false)
    val isScrollingFast: StateFlow<Boolean> = _isScrollingFast.asStateFlow()

    private val _currentPdfUri = MutableStateFlow<Uri?>(null)
    val currentPdfUri: StateFlow<Uri?> = _currentPdfUri.asStateFlow()

    /** true 表示有頁面操作（插入／刪除／搬移）正在執行；UI 依此顯示進度並阻擋新的操作。*/
    private val _isPageOperationInProgress = MutableStateFlow(false)
    val isPageOperationInProgress: StateFlow<Boolean> = _isPageOperationInProgress.asStateFlow()

    private val _pageOperationMessage = MutableStateFlow<String?>(null)
    val pageOperationMessage: StateFlow<String?> = _pageOperationMessage.asStateFlow()

    fun consumePageOperationMessage() {
        _pageOperationMessage.value = null
    }

    /** Emits the width ? height (pts) of the first page once the PDF is opened.
     *  UI / EditorViewModel should use this to set the model coordinate space. */
    private val _firstPageSize = MutableStateFlow<Pair<Float, Float>?>(null)
    val firstPageSize: StateFlow<Pair<Float, Float>?> = _firstPageSize.asStateFlow()

    // Emits the index of the newly inserted page so the UI can auto-navigate to it.
    // Resets to null after each consumption.
    private val _lastInsertedPageIndex = MutableStateFlow<Int?>(null)
    val lastInsertedPageIndex: StateFlow<Int?> = _lastInsertedPageIndex.asStateFlow()

    /** Call once after consuming lastInsertedPageIndex to reset the event. */
    fun consumeInsertedPageEvent() { _lastInsertedPageIndex.value = null }

    // Emits all deleted indices (sorted descending) so UI can adjust state after multi-delete.
    private val _lastDeletedPageIndices = MutableStateFlow<List<Int>>(emptyList())
    val lastDeletedPageIndices: StateFlow<List<Int>> = _lastDeletedPageIndices.asStateFlow()

    /** Call once after consuming lastDeletedPageIndices to reset the event. */
    fun consumeDeletedPageEvent() {
        _lastDeletedPageIndices.value = emptyList()
    }

    /**
     * S1 docY 同搬（頁 txn 內呼叫）：只平移受影響區間，不做全表重算。
     * 超大檔全表 backfill 會卡數秒，此處走 (documentUri, docY) 索引，只碰受影響列。
     * stride 取首頁高（與 S1 回填同公式）；未知時跳過（退回舊行為，不更壞）。
     * 邊界列（docY 恰落分隔線，容差外）可能錯一頁，下次開檔全量回填自癒。
     */
    private suspend fun currentStrideOrNull(): Float? =
        _firstPageSize.value?.second?.takeIf { it > 0f }

    /** 插頁：在 insertionIndex 處插入 amount 頁，docY >= idx*stride 整批下移。 */
    private suspend fun shiftDocYForInsertLocked(documentUri: String, insertionIndex: Int, amount: Int) {
        val stride = currentStrideOrNull() ?: return
        val y0 = insertionIndex * stride
        val dy = amount * stride
        db.strokeDao().shiftDocYBelow(documentUri, y0, dy)
        db.textAnnotationDao().shiftDocYBelow(documentUri, y0, dy)
        db.imageAnnotationDao().shiftDocYBelow(documentUri, y0, dy)
    }

    /** 刪頁：第 index 頁的列已先刪除，docY >= (index+1)*stride 上移一頁。 */
    private suspend fun shiftDocYForDeleteLocked(documentUri: String, deletedIndex: Int) {
        val stride = currentStrideOrNull() ?: return
        val y0 = (deletedIndex + 1) * stride
        db.strokeDao().shiftDocYBelow(documentUri, y0, -stride)
        db.textAnnotationDao().shiftDocYBelow(documentUri, y0, -stride)
        db.imageAnnotationDao().shiftDocYBelow(documentUri, y0, -stride)
    }

    /** 移頁：中間區間整批平移一頁，被搬頁跳到目標（兩區間不相交，順序無關）。 */
    private suspend fun shiftDocYForMoveLocked(documentUri: String, fromIndex: Int, toIndex: Int) {
        val stride = currentStrideOrNull() ?: return
        if (fromIndex == toIndex) return
        if (fromIndex < toIndex) {
            // (from, to] 上移一頁：docY [(from+1)*s, (to+1)*s) -= s
            val y0 = (fromIndex + 1) * stride
            val y1 = (toIndex + 1) * stride
            db.strokeDao().shiftDocYRange(documentUri, y0, y1, -stride)
            db.textAnnotationDao().shiftDocYRange(documentUri, y0, y1, -stride)
            db.imageAnnotationDao().shiftDocYRange(documentUri, y0, y1, -stride)
        } else {
            // [to, from) 下移一頁：docY [to*s, from*s) += s
            val y0 = toIndex * stride
            val y1 = fromIndex * stride
            db.strokeDao().shiftDocYRange(documentUri, y0, y1, stride)
            db.textAnnotationDao().shiftDocYRange(documentUri, y0, y1, stride)
            db.imageAnnotationDao().shiftDocYRange(documentUri, y0, y1, stride)
        }
        // 被搬頁 from -> to
        val pageDy = (toIndex - fromIndex) * stride
        db.strokeDao().shiftDocYRange(documentUri, fromIndex * stride, (fromIndex + 1) * stride, pageDy)
        db.textAnnotationDao().shiftDocYRange(documentUri, fromIndex * stride, (fromIndex + 1) * stride, pageDy)
        db.imageAnnotationDao().shiftDocYRange(documentUri, fromIndex * stride, (fromIndex + 1) * stride, pageDy)
    }

    fun getBookmarkedPages(documentUri: String): kotlinx.coroutines.flow.Flow<List<Int>> {
        return db.bookmarkDao().getBookmarkedPages(documentUri)
    }

    fun toggleBookmark(documentUri: String, pageIndex: Int, isBookmarked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (isBookmarked) {
                db.bookmarkDao().insert(com.vic.inkflow.data.BookmarkEntity(documentUri, pageIndex))
            } else {
                db.bookmarkDao().deleteForPage(documentUri, pageIndex)
            }
        }
    }

    // --- Caching ---
    // Bitmaps are intentionally NOT pooled or manually recycled: Compose animations
    // (Crossfade) may hold references to evicted bitmaps for a frame or two, so
    // allowing the GC to collect them is the only safe approach.
    private val bitmapCache: LruCache<Int, Bitmap>
    private val thumbnailCache: LruCache<Int, Bitmap>

    // Stable StateFlow instances so the same object is returned for the same index.
    // This prevents new coroutines being spawned every time Compose re-enters a list item.
    // ConcurrentHashMap: reads (getPageThumbnail on Main) and clears (closeRendererOnly on IO)
    // may race, so we need a thread-safe map.
    private val thumbnailFlowCache = java.util.concurrent.ConcurrentHashMap<Int, MutableStateFlow<Bitmap?>>()
    private val bitmapFlowCache = java.util.concurrent.ConcurrentHashMap<Int, MutableStateFlow<Bitmap?>>()
    /** Stores each page's (widthPt, heightPt) as reported by PdfRenderer. Updated on every open. */
    private val pageSizesMap = java.util.concurrent.ConcurrentHashMap<Int, Pair<Float, Float>>()
    private var pageSizeScanJob: Job? = null

    /**
     * 渲染代際門控（超大檔頁操作防擁塞）：
     * 每次關 renderer（頁操作/重開）就世代+1，並取消整個 renderScope。
     * 等鎖中的舊渲染醒來發現世代變了直接丟棄，不佔鎖不畫圖；
     * 等待中的直接被 cancel，頁操作不用排 37 秒的隊。
     */
    private var renderGen = 0L
    private var renderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 手勢中暫停貼圖投送（治頓挫）：渲染照跑（暖機不停，IO 執行緒無影響），
     * 但完成的新點陣圖先扣著不寫入 flow——手勢中不再有 Crossfade 風暴＋貼圖上傳脈衝。
     * 放手由 Workspace 調 [flushPendingRenders] 一次貼上。plain var＋併發 Map，
     * 不走 StateFlow（避免投送開關本身引發重組）。
     */
    private val rendersPaused = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pendingBitmaps = java.util.concurrent.ConcurrentHashMap<Int, Bitmap>()
    private val pendingThumbs = java.util.concurrent.ConcurrentHashMap<Int, Bitmap>()

    fun setRendersPaused(paused: Boolean) {
        rendersPaused.set(paused)
    }

    /** 放手：解除暫停＋把扣住的點陣圖貼上（已有 fresher 值的 flow 跳過）。 */
    fun flushPendingRenders() {
        rendersPaused.set(false)
        if (pendingBitmaps.isEmpty() && pendingThumbs.isEmpty()) return
        pendingBitmaps.forEach { (index, bmp) ->
            bitmapFlowCache[index]?.let { flow -> if (flow.value == null) flow.value = bmp }
        }
        pendingBitmaps.clear()
        pendingThumbs.forEach { (index, bmp) ->
            thumbnailFlowCache[index]?.let { flow -> if (flow.value == null) flow.value = bmp }
        }
        pendingThumbs.clear()
    }

    private val _pageSizeVersion = MutableStateFlow(0)
    val pageSizeVersion: StateFlow<Int> = _pageSizeVersion.asStateFlow()

    private fun bumpPageSizeVersion() { _pageSizeVersion.value += 1 }

    // 顯示用渲染倍率（密度感知，由 Workspace 按可視寬設定；變更即整批失效重渲）。
    // renderEpoch 供 UI 纳入 remember key，可视页自動重取。
    var displayRenderScale = 2f
        private set
    private val _renderEpoch = MutableStateFlow(0)
    val renderEpoch: StateFlow<Int> = _renderEpoch.asStateFlow()

    fun setDisplayRenderScale(scale: Float) {
        val s = scale.coerceIn(2f, 3f)
        if (s == displayRenderScale) return
        android.util.Log.d("InkFlowDbg", "renderScale $displayRenderScale -> $s")
        displayRenderScale = s
        bitmapCache.evictAll()
        // Fix2c: 不再把 flow 置 null（那會讓可見頁同時變透明、露出黑紙底）。
        // 舊圖繼續頂著顯示（倍率略差但可見），新圖在底下重渲、好了自動換上（Crossfade 接住）。
        bitmapFlowCache.forEach { (index, flow) ->
            if (flow.value != null) {
                renderScope.launch {
                    renderPage(index, highQuality = true)?.let { flow.value = it }
                }
            }
        }
        _renderEpoch.value += 1
    }

    private fun invalidateRenderedFlowsInRange(range: IntRange) {
        for (index in range) {
            thumbnailCache.remove(index)
            val thumbFlow = thumbnailFlowCache[index]
            if (thumbFlow != null) {
                thumbFlow.value = null
                // 超大檔：只重渲有人在看的頁；沒訂閱的置空即可，滑回來取用時補渲。
                if (thumbFlow.subscriptionCount.value > 0) {
                    launchThumbnailRender(thumbFlow, index)
                }
            }

            bitmapCache.remove(index)
            val bitmapFlow = bitmapFlowCache[index]
            if (bitmapFlow != null) {
                bitmapFlow.value = null
                if (bitmapFlow.subscriptionCount.value > 0) {
                    launchBitmapRender(bitmapFlow, index)
                }
            }
        }
    }

    private fun trimFlowCachesToPageCount(pageCount: Int) {
        val overflowThumbKeys = thumbnailFlowCache.keys.filter { it >= pageCount }
        overflowThumbKeys.forEach { key ->
            thumbnailFlowCache.remove(key)
            thumbnailCache.remove(key)
        }

        val overflowBitmapKeys = bitmapFlowCache.keys.filter { it >= pageCount }
        overflowBitmapKeys.forEach { key ->
            bitmapFlowCache.remove(key)
            bitmapCache.remove(key)
        }
    }

    init {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8

        bitmapCache = object : LruCache<Int, Bitmap>(cacheSize) {
            override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
        }

        thumbnailCache = object : LruCache<Int, Bitmap>(maxMemory / 20) {
            override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    /** Returns the aspect ratio (width/height) for [index], or [fallback] when unknown. */
    fun getPageAspectRatio(index: Int, fallback: Float = 595f / 842f): Float {
        val size = pageSizesMap[index] ?: return fallback
        return if (size.second > 0f) size.first / size.second else fallback
    }

    /**
     * Caches the first page size immediately for fast UI response.
     * Must be called while [renderMutex] is held.
     */
    private fun readAndCacheFirstPageSize(renderer: PdfRenderer): Pair<Float, Float>? {
        pageSizesMap.clear()
        if (renderer.pageCount <= 0) return null
        return try {
            renderer.openPage(0).use { page ->
                val w = page.width.toFloat()
                val h = page.height.toFloat()
                if (w > 0f && h > 0f) {
                    val size = Pair(w, h)
                    pageSizesMap[0] = size
                    bumpPageSizeVersion()
                    size
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun scheduleRemainingPageSizeScan(expectedPageCount: Int) {
        pageSizeScanJob?.cancel()
        pageSizeScanJob = null
        if (expectedPageCount <= 1) return

        val expectedUri = _currentPdfUri.value
        pageSizeScanJob = viewModelScope.launch(Dispatchers.IO) {
            var scannedSinceBump = 0
            for (i in 1 until expectedPageCount) {
                if (_currentPdfUri.value != expectedUri) return@launch
                renderMutex.withLock {
                    val renderer = pdfRenderer ?: return@withLock
                    if (i >= renderer.pageCount || pageSizesMap.containsKey(i)) return@withLock
                    try {
                        renderer.openPage(i).use { page ->
                            val w = page.width.toFloat()
                            val h = page.height.toFloat()
                            if (w > 0f && h > 0f) {
                                pageSizesMap[i] = Pair(w, h)
                            }
                        }
                        // 超大檔：每頁都 bump 會引發上千次重組，攢 20 頁刷一次。
                        if (++scannedSinceBump >= 20) {
                            scannedSinceBump = 0
                            bumpPageSizeVersion()
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            if (scannedSinceBump > 0) bumpPageSizeVersion()
        }
    }

    fun openPdf(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            renderMutex.withLock {
                // ViewModel survives configuration changes — skip re-opening if the same PDF is already loaded
                if (_currentPdfUri.value == uri && pdfRenderer != null) return@withLock
                closePdf()
                try {
                    val fd = PdfManager.openPdfFileDescriptor(getApplication(), uri)
                    if (fd == null) {
                        _pageOperationMessage.value = "無法開啟文件，檔案可能已被移動、刪除或沒有權限"
                        return@withLock
                    }
                    val renderer = try {
                        PdfRenderer(fd)
                    } catch (e: SecurityException) {
                        fd.close()
                        _pageOperationMessage.value = "此 PDF 已加密（需要密碼），無法開啟"
                        return@withLock
                    } catch (e: Exception) {
                        fd.close()
                        _pageOperationMessage.value = "PDF 檔案損毀或格式不支援，無法開啟"
                        return@withLock
                    }
                    var firstSize: Pair<Float, Float>? = null
                    parcelFileDescriptor = fd
                    pdfRenderer = renderer
                    _currentPdfUri.value = uri
                    firstSize = readAndCacheFirstPageSize(renderer)
                    if (renderer.pageCount <= 0) {
                        _pageOperationMessage.value = "此 PDF 沒有可顯示的頁面"
                    }
                    _pageCount.value = renderer.pageCount
                    _firstPageSize.value = firstSize
                    scheduleRemainingPageSizeScan(renderer.pageCount)
                } catch (e: Exception) {
                    android.util.Log.e("PdfViewModel", "Failed to open PDF: $uri", e)
                    _pageOperationMessage.value = "開啟文件失敗，請再試一次"
                }
            }
        }
    }

    /** 關閉 Renderer，並視情況清除目前 URI 與 pageCount，避免殘留舊資料。*/
    private fun closeRendererOnly(clearFlows: Boolean = true) {
        pageSizeScanJob?.cancel()
        pageSizeScanJob = null
        // 先換代際＋取消渲染域：排隊等鎖的舊渲染醒來即棄，不用等它們畫完。
        renderGen++
        renderScope.cancel()
        renderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        pdfRenderer?.close()
        parcelFileDescriptor?.close()
        pdfRenderer = null
        parcelFileDescriptor = null
        bitmapCache.evictAll()
        thumbnailCache.evictAll()
        if (clearFlows) {
            thumbnailFlowCache.clear()
            bitmapFlowCache.clear()
        }
    }

    /** 清空 Main 執行緒的 flow cache；之後重新呼叫 getPageThumbnail/getPageBitmap 即可重建。*/
    private fun clearFlowCaches() {
        thumbnailFlowCache.clear()
        bitmapFlowCache.clear()
    }

    /** 在目前 PDF（file:// URI）的 [afterIndex] 頁之後插入一頁空白頁，尺寸預設 A4 直向。
     *  afterIndex = -1 ??Int.MAX_VALUE ?蕭??怠偏??
     *
      *  採用樂觀更新（Optimistic UI）策略：
      *  - 先更新 _pageCount 與 _lastInsertedPageIndex，讓 UI 立即導向新頁面。
      *  - 實際的 PDDocument 寫入在背景進行，通常耗時約 2 秒。
      *  - 完成後重新開啟 Renderer，並遞增 _thumbnailVersion 使縮圖快取失效。
      *  - 失敗時會回滾並提示錯誤。
     */
    fun insertBlankPage(
        context: Context,
        documentUri: String,
        afterIndex: Int,
        pageWidthPt: Float = com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4.width,
        pageHeightPt: Float = com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4.height
    ) {
        val fileUri = _currentPdfUri.value ?: return
        if (fileUri.scheme != "file") {
            android.util.Log.w("PdfViewModel", "insertBlankPage: only file:// URIs supported")
            return
        }
        if (_isPageOperationInProgress.value) {
            _pageOperationMessage.value = "頁面操作進行中，請稍後再試"
            return
        }
        val currentCount = _pageCount.value
        // 計算新頁的樂觀索引：afterIndex 為 Int.MAX_VALUE 時直接附加到最後。
        val optimisticNewIndex = if (afterIndex == Int.MAX_VALUE || afterIndex >= currentCount - 1) {
            currentCount  // 新頁的索引 = currentCount（附加到結尾）
        } else {
            afterIndex + 1
        }.coerceIn(0, currentCount)

        // 先同步更新狀態，讓 UI 在 PDF 寫檔期間就能反應新的頁數；
        // 若寫入尚未完成，對應縮圖可能短暫呈現 null 或舊圖，屬預期現象。
        _isPageOperationInProgress.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val tOp0 = System.currentTimeMillis()
            var backup: java.io.File? = null
            try {
                val targetFile = File(fileUri.path!!)
                backup = PageOpJournal.backupFile(getApplication(), targetFile)
                val tAfterBackupMs = System.currentTimeMillis() - tOp0
                if (backup == null) {
                    _pageOperationMessage.value = "無法建立操作備份，已取消"
                    return@launch
                }
                val entry = PageOpJournal.Entry(
                    op = "insert", documentUri = documentUri,
                    indices = listOf(optimisticNewIndex), count = 1, toIndex = -1,
                    pageCountBefore = currentCount, stage = "prepared"
                )
                PageOpJournal.write(getApplication(), entry)

                renderMutex.withLock { closeRendererOnly(clearFlows = false) }
                val tFile0 = System.currentTimeMillis()
                val ok = com.vic.inkflow.util.PdfManager.insertBlankPage(fileUri, afterIndex, pageWidthPt, pageHeightPt)
                val tFileMs = System.currentTimeMillis() - tFile0

                if (!ok) {
                    PageOpJournal.restoreBackup(getApplication(), backup, targetFile)
                    PageOpJournal.clear(getApplication())
                    _pageOperationMessage.value = "新增頁面失敗，請再試一次"
                    reopenCurrentPdf(fileUri, fallbackPageCount = currentCount)
                    return@launch
                }

                PageOpJournal.markFileDone(getApplication(), entry)
                val tDb0 = System.currentTimeMillis()
                db.withTransaction {
                    db.strokeDao().shiftPageIndicesUp(documentUri, optimisticNewIndex, 1)
                    db.textAnnotationDao().shiftPageIndicesUp(documentUri, optimisticNewIndex, 1)
                    db.imageAnnotationDao().shiftPageIndicesUp(documentUri, optimisticNewIndex, 1)
                    db.bookmarkDao().shiftPageIndicesUp(documentUri, optimisticNewIndex, 1)
                    db.mathSourceDao().shiftPageIndicesUp(documentUri, optimisticNewIndex, 1)
                    shiftDocYForInsertLocked(documentUri, optimisticNewIndex, 1)
                }
                val tDbMs = System.currentTimeMillis() - tDb0
                android.util.Log.d("PdfViewModel", "insertBlankPage perf: backup=${tAfterBackupMs}ms file=${tFileMs}ms db=${tDbMs}ms")
                PageOpJournal.clear(getApplication())
                PageOpJournal.deleteBackup(getApplication(), backup)

                _lastInsertedPageIndex.value = optimisticNewIndex
                reopenCurrentPdfAfterInsert(
                    fileUri = fileUri,
                    fallbackPageCount = currentCount + 1,
                    insertionIndex = optimisticNewIndex
                )
            } catch (e: Exception) {
                android.util.Log.e("PdfViewModel", "insertBlankPage failed", e)
                val targetFile = File(fileUri.path!!)
                if (backup != null) PageOpJournal.restoreBackup(getApplication(), backup, targetFile)
                PageOpJournal.clear(getApplication())
                _pageOperationMessage.value = "新增頁面失敗，請再試一次"
                reopenCurrentPdf(fileUri, fallbackPageCount = currentCount)
            } finally {
                _isPageOperationInProgress.value = false
            }
        }
    }

    fun insertPdfPages(
        context: Context,
        documentUri: String,
        sourceUri: Uri,
        afterIndex: Int
    ) {
        if (_isPageOperationInProgress.value) {
            _pageOperationMessage.value = "頁面操作進行中，請稍後再試"
            return
        }

        val targetFileUri = _currentPdfUri.value ?: return
        if (targetFileUri.scheme != "file") {
            android.util.Log.w("PdfViewModel", "insertPdfPages: only file:// target URIs supported")
            return
        }

        _isPageOperationInProgress.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val tOp0 = System.currentTimeMillis()
            val localSourceUri = PdfManager.copyPdfToAppDir(context, sourceUri)
            if (localSourceUri == null) {
                _pageOperationMessage.value = "匯入 PDF 失敗，請再試一次"
                _isPageOperationInProgress.value = false
                return@launch
            }

            val insertedPageCount = PdfManager.getPdfPageCount(localSourceUri)
            if (insertedPageCount <= 0) {
                _pageOperationMessage.value = "匯入 PDF 沒有可新增的頁面"
                _isPageOperationInProgress.value = false
                return@launch
            }

            val currentCount = _pageCount.value
            val insertionIndex = if (afterIndex >= currentCount - 1) currentCount else afterIndex + 1
            android.util.Log.d(
                "PdfViewModel",
                "insertPdfPages start: currentCount=$currentCount, afterIndex=$afterIndex, insertionIndex=$insertionIndex, inserted=$insertedPageCount"
            )

            var backup: java.io.File? = null
            try {
                val targetFile = File(targetFileUri.path!!)
                backup = PageOpJournal.backupFile(getApplication(), targetFile)
                if (backup == null) {
                    _pageOperationMessage.value = "無法建立操作備份，已取消"
                    return@launch
                }
                val entry = PageOpJournal.Entry(
                    op = "insert", documentUri = documentUri,
                    indices = listOf(insertionIndex), count = insertedPageCount, toIndex = -1,
                    pageCountBefore = currentCount, stage = "prepared"
                )
                PageOpJournal.write(getApplication(), entry)

                renderMutex.withLock { closeRendererOnly(clearFlows = false) }
                val tFile0 = System.currentTimeMillis()
                val merged = PdfManager.insertPdfPages(targetFileUri, localSourceUri, afterIndex)
                val tFileMs = System.currentTimeMillis() - tFile0
                if (!merged) {
                    PageOpJournal.restoreBackup(getApplication(), backup, targetFile)
                    PageOpJournal.clear(getApplication())
                    _pageOperationMessage.value = "匯入 PDF 失敗，請再試一次"
                    reopenCurrentPdf(targetFileUri, fallbackPageCount = currentCount)
                    return@launch
                }

                PageOpJournal.markFileDone(getApplication(), entry)
                val tDb0 = System.currentTimeMillis()
                db.withTransaction {
                    db.strokeDao().shiftPageIndicesUp(documentUri, insertionIndex, insertedPageCount)
                    db.textAnnotationDao().shiftPageIndicesUp(documentUri, insertionIndex, insertedPageCount)
                    db.imageAnnotationDao().shiftPageIndicesUp(documentUri, insertionIndex, insertedPageCount)
                    db.bookmarkDao().shiftPageIndicesUp(documentUri, insertionIndex, insertedPageCount)
                    db.mathSourceDao().shiftPageIndicesUp(documentUri, insertionIndex, insertedPageCount)
                    shiftDocYForInsertLocked(documentUri, insertionIndex, insertedPageCount)
                }
                val tDbMs = System.currentTimeMillis() - tDb0
                android.util.Log.d("PdfViewModel", "insertPdfPages perf: total=${System.currentTimeMillis() - tOp0}ms file=${tFileMs}ms db=${tDbMs}ms")
                PageOpJournal.clear(getApplication())
                PageOpJournal.deleteBackup(getApplication(), backup)

                _lastInsertedPageIndex.value = insertionIndex
                reopenCurrentPdfAfterInsert(
                    fileUri = targetFileUri,
                    fallbackPageCount = currentCount + insertedPageCount,
                    insertionIndex = insertionIndex
                )
            } catch (e: Exception) {
                android.util.Log.e("PdfViewModel", "insertPdfPages failed", e)
                val targetFile = File(targetFileUri.path!!)
                if (backup != null) PageOpJournal.restoreBackup(getApplication(), backup, targetFile)
                PageOpJournal.clear(getApplication())
                _pageCount.value = currentCount
                _lastInsertedPageIndex.value = null
                _pageOperationMessage.value = "匯入 PDF 失敗，請再試一次"
                reopenCurrentPdf(targetFileUri, fallbackPageCount = currentCount)
            } finally {
                runCatching {
                    localSourceUri.path?.let { File(it).delete() }
                }
                _isPageOperationInProgress.value = false
            }
        }
    }

    /**
     * 將多份 PDF 按 [sourceUris] 順序插到 [afterIndex] 頁後方（單次互斥、單份備份、
     * 索引一次平移、一次重開）。第一份失敗即整批回滾；拷貝/空檔等瑣碎失敗跳過該份
     * 繼續（最後報數）。單份請繼續用 [insertPdfPages]，行為零變化。
     */
    fun insertMultiplePdfs(
        context: Context,
        documentUri: String,
        sourceUris: List<Uri>,
        afterIndex: Int
    ) {
        if (_isPageOperationInProgress.value) {
            _pageOperationMessage.value = "頁面操作進行中，請稍後再試"
            return
        }

        val targetFileUri = _currentPdfUri.value ?: return
        if (targetFileUri.scheme != "file") {
            android.util.Log.w("PdfViewModel", "insertMultiplePdfs: only file:// target URIs supported")
            return
        }

        _isPageOperationInProgress.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val tOp0 = System.currentTimeMillis()
            // 逐份拷貝＋算頁數，壞的跳過。
            val locals = mutableListOf<Pair<Uri, Int>>()
            var skippedCount = 0
            for (sourceUri in sourceUris) {
                val local = PdfManager.copyPdfToAppDir(context, sourceUri)
                if (local == null) {
                    skippedCount++
                    continue
                }
                val count = PdfManager.getPdfPageCount(local)
                if (count <= 0) {
                    runCatching { local.path?.let { File(it).delete() } }
                    skippedCount++
                    continue
                }
                locals.add(local to count)
            }
            if (locals.isEmpty()) {
                _pageOperationMessage.value = "匯入 PDF 失敗，請再試一次"
                _isPageOperationInProgress.value = false
                return@launch
            }

            val totalInserted = locals.sumOf { it.second }
            val currentCount = _pageCount.value
            val insertionIndex = if (afterIndex >= currentCount - 1) currentCount else afterIndex + 1

            var backup: java.io.File? = null
            try {
                val targetFile = File(targetFileUri.path!!)
                backup = PageOpJournal.backupFile(getApplication(), targetFile)
                if (backup == null) {
                    _pageOperationMessage.value = "無法建立操作備份，已取消"
                    return@launch
                }
                val entry = PageOpJournal.Entry(
                    op = "insert", documentUri = documentUri,
                    indices = listOf(insertionIndex), count = totalInserted, toIndex = -1,
                    pageCountBefore = currentCount, stage = "prepared"
                )
                PageOpJournal.write(getApplication(), entry)

                renderMutex.withLock { closeRendererOnly(clearFlows = false) }
                val tFile0 = System.currentTimeMillis()
                // 從正規化後的 insertionIndex 倒推（見 multiInsertStartCursor）：
                // afterIndex==MAX_VALUE 時接尾，禁拿 MAX 去 +=（溢位負數）。
                var cursor = multiInsertStartCursor(afterIndex, currentCount)
                for ((localUri, filePageCount) in locals) {
                    val merged = PdfManager.insertPdfPages(targetFileUri, localUri, cursor)
                    if (!merged) {
                        PageOpJournal.restoreBackup(getApplication(), backup, targetFile)
                        PageOpJournal.clear(getApplication())
                        _pageOperationMessage.value = "匯入 PDF 失敗，請再試一次"
                        reopenCurrentPdf(targetFileUri, fallbackPageCount = currentCount)
                        return@launch
                    }
                    cursor += filePageCount
                }

                PageOpJournal.markFileDone(getApplication(), entry)
                val tFileMs = System.currentTimeMillis() - tFile0
                val tDb0 = System.currentTimeMillis()
                db.withTransaction {
                    db.strokeDao().shiftPageIndicesUp(documentUri, insertionIndex, totalInserted)
                    db.textAnnotationDao().shiftPageIndicesUp(documentUri, insertionIndex, totalInserted)
                    db.imageAnnotationDao().shiftPageIndicesUp(documentUri, insertionIndex, totalInserted)
                    db.bookmarkDao().shiftPageIndicesUp(documentUri, insertionIndex, totalInserted)
                    db.mathSourceDao().shiftPageIndicesUp(documentUri, insertionIndex, totalInserted)
                    shiftDocYForInsertLocked(documentUri, insertionIndex, totalInserted)
                }
                val tDbMs = System.currentTimeMillis() - tDb0
                android.util.Log.d("PdfViewModel", "insertMultiplePdfs perf: total=${System.currentTimeMillis() - tOp0}ms file=${tFileMs}ms db=${tDbMs}ms")
                PageOpJournal.clear(getApplication())
                PageOpJournal.deleteBackup(getApplication(), backup)

                _lastInsertedPageIndex.value = insertionIndex
                reopenCurrentPdfAfterInsert(
                    fileUri = targetFileUri,
                    fallbackPageCount = currentCount + totalInserted,
                    insertionIndex = insertionIndex
                )
                if (skippedCount > 0) {
                    _pageOperationMessage.value = "已匯入 ${locals.size} 份，另有 $skippedCount 份無法讀取已跳過"
                }
            } catch (e: Exception) {
                android.util.Log.e("PdfViewModel", "insertMultiplePdfs failed", e)
                val targetFile = File(targetFileUri.path!!)
                if (backup != null) PageOpJournal.restoreBackup(getApplication(), backup, targetFile)
                PageOpJournal.clear(getApplication())
                _pageCount.value = currentCount
                _lastInsertedPageIndex.value = null
                _pageOperationMessage.value = "匯入 PDF 失敗，請再試一次"
                reopenCurrentPdf(targetFileUri, fallbackPageCount = currentCount)
            } finally {
                for ((localUri, _) in locals) {
                    runCatching { localUri.path?.let { File(it).delete() } }
                }
                _isPageOperationInProgress.value = false
            }
        }
    }



    /** 
      * 將 PDF 的第 [fromIndex] 頁搬移到 [toIndex]。
      * 先搬移 PDF 頁面，再於同一交易內更新所有 annotations 的頁碼索引。
     */
    fun movePage(documentUri: String, fromIndex: Int, toIndex: Int) {
        val fileUri = _currentPdfUri.value ?: return
        if (fileUri.scheme != "file") return
        if (_isPageOperationInProgress.value) {
            _pageOperationMessage.value = "頁面操作進行中，請稍後再試"
            return
        }
        if (fromIndex == toIndex) return
        val currentCount = _pageCount.value
        if (fromIndex !in 0 until currentCount || toIndex !in 0 until currentCount) return

        _isPageOperationInProgress.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val targetFile = File(fileUri.path!!)
            val backup = PageOpJournal.backupFile(getApplication(), targetFile)
            if (backup == null) {
                _pageOperationMessage.value = "無法建立操作備份，已取消"
                _isPageOperationInProgress.value = false
                return@launch
            }
            val entry = PageOpJournal.Entry(
                op = "move", documentUri = documentUri,
                indices = listOf(fromIndex), count = 1, toIndex = toIndex,
                pageCountBefore = currentCount, stage = "prepared"
            )
            PageOpJournal.write(getApplication(), entry)

            val ok = runCatching {
                val tOp0 = System.currentTimeMillis()
                renderMutex.withLock { closeRendererOnly() }

                // 1. Move page in PDF file
                val tFile0 = System.currentTimeMillis()
                val moved = com.vic.inkflow.util.PdfManager.movePage(fileUri, fromIndex, toIndex)
                val tFileMs = System.currentTimeMillis() - tFile0

                if (moved) {
                    PageOpJournal.markFileDone(getApplication(), entry)

                    // 2. Transact DB index updates
                    val tDb0 = System.currentTimeMillis()
                    db.withTransaction {
                    // Update StrokeDao
                    with(db.strokeDao()) {
                        moveToTempIndex(documentUri, fromIndex, -1)
                        if (fromIndex < toIndex) shiftForMoveDown(documentUri, fromIndex, toIndex)
                        else shiftForMoveUp(documentUri, fromIndex, toIndex)
                        moveToTempIndex(documentUri, -1, toIndex)
                    }
                    // Update TextAnnotationDao
                    with(db.textAnnotationDao()) {
                        moveToTempIndex(documentUri, fromIndex, -1)
                        if (fromIndex < toIndex) shiftForMoveDown(documentUri, fromIndex, toIndex)
                        else shiftForMoveUp(documentUri, fromIndex, toIndex)
                        moveToTempIndex(documentUri, -1, toIndex)
                    }
                    // Update ImageAnnotationDao
                    with(db.imageAnnotationDao()) {
                        moveToTempIndex(documentUri, fromIndex, -1)
                        if (fromIndex < toIndex) shiftForMoveDown(documentUri, fromIndex, toIndex)
                        else shiftForMoveUp(documentUri, fromIndex, toIndex)
                        moveToTempIndex(documentUri, -1, toIndex)
                    }
                    // Update BookmarkDao
                    with(db.bookmarkDao()) {
                        moveToTempIndex(documentUri, fromIndex, -1)
                        if (fromIndex < toIndex) shiftForMoveDown(documentUri, fromIndex, toIndex)
                        else shiftForMoveUp(documentUri, fromIndex, toIndex)
                        moveToTempIndex(documentUri, -1, toIndex)
                    }
                    // Update MathSourceDao（公式 TeX 跟頁走）
                    with(db.mathSourceDao()) {
                        moveToTempIndex(documentUri, fromIndex, -1)
                        if (fromIndex < toIndex) shiftForMoveDown(documentUri, fromIndex, toIndex)
                        else shiftForMoveUp(documentUri, fromIndex, toIndex)
                        moveToTempIndex(documentUri, -1, toIndex)
                    }
                    shiftDocYForMoveLocked(documentUri, fromIndex, toIndex)
                }
                    val tDbMs = System.currentTimeMillis() - tDb0
                    android.util.Log.d("PdfViewModel", "movePage perf: total=${System.currentTimeMillis() - tOp0}ms file=${tFileMs}ms db=${tDbMs}ms")
                } else {
                    PageOpJournal.restoreBackup(getApplication(), backup, targetFile)
                }
                true
            }.getOrElse {
                android.util.Log.e("PdfViewModel", "movePage failed", it)
                PageOpJournal.restoreBackup(getApplication(), backup, targetFile)
                false
            }
            PageOpJournal.clear(getApplication())

            // 3. Reopen PDF
            try {
                val fd = ParcelFileDescriptor.open(
                    File(fileUri.path!!),
                    ParcelFileDescriptor.MODE_READ_ONLY
                )
                val renderer = try {
                    PdfRenderer(fd)
                } catch (e: Exception) {
                    fd.close()
                    _isPageOperationInProgress.value = false
                    return@launch
                }
                var firstSize: Pair<Float, Float>? = null
                renderMutex.withLock {
                    parcelFileDescriptor = fd
                    pdfRenderer = renderer
                    firstSize = readAndCacheFirstPageSize(renderer)
                }
                _pageCount.value = renderer.pageCount
                _firstPageSize.value = firstSize
                scheduleRemainingPageSizeScan(renderer.pageCount)
                _thumbnailVersion.value++ // ? UI ??渡?蝮桀?
            } catch (e: Exception) {
                android.util.Log.e("PdfViewModel", "movePage reopen failed", e)
            } finally {
                _isPageOperationInProgress.value = false
            }
        }
    }

        /**
      * 刪除多個頁面。
      * @param documentUri 文件的內部 file:// URI。
      * @param pageIndices 要刪除的頁面索引清單。
     */
    fun deletePages(documentUri: String, pageIndices: List<Int>) {
        val fileUri = _currentPdfUri.value ?: return
        if (fileUri.scheme != "file") {
            _pageOperationMessage.value = "目前只支援刪除 app 內部 PDF 頁面"
            return
        }
        val sortedIndices = pageIndices.distinct().sortedDescending()
        if (sortedIndices.isEmpty()) {
            _pageOperationMessage.value = "請先選擇要刪除的頁面"
            return
        }
        val currentCount = _pageCount.value
        // 防呆：至少需要保留 1 頁。
        if (currentCount <= sortedIndices.size) {
            _pageOperationMessage.value = "至少需要保留 1 頁"
            return
        }
        if (_isPageOperationInProgress.value) {
            _pageOperationMessage.value = "頁面操作進行中，請稍後再試"
            return
        }

        _isPageOperationInProgress.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val targetFile = File(fileUri.path!!)
            val backup = PageOpJournal.backupFile(getApplication(), targetFile)
            if (backup == null) {
                _pageOperationMessage.value = "無法建立操作備份，已取消"
                _isPageOperationInProgress.value = false
                return@launch
            }
            val entry = PageOpJournal.Entry(
                op = "delete", documentUri = documentUri,
                indices = sortedIndices, count = sortedIndices.size, toIndex = -1,
                pageCountBefore = currentCount, stage = "prepared"
            )
            PageOpJournal.write(getApplication(), entry)

            val ok = runCatching {
                val tOp0 = System.currentTimeMillis()
                val tLock0 = System.currentTimeMillis()
                renderMutex.withLock { closeRendererOnly(clearFlows = false) }
                val tLockMs = System.currentTimeMillis() - tLock0
            
            // ???格活摨惜 I/O ?寞活?芷????撖阡? PDF ?
                val tFile0 = System.currentTimeMillis()
                val deleted = com.vic.inkflow.util.PdfManager.deletePages(fileUri, sortedIndices)
                val tFileMs = System.currentTimeMillis() - tFile0

                if (deleted) {
                    PageOpJournal.markFileDone(getApplication(), entry)
                // 同步下修所有 annotation 的頁碼索引。
                val tDb0 = System.currentTimeMillis()
                db.withTransaction {
                    for (index in sortedIndices) {
                        with(db.strokeDao()) {
                            clearPage(documentUri, index)
                            shiftPageIndicesDown(documentUri, index)
                        }
                        with(db.textAnnotationDao()) {
                            deleteForPage(documentUri, index)
                            shiftPageIndicesDown(documentUri, index)
                        }
                        with(db.imageAnnotationDao()) {
                            deleteForPage(documentUri, index)
                            shiftPageIndicesDown(documentUri, index)
                        }
                        with(db.bookmarkDao()) {
                            deleteForPage(documentUri, index)
                            shiftPageIndicesDown(documentUri, index)
                        }
                        with(db.mathSourceDao()) {
                            deleteForPage(documentUri, index)
                            shiftPageIndicesDown(documentUri, index)
                        }
                        shiftDocYForDeleteLocked(documentUri, index)
                    }
                    PageOpJournal.deleteBackup(getApplication(), backup)
                }
                    val tDbMs = System.currentTimeMillis() - tDb0
                    android.util.Log.d("PdfViewModel", "deletePages perf: total=${System.currentTimeMillis() - tOp0}ms lock=${tLockMs}ms file=${tFileMs}ms db=${tDbMs}ms pages=${sortedIndices.size}")
                } else {
                    PageOpJournal.restoreBackup(getApplication(), backup, targetFile)
                    _pageOperationMessage.value = "刪除頁面失敗，請再試一次"
                }
                deleted
            }.getOrElse {
                android.util.Log.e("PdfViewModel", "deletePages failed", it)
                PageOpJournal.restoreBackup(getApplication(), backup, targetFile)
                _pageOperationMessage.value = "刪除頁面失敗，請再試一次"
                false
            }
            PageOpJournal.clear(getApplication())

            
            // Reopen PDF
            try {
                val fd = ParcelFileDescriptor.open(
                    File(fileUri.path!!),
                    ParcelFileDescriptor.MODE_READ_ONLY
                )
                val renderer = try {
                    PdfRenderer(fd)
                } catch (e: Exception) {
                    fd.close()
                    _pageOperationMessage.value = "刪除後重載 PDF 失敗"
                    _isPageOperationInProgress.value = false
                    return@launch
                }
                var firstSize: Pair<Float, Float>? = null
                renderMutex.withLock {
                    parcelFileDescriptor = fd
                    pdfRenderer = renderer
                    firstSize = readAndCacheFirstPageSize(renderer)
                }
                _pageCount.value = renderer.pageCount
                _firstPageSize.value = firstSize
                scheduleRemainingPageSizeScan(renderer.pageCount)
                if (ok) {
                    trimFlowCachesToPageCount(renderer.pageCount)
                    affectedStartAfterDeletes(sortedIndices, renderer.pageCount)?.let { startIndex ->
                        invalidateRenderedFlowsInRange(startIndex..(renderer.pageCount - 1))
                    }
                    _lastDeletedPageIndices.value = sortedIndices
                } else {
                    _thumbnailVersion.value++
                }
                android.util.Log.d("PdfViewModel", "deletePages completed, deleted=${sortedIndices.size}")
            } catch (e: Exception) {
                android.util.Log.e("PdfViewModel", "deletePages reopen failed", e)
                _pageOperationMessage.value = "刪除後重載 PDF 失敗"
            } finally {
                _isPageOperationInProgress.value = false
            }
        }
    }

    private fun closePdf() {
        pageSizeScanJob?.cancel()
        pageSizeScanJob = null
        _currentPdfUri.value = null
        pdfRenderer?.close()
        parcelFileDescriptor?.close()
        pdfRenderer = null
        parcelFileDescriptor = null
        _pageCount.value = 0
        bitmapCache.evictAll()
        thumbnailCache.evictAll()
        thumbnailFlowCache.clear()
        bitmapFlowCache.clear()
    }

    private suspend fun reopenCurrentPdf(fileUri: Uri, fallbackPageCount: Int) {
        try {
            val fd = ParcelFileDescriptor.open(
                File(fileUri.path!!),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: Exception) {
                fd.close()
                _pageCount.value = fallbackPageCount
                return
            }
            var firstSize: Pair<Float, Float>? = null
            renderMutex.withLock {
                parcelFileDescriptor = fd
                pdfRenderer = renderer
                firstSize = readAndCacheFirstPageSize(renderer)
            }
            clearFlowCaches()
            _pageCount.value = renderer.pageCount
            _firstPageSize.value = firstSize
            scheduleRemainingPageSizeScan(renderer.pageCount)
            _thumbnailVersion.value++
        } catch (e: Exception) {
            android.util.Log.e("PdfViewModel", "reopenCurrentPdf failed", e)
            _pageCount.value = fallbackPageCount
        }
    }

    private suspend fun reopenCurrentPdfAfterInsert(
        fileUri: Uri,
        fallbackPageCount: Int,
        insertionIndex: Int
    ) {
        try {
            val fd = ParcelFileDescriptor.open(
                File(fileUri.path!!),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: Exception) {
                fd.close()
                _pageCount.value = fallbackPageCount
                return
            }
            var firstSize: Pair<Float, Float>? = null
            renderMutex.withLock {
                parcelFileDescriptor = fd
                pdfRenderer = renderer
                firstSize = readAndCacheFirstPageSize(renderer)
            }
            trimFlowCachesToPageCount(renderer.pageCount)
            _pageCount.value = renderer.pageCount
            _firstPageSize.value = firstSize
            scheduleRemainingPageSizeScan(renderer.pageCount)

            affectedStartAfterInsert(insertionIndex, renderer.pageCount)?.let { startIndex ->
                invalidateRenderedFlowsInRange(startIndex..(renderer.pageCount - 1))
            }
            _thumbnailVersion.value++
        } catch (e: Exception) {
            android.util.Log.e("PdfViewModel", "reopenCurrentPdfAfterInsert failed", e)
            _pageCount.value = fallbackPageCount
        }
    }

    fun getPageBitmap(pageIndex: Int): StateFlow<Bitmap?> {
        val existing = bitmapFlowCache[pageIndex]
        if (existing != null) {
            // F2：失效後被置空的非訂閱 flow，被重新取用時補發渲染（否則永久黑頁）。
            if (existing.value == null && bitmapCache[pageIndex] == null) {
                launchBitmapRender(existing, pageIndex)
            }
            return existing
        }
        val flow = MutableStateFlow(bitmapCache[pageIndex])
        bitmapFlowCache[pageIndex] = flow
        if (flow.value == null) {
            launchBitmapRender(flow, pageIndex)
        }
        return flow
    }

    private fun launchBitmapRender(flow: MutableStateFlow<Bitmap?>, pageIndex: Int) {
        renderScope.launch {
            // F2 黑頁修復：剛插頁重開空窗期 pdfRenderer 為 null，這次必回 null；
            // 以前只試一次就永久黑，現在退避重試（flow 留空等重試，不毒化快取）。
            repeat(4) { attempt ->
                val bmp = renderPage(pageIndex, highQuality = true)
                if (bmp != null) {
                    if (rendersPaused.get()) pendingBitmaps[pageIndex] = bmp
                    else flow.value = bmp
                    return@launch
                }
                android.util.Log.d("PdfViewModel", "getPageBitmap retry $attempt page=$pageIndex")
                kotlinx.coroutines.delay(600L * (attempt + 1))
            }
        }
    }

    fun getPageThumbnail(pageIndex: Int): StateFlow<Bitmap?> {
        val existing = thumbnailFlowCache[pageIndex]
        if (existing != null) {
            if (existing.value == null && thumbnailCache[pageIndex] == null) {
                launchThumbnailRender(existing, pageIndex)
            }
            return existing
        }
        val flow = MutableStateFlow(thumbnailCache[pageIndex])
        thumbnailFlowCache[pageIndex] = flow
        if (flow.value == null) {
            launchThumbnailRender(flow, pageIndex)
        }
        return flow
    }

    private fun launchThumbnailRender(flow: MutableStateFlow<Bitmap?>, pageIndex: Int) {
        renderScope.launch {
            renderPage(pageIndex, highQuality = false)?.let {
                if (rendersPaused.get()) pendingThumbs[pageIndex] = it
                else flow.value = it
            }
        }
    }

    fun prefetchPage(pageIndex: Int) {
        if (pageIndex < 0 || pageIndex >= pageCount.value) return
        if (bitmapCache[pageIndex] != null) return

        renderScope.launch {
            renderPage(pageIndex, highQuality = true)
        }
    }
    
    fun setScrollingFast(isFast: Boolean) {
        _isScrollingFast.value = isFast
    }

    private suspend fun renderPage(pageIndex: Int, highQuality: Boolean): Bitmap? {
        if (pageIndex < 0 || pageIndex >= _pageCount.value) return null

        val cache = if (highQuality) bitmapCache else thumbnailCache
        cache[pageIndex]?.let { return it }

        return withContext(Dispatchers.IO) {
            val gen = renderGen
            renderMutex.withLock {
                // 頁操作已換代：舊渲染直接丟棄，不畫不佔鎖。
                if (gen != renderGen) return@withLock null
                pdfRenderer?.let { renderer ->
                    try {
                        val page = renderer.openPage(pageIndex)
                        val scale = if (highQuality) displayRenderScale else 0.4f
                        // For thumbnails, cap width at 240 px to keep memory reasonable
                        val rawW = (page.width * scale).toInt()
                        val rawH = (page.height * scale).toInt()
                        val width: Int
                        val height: Int
                        if (!highQuality && rawW > 240) {
                            width = 240
                            height = (rawH * 240f / rawW).toInt().coerceAtLeast(1)
                        } else if (rawW > MAX_RENDER_DIMENSION_PX || rawH > MAX_RENDER_DIMENSION_PX) {
                            // Cap huge-format pages (posters/blueprints) to avoid OOM
                            val down = MAX_RENDER_DIMENSION_PX.toFloat() / maxOf(rawW, rawH)
                            width = (rawW * down).toInt().coerceAtLeast(1)
                            height = (rawH * down).toInt().coerceAtLeast(1)
                        } else {
                            width = rawW.coerceAtLeast(1)
                            height = rawH.coerceAtLeast(1)
                        }
                        // Always use ARGB_8888 for correct PDF rendering
                        val bitmap = try {
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        } catch (oom: OutOfMemoryError) {
                            android.util.Log.e("PdfViewModel", "OOM creating page bitmap for page $pageIndex", oom)
                            bitmapCache.evictAll()
                            thumbnailCache.evictAll()
                            System.gc()
                            return@let null
                        }
                        bitmap.eraseColor(android.graphics.Color.WHITE)

                        // Render page content. If rendering fails, do NOT cache a white placeholder,
                        // otherwise the page may stay permanently blank until manual cache invalidation.
                        var rendered = false
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            rendered = true
                        } catch (renderEx: Exception) {
                            android.util.Log.w("PdfViewModel", "page.render failed for page $pageIndex, will retry later", renderEx)
                        } finally {
                            page.close()
                        }
                        if (!rendered) {
                            bitmap.recycle()
                            return@let null
                        }

                        cache.put(pageIndex, bitmap)
                        bitmap
                    } catch (e: Exception) {
                        null
                    } catch (t: Throwable) {
                        android.util.Log.e("PdfViewModel", "Unexpected error rendering page $pageIndex", t)
                        null
                    }
                }
            }
        }
    }

    private fun obtainBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    override fun onCleared() {
        super.onCleared()
        pageSizeScanJob?.cancel()
        pageSizeScanJob = null
        renderScope.cancel()
        try {
            pdfRenderer?.close()
            parcelFileDescriptor?.close()
        } catch (_: Exception) {}
        pdfRenderer = null
        parcelFileDescriptor = null
        bitmapCache.evictAll()
        thumbnailCache.evictAll()
        thumbnailFlowCache.clear()
        bitmapFlowCache.clear()
    }
}
