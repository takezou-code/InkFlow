package com.vic.inkflow.ui


import android.app.Application

import android.content.Context

import android.graphics.Bitmap

import android.graphics.pdf.PdfRenderer

import android.net.Uri

import android.os.ParcelFileDescriptor

import androidx.collection.LruCache

import androidx.lifecycle.AndroidViewModel

import androidx.lifecycle.viewModelScope

import androidx.room.withTransaction

import com.vic.inkflow.data.AppDatabase

import com.vic.inkflow.util.PdfManager

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.flow.MutableStateFlow

import kotlinx.coroutines.withContext

import kotlinx.coroutines.flow.StateFlow

import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.launch

import kotlinx.coroutines.sync.Mutex

import kotlinx.coroutines.sync.withLock

import java.io.File

class PdfViewModel(application: Application) : AndroidViewModel(application) {

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

    /** true ??銵函內甇??脰?? / ?芷????啣摮?UI ?＊蝷粹脣漲?內?其蒂??賊?????*/
    private val _isPageOperationInProgress = MutableStateFlow(false)
    val isPageOperationInProgress: StateFlow<Boolean> = _isPageOperationInProgress.asStateFlow()

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

    // Emits the index of the deleted page so the UI can adjust currentPageIndex.
    private val _lastDeletedPageIndex = MutableStateFlow<Int?>(null)
    val lastDeletedPageIndex: StateFlow<Int?> = _lastDeletedPageIndex.asStateFlow()

    /** Call once after consuming lastDeletedPageIndex to reset the event. */
    fun consumeDeletedPageEvent() { _lastDeletedPageIndex.value = null }

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

