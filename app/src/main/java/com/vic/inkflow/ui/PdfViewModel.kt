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

    private val _pageSizeVersion = MutableStateFlow(0)
    val pageSizeVersion: StateFlow<Int> = _pageSizeVersion.asStateFlow()

    private fun bumpPageSizeVersion() { _pageSizeVersion.value += 1 }

    private fun invalidateRenderedFlowsInRange(range: IntRange) {
        for (index in range) {
            thumbnailCache.remove(index)
            val thumbFlow = thumbnailFlowCache[index]
            if (thumbFlow != null) {
                thumbFlow.value = null
                viewModelScope.launch(Dispatchers.IO) {
                    renderPage(index, highQuality = false)?.let { thumbFlow.value = it }
                }
            }

            bitmapCache.remove(index)
            val bitmapFlow = bitmapFlowCache[index]
            if (bitmapFlow != null) {
                bitmapFlow.value = null
                viewModelScope.launch(Dispatchers.IO) {
                    renderPage(index, highQuality = true)?.let { bitmapFlow.value = it }
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
                        bumpPageSizeVersion()
                    } catch (_: Exception) {
                    }
                }
            }
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
            var backup: java.io.File? = null
            try {
                val targetFile = File(fileUri.path!!)
                backup = PageOpJournal.backupFile(getApplication(), targetFile)
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
                val ok = com.vic.inkflow.util.PdfManager.insertBlankPage(fileUri, afterIndex, pageWidthPt, pageHeightPt)

                if (!ok) {
                    PageOpJournal.restoreBackup(getApplication(), backup, targetFile)
                    PageOpJournal.clear(getApplication())
                    _pageOperationMessage.value = "新增頁面失敗，請再試一次"
                    reopenCurrentPdf(fileUri, fallbackPageCount = currentCount)
                    return@launch
                }

                PageOpJournal.markFileDone(getApplication(), entry)
                db.withTransaction {
                    db.strokeDao().shiftPageIndicesUp(documentUri, optimisticNewIndex, 1)
                    db.textAnnotationDao().shiftPageIndicesUp(documentUri, optimisticNewIndex, 1)
                    db.imageAnnotationDao().shiftPageIndicesUp(documentUri, optimisticNewIndex, 1)
                    db.bookmarkDao().shiftPageIndicesUp(documentUri, optimisticNewIndex, 1)
                }
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
                val merged = PdfManager.insertPdfPages(targetFileUri, localSourceUri, afterIndex)
                if (!merged) {
                    PageOpJournal.restoreBackup(getApplication(), backup, targetFile)
                    PageOpJournal.clear(getApplication())
                    _pageOperationMessage.value = "匯入 PDF 失敗，請再試一次"
                    reopenCurrentPdf(targetFileUri, fallbackPageCount = currentCount)
                    return@launch
                }

                PageOpJournal.markFileDone(getApplication(), entry)
                db.withTransaction {
                    db.strokeDao().shiftPageIndicesUp(documentUri, insertionIndex, insertedPageCount)
                    db.textAnnotationDao().shiftPageIndicesUp(documentUri, insertionIndex, insertedPageCount)
                    db.imageAnnotationDao().shiftPageIndicesUp(documentUri, insertionIndex, insertedPageCount)
                    db.bookmarkDao().shiftPageIndicesUp(documentUri, insertionIndex, insertedPageCount)
                }
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
                renderMutex.withLock { closeRendererOnly() }

                // 1. Move page in PDF file
                val moved = com.vic.inkflow.util.PdfManager.movePage(fileUri, fromIndex, toIndex)

                if (moved) {
                    PageOpJournal.markFileDone(getApplication(), entry)

                    // 2. Transact DB index updates
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
                }
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
                renderMutex.withLock { closeRendererOnly(clearFlows = false) }
            
            // ???格活摨惜 I/O ?寞活?芷????撖阡? PDF ?
                val deleted = com.vic.inkflow.util.PdfManager.deletePages(fileUri, sortedIndices)

                if (deleted) {
                    PageOpJournal.markFileDone(getApplication(), entry)
                // 同步下修所有 annotation 的頁碼索引。
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
                    }
                    PageOpJournal.deleteBackup(getApplication(), backup)
                }
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
        if (existing != null) return existing
        val flow = MutableStateFlow(bitmapCache[pageIndex])
        bitmapFlowCache[pageIndex] = flow
        if (flow.value == null) {
            viewModelScope.launch(Dispatchers.IO) {
                renderPage(pageIndex, highQuality = true)?.let {
                    flow.value = it
                }
            }
        }
        return flow
    }

    fun getPageThumbnail(pageIndex: Int): StateFlow<Bitmap?> {
        val existing = thumbnailFlowCache[pageIndex]
        if (existing != null) return existing
        val flow = MutableStateFlow(thumbnailCache[pageIndex])
        thumbnailFlowCache[pageIndex] = flow
        if (flow.value == null) {
            viewModelScope.launch(Dispatchers.IO) {
                renderPage(pageIndex, highQuality = false)?.let { flow.value = it }
            }
        }
        return flow
    }

    fun prefetchPage(pageIndex: Int) {
        if (pageIndex < 0 || pageIndex >= pageCount.value) return
        if (bitmapCache[pageIndex] != null) return

        viewModelScope.launch(Dispatchers.IO) {
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
            renderMutex.withLock {
                pdfRenderer?.let { renderer ->
                    try {
                        val page = renderer.openPage(pageIndex)
                        val scale = if (highQuality) 2f else 0.4f
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
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
                    }
                }
            }
        }
    }

    private fun obtainBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    override fun onCleared() {
        runBlocking {
            renderMutex.withLock { closePdf() }
        }
        super.onCleared()
    }
}