    init {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8

        bitmapCache = object : LruCache<Int, Bitmap>(cacheSize) {
            override fun sizeOf(key: Int, bitmap: Bitmap): Int = bitmap.byteCount / 1024
        }

        thumbnailCache = object : LruCache<Int, Bitmap>(maxMemory / 20) {
            override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    /** Returns the aspect ratio (width/height) for [index], or A4 portrait ratio as fallback. */
    fun getPageAspectRatio(index: Int): Float {
        val size = pageSizesMap[index] ?: return 595f / 842f
        return if (size.second > 0f) size.first / size.second else 595f / 842f
    }

    /**
     * Reads all page dimensions from [renderer] and caches them in [pageSizesMap].
     * Must be called while [renderMutex] is held so no concurrent page opens conflict.
     */
    private fun readAndCachePageSizes(renderer: PdfRenderer) {
        val newSizes = mutableMapOf<Int, Pair<Float, Float>>()
        for (i in 0 until renderer.pageCount) {
            try {
                renderer.openPage(i).use { page ->
                    val w = page.width.toFloat()
                    val h = page.height.toFloat()
                    if (w > 0f && h > 0f) newSizes[i] = Pair(w, h)
                }
            } catch (_: Exception) { }
        }
        pageSizesMap.clear()
        pageSizesMap.putAll(newSizes)
    }

    fun openPdf(uri: Uri) {
        // ViewModel survives configuration changes ??skip re-opening if the same PDF is already loaded
        if (_currentPdfUri.value == uri && pdfRenderer != null) return

        viewModelScope.launch(Dispatchers.IO) {
            // Close previous PDF under the mutex to avoid racing with renderPage
            renderMutex.withLock { closePdf() }
            _currentPdfUri.value = uri
            try {
                val fd = PdfManager.openPdfFileDescriptor(getApplication(), uri)
                    ?: return@launch
                val renderer = try {
                    PdfRenderer(fd)
                } catch (e: Exception) {
                    fd.close()
                    return@launch
                }
                renderMutex.withLock {
                    parcelFileDescriptor = fd
                    pdfRenderer = renderer
                    readAndCachePageSizes(renderer)
                }
                _pageCount.value = renderer.pageCount
                // Use cached first-page size derived from readAndCachePageSizes above.
                pageSizesMap[0]?.let { _firstPageSize.value = it }
            } catch (e: Exception) {
                android.util.Log.e("PdfViewModel", "Failed to open PDF: $uri", e)
            }
        }
    }

    /** ????Renderer嚗D嚗?皜征 URI ??pageCount嚗?潸蕭???Ｗ????嚗?*/
    private fun closeRendererOnly() {
        pdfRenderer?.close()
        parcelFileDescriptor?.close()
        pdfRenderer = null
        parcelFileDescriptor = null
        bitmapCache.evictAll()
        thumbnailCache.evictAll()
        thumbnailFlowCache.clear()
        bitmapFlowCache.clear()
    }

    /** ??Main ?瑁?蝺?蝛?flow cache嚗蝙銝?甈?getPageThumbnail/getPageBitmap 撘瑕?皜脫???*/
    private fun clearFlowCaches() {
        thumbnailFlowCache.clear()
        bitmapFlowCache.clear()
    }

    /** ?函??PDF嚗???file:// URI嚗? [afterIndex] ??敺??乩???A4 蝛箇??
     *  afterIndex = -1 ??Int.MAX_VALUE ?蕭??怠偏??
     *
     *  ?∠璅?撘?UI ?湔嚗ptimistic UI嚗?
     *  - 蝡?? _pageCount 銝衣??_lastInsertedPageIndex嚗蝙 UI ?祇?撠?單蝛箇??
     *  - 敺??甇亙銵?PDDocument ?耨?寡?摮?嚗? 2?? 蝘???
     *  - 摮?摰?敺??圈???Renderer嚗??_thumbnailVersion 閫貊蝮桀??瑟??
     *  - 憭望???皛暹?閫?湔??
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
        val currentCount = _pageCount.value
        // 閮??唳??仿??揣撘???afterIndex 銋?嚗??怠偏嚗?
        val optimisticNewIndex = if (afterIndex == Int.MAX_VALUE || afterIndex >= currentCount - 1) {
            currentCount  // 餈賢??唳撠橘??圈? index = currentCount
        } else {
            afterIndex + 1
        }.coerceIn(0, currentCount)

        // 蝡閮剖??脰?銝哨?霈?UI ?臭誑憿舐內?脣漲璇?雿???閫?湔 pageCount
        // ???PDF 撠摮????圈?????UI ?岫隢??圈??Ｙ? Bitmap ????PDF 撠頛??? null ???⊿???
        _isPageOperationInProgress.value = true

        viewModelScope.launch(Dispatchers.IO) {
            var dbShiftApplied = false
            try {
                db.withTransaction {
                    db.strokeDao().shiftPageIndicesUp(documentUri, optimisticNewIndex, 1)
                    db.textAnnotationDao().shiftPageIndicesUp(documentUri, optimisticNewIndex, 1)
                    db.imageAnnotationDao().shiftPageIndicesUp(documentUri, optimisticNewIndex, 1)
                }
                dbShiftApplied = true

                renderMutex.withLock { closeRendererOnly() }
                val ok = com.vic.inkflow.util.PdfManager.insertBlankPage(fileUri, afterIndex, pageWidthPt, pageHeightPt)
                
                if (!ok) {
                    if (dbShiftApplied) {
                        db.withTransaction {
                            db.strokeDao().shiftPageIndicesDown(documentUri, optimisticNewIndex - 1)
                            db.textAnnotationDao().shiftPageIndicesDown(documentUri, optimisticNewIndex - 1)
                            db.imageAnnotationDao().shiftPageIndicesDown(documentUri, optimisticNewIndex - 1)
                        }
                    }
                    reopenCurrentPdf(fileUri, fallbackPageCount = currentCount)
                    return@launch
                }
                
                _lastInsertedPageIndex.value = optimisticNewIndex
                reopenCurrentPdf(fileUri, fallbackPageCount = currentCount + 1)

            } catch (e: Exception) {
                android.util.Log.e("PdfViewModel", "insertBlankPage failed", e)
                if (dbShiftApplied) {
                    runCatching {
                        db.withTransaction {
                            db.strokeDao().shiftPageIndicesDown(documentUri, optimisticNewIndex - 1)
                            db.textAnnotationDao().shiftPageIndicesDown(documentUri, optimisticNewIndex - 1)
                            db.imageAnnotationDao().shiftPageIndicesDown(documentUri, optimisticNewIndex - 1)
                        }
                    }
                }
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
        val targetFileUri = _currentPdfUri.value ?: return
        if (targetFileUri.scheme != "file") {
            android.util.Log.w("PdfViewModel", "insertPdfPages: only file:// target URIs supported")
            return
        }

        _isPageOperationInProgress.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val localSourceUri = PdfManager.copyPdfToAppDir(context, sourceUri)
            if (localSourceUri == null) {
                _isPageOperationInProgress.value = false
                return@launch
            }

            val insertedPageCount = PdfManager.getPdfPageCount(localSourceUri)
            if (insertedPageCount <= 0) {
                _isPageOperationInProgress.value = false
                return@launch
            }

            val currentCount = _pageCount.value
            val insertionIndex = if (afterIndex >= currentCount - 1) currentCount else afterIndex + 1

            var dbShiftApplied = false
            try {
                db.withTransaction {
                    db.strokeDao().shiftPageIndicesUp(documentUri, insertionIndex, insertedPageCount)
                    db.textAnnotationDao().shiftPageIndicesUp(documentUri, insertionIndex, insertedPageCount)
                    db.imageAnnotationDao().shiftPageIndicesUp(documentUri, insertionIndex, insertedPageCount)
                }
                dbShiftApplied = true

                renderMutex.withLock { closeRendererOnly() }
                val merged = PdfManager.insertPdfPages(targetFileUri, localSourceUri, afterIndex)
                if (!merged) {
                    if (dbShiftApplied) {
                        db.withTransaction {
                            repeat(insertedPageCount) {
                                db.strokeDao().shiftPageIndicesDown(documentUri, insertionIndex - 1)
                                db.textAnnotationDao().shiftPageIndicesDown(documentUri, insertionIndex - 1)
                                db.imageAnnotationDao().shiftPageIndicesDown(documentUri, insertionIndex - 1)
                            }
                        }
                    }
                    reopenCurrentPdf(targetFileUri, fallbackPageCount = currentCount)
                    return@launch
                }

                _lastInsertedPageIndex.value = insertionIndex
                reopenCurrentPdf(targetFileUri, fallbackPageCount = currentCount + insertedPageCount)
            } catch (e: Exception) {
                android.util.Log.e("PdfViewModel", "insertPdfPages failed", e)
                if (dbShiftApplied) {
                    runCatching {
                        db.withTransaction {
                            repeat(insertedPageCount) {
                                db.strokeDao().shiftPageIndicesDown(documentUri, insertionIndex - 1)
                                db.textAnnotationDao().shiftPageIndicesDown(documentUri, insertionIndex - 1)
                                db.imageAnnotationDao().shiftPageIndicesDown(documentUri, insertionIndex - 1)
                            }
                        }
                    }
                }
                _pageCount.value = currentCount
                _lastInsertedPageIndex.value = null
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
     * 撠?PDF ?洵 [fromIndex] ?宏? [toIndex]??
     * ?湔 PDF ?辣???湔 DB 銝剖????Ｙ? annotations index??
     */
    fun movePage(documentUri: String, fromIndex: Int, toIndex: Int) {
        val fileUri = _currentPdfUri.value ?: return
        if (fileUri.scheme != "file") return
        if (fromIndex == toIndex) return
        val currentCount = _pageCount.value
        if (fromIndex !in 0 until currentCount || toIndex !in 0 until currentCount) return

        _isPageOperationInProgress.value = true

        viewModelScope.launch(Dispatchers.IO) {
            renderMutex.withLock { closeRendererOnly() }
            
            // 1. Move page in PDF file
            val ok = com.vic.inkflow.util.PdfManager.movePage(fileUri, fromIndex, toIndex)
            
            if (ok) {
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
                }
            }

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
                renderMutex.withLock {
                    parcelFileDescriptor = fd
                    pdfRenderer = renderer
                    readAndCachePageSizes(renderer)
                }
                _pageCount.value = renderer.pageCount
                _thumbnailVersion.value++ // ? UI ??渡?蝮桀?
            } catch (e: Exception) {
                android.util.Log.e("PdfViewModel", "movePage reopen failed", e)
            } finally {
                _isPageOperationInProgress.value = false
            }
        }
    }

        /**
     * ?寞活?芷憭???
     * @param documentUri ?冽皜?鞈?摨怎?瑼? URI 璅?
     * @param pageIndices 閬?斤??蝝Ｗ?皜??
     */
    fun deletePages(documentUri: String, pageIndices: List<Int>) {
        val fileUri = _currentPdfUri.value ?: return
        if (fileUri.scheme != "file" || pageIndices.isEmpty()) return
        val currentCount = _pageCount.value
        // 銝???Ｗ?芸?
        if (currentCount <= pageIndices.size) return 

        _isPageOperationInProgress.value = true

        viewModelScope.launch(Dispatchers.IO) {
            renderMutex.withLock { closeRendererOnly() }
            
            // ???格活摨惜 I/O ?寞活?芷????撖阡? PDF ?
            val sortedIndices = pageIndices.sortedDescending(); val ok = com.vic.inkflow.util.PdfManager.deletePages(fileUri, sortedIndices)
            
            if (ok) {
                // ?芷鞈?摨?annotation 銝虫?蝘餃???index
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
                    }
                }
            }
            
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
                    _isPageOperationInProgress.value = false
                    return@launch
                }
                renderMutex.withLock {
                    parcelFileDescriptor = fd
                    pdfRenderer = renderer
                    readAndCachePageSizes(renderer)
                }
                _pageCount.value = renderer.pageCount
                _thumbnailVersion.value++
            } catch (e: Exception) {
                android.util.Log.e("PdfViewModel", "deletePages reopen failed", e)
            } finally {
                _isPageOperationInProgress.value = false
            }
        }
    }

    private fun closePdf() {
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
            renderMutex.withLock {
                parcelFileDescriptor = fd
                pdfRenderer = renderer
                readAndCachePageSizes(renderer)
            }
            clearFlowCaches()
            _pageCount.value = renderer.pageCount
            pageSizesMap[0]?.let { _firstPageSize.value = it }
            _thumbnailVersion.value++
        } catch (e: Exception) {
            android.util.Log.e("PdfViewModel", "reopenCurrentPdf failed", e)
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
        closePdf()
        super.onCleared()
    }
}
