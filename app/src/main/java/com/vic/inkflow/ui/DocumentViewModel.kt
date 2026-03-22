package com.vic.inkflow.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.DocumentDao
import com.vic.inkflow.data.DocumentEntity
import com.vic.inkflow.data.StrokeDao
import com.vic.inkflow.util.PdfManager
import com.vic.inkflow.util.ThumbnailCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private data class DocumentThumbnailEntry(
    val cacheKey: String,
    val flow: MutableStateFlow<Bitmap?>
)

class DocumentViewModel(private val documentDao: DocumentDao, private val strokeDao: StrokeDao, private val db: AppDatabase) : ViewModel() {

    val documents: StateFlow<List<DocumentEntity>> = documentDao.getAllDocuments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val thumbnailEntries = ConcurrentHashMap<String, DocumentThumbnailEntry>()
    private val thumbnailJobs = ConcurrentHashMap<String, Job>()
    private val thumbnailLoadSemaphore = Semaphore(permits = 2)

    // Guard so fixStaleNames runs at most once per ViewModel lifetime (survives config changes).
    private var staleNamesMigrated = false

    fun getDocumentThumbnail(context: Context, documentUri: String): StateFlow<Bitmap?> {
        val appContext = context.applicationContext
        val cacheKey = buildThumbnailCacheKey(documentUri)
        val entry = thumbnailEntries.compute(documentUri) { _, current ->
            if (current?.cacheKey == cacheKey) {
                current
            } else {
                current?.let { ThumbnailCacheManager.remove(it.cacheKey) }
                DocumentThumbnailEntry(
                    cacheKey = cacheKey,
                    flow = MutableStateFlow(ThumbnailCacheManager.get(cacheKey))
                )
            }
        } ?: DocumentThumbnailEntry(cacheKey, MutableStateFlow(ThumbnailCacheManager.get(cacheKey)))

        if (entry.flow.value == null) {
            ensureThumbnailLoaded(appContext, documentUri, entry)
        }
        return entry.flow.asStateFlow()
    }

    fun recordOpened(uri: String, displayName: String) {
        viewModelScope.launch {
            documentDao.upsert(DocumentEntity(uri = uri, displayName = displayName))
        }
    }

    fun delete(uri: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            invalidateThumbnail(uri)
            // Delete all strokes and annotations belonging to this document first.
            strokeDao.deleteStrokesForDocument(uri)
            db.textAnnotationDao().deleteForDocument(uri)
            db.imageAnnotationDao().deleteForDocument(uri)
            db.documentPreferenceDao().deleteByDocumentUri(uri)
            // Delete the physical file for app-private documents (file:// URIs).
            try {
                val parsed = android.net.Uri.parse(uri)
                if (parsed.scheme == "file") {
                    val file = java.io.File(parsed.path!!)
                    if (file.exists()) file.delete()
                }
            } catch (_: Exception) { }
            documentDao.delete(uri)
        }
    }

    /** Re-resolve display names that were saved as raw URI segments (e.g. "document:1000000062"). */
    fun fixStaleNames(context: Context) {
        if (staleNamesMigrated) return
        staleNamesMigrated = true
        viewModelScope.launch(Dispatchers.IO) {
            val stalePattern = Regex("^[a-z]+:\\d+$", RegexOption.IGNORE_CASE)
            val snapshot = documents.value
            snapshot.filter { stalePattern.matches(it.displayName) }.forEach { doc ->
                val uri = Uri.parse(doc.uri)
                val resolved = try {
                    context.contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                } catch (_: Exception) { null }
                if (resolved != null && resolved != doc.displayName) {
                    documentDao.upsert(doc.copy(displayName = resolved))
                }
            }
        }
    }

    fun updateLastPage(uri: String, pageIndex: Int) {
        viewModelScope.launch {
            documentDao.updateLastPage(uri, pageIndex)
        }
    }

    suspend fun getLastPageIndex(uri: String): Int {
        return documentDao.getLastPageIndex(uri) ?: 0
    }

    fun rename(uri: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            documentDao.renameDocument(uri, newName.trim().ifEmpty { "未命名筆記" })
        }
    }

    fun toggleFavorite(uri: String, isFavorite: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            documentDao.updateFavoriteStatus(uri, isFavorite)
        }
    }

    private fun ensureThumbnailLoaded(
        context: Context,
        documentUri: String,
        entry: DocumentThumbnailEntry
    ) {
        val currentJob = thumbnailJobs[documentUri]
        if (currentJob?.isActive == true) return

        thumbnailJobs[documentUri] = viewModelScope.launch(Dispatchers.IO) {
            thumbnailLoadSemaphore.withPermit {
                try {
                    val latestEntry = thumbnailEntries[documentUri]
                    if (latestEntry == null || latestEntry.cacheKey != entry.cacheKey || latestEntry.flow.value != null) {
                        return@withPermit
                    }

                    val bitmap = renderThumbnailBitmap(context, documentUri)
                    if (bitmap != null) {
                        ThumbnailCacheManager.put(entry.cacheKey, bitmap)
                    }
                    latestEntry.flow.value = bitmap
                } finally {
                    thumbnailJobs.remove(documentUri)
                }
            }
        }
    }

    private suspend fun renderThumbnailBitmap(context: Context, documentUri: String): Bitmap? {
        val uri = Uri.parse(documentUri)
        val pfd = PdfManager.openPdfFileDescriptor(context, uri) ?: return null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        return try {
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount == 0) return null
            page = renderer.openPage(0)
            val scale = 0.35f
            val bitmapWidth = (page.width * scale).toInt().coerceAtLeast(1)
            val bitmapHeight = (page.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                val canvas = android.graphics.Canvas(bitmap)
                canvas.scale(scale, scale)

                // 1. Draw Images
                val images = db.imageAnnotationDao().getForPageSync(documentUri, 0)
                for (img in images) {
                    val bmp = loadBitmapFromUri(context, img.uri)
                    if (bmp != null) {
                        canvas.drawBitmap(
                            bmp, null,
                            android.graphics.RectF(img.modelX, img.modelY, img.modelX + img.modelWidth, img.modelY + img.modelHeight),
                            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                        )
                        bmp.recycle()
                    }
                }

                // 2. Draw Strokes
                val strokes = strokeDao.getStrokesForPageSync(documentUri, 0)
                val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    strokeJoin = android.graphics.Paint.Join.ROUND
                }
                for (swp in strokes) {
                    val isHL = swp.stroke.isHighlighter
                    strokePaint.color = swp.stroke.color
                    strokePaint.strokeWidth = swp.stroke.strokeWidth * (if (isHL) 3f else 1f)
                    strokePaint.alpha = if (isHL) (255 * 0.4f).toInt() else 255
                    strokePaint.xfermode = if (isHL) android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.MULTIPLY) else null

                    if (swp.stroke.shapeType != null) {
                        val r = android.graphics.RectF(swp.stroke.boundsLeft, swp.stroke.boundsTop, swp.stroke.boundsRight, swp.stroke.boundsBottom)
                        when (swp.stroke.shapeType) {
                            "RECT"  -> canvas.drawRect(r, strokePaint)
                            "OVAL"  -> canvas.drawOval(r, strokePaint)
                            "LINE", "ARROW" -> if (swp.points.size >= 2) {
                                val p0 = swp.points.first(); val p1 = swp.points.last()
                                canvas.drawLine(p0.x, p0.y, p1.x, p1.y, strokePaint)
                            }
                        }
                    } else {
                        val pts = swp.points
                        if (pts.size >= 2) {
                            val path = android.graphics.Path()
                            path.moveTo(pts[0].x, pts[0].y)
                            if (pts.size < 3) {
                                for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                            } else {
                                for (i in 1 until pts.size) {
                                    val prev = pts[i - 1]; val curr = pts[i]
                                    path.quadTo(prev.x, prev.y, (prev.x + curr.x) / 2f, (prev.y + curr.y) / 2f)
                                }
                                path.lineTo(pts.last().x, pts.last().y)
                            }
                            canvas.drawPath(path, strokePaint)
                        }
                    }
                }

                // 3. Draw Texts
                val texts = db.textAnnotationDao().getForPageSync(documentUri, 0)
                val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.FILL
                }
                for (txt in texts) {
                    textPaint.textSize = txt.fontSize
                    textPaint.color = txt.colorArgb
                    var y = txt.modelY + txt.fontSize
                    txt.text.split("\n").forEach { line ->
                        canvas.drawText(line, txt.modelX, y, textPaint)
                        y += txt.fontSize * 1.2f
                    }
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                page?.close()
            } catch (_: Exception) { }
            try {
                renderer?.close()
            } catch (_: Exception) { }
            try {
                pfd.close()
            } catch (_: Exception) { }
        }
    }

    private fun loadBitmapFromUri(context: Context, uri: String): Bitmap? {
        return try {
            val parsed = Uri.parse(uri)
            if (parsed.scheme == "content") {
                context.contentResolver.openInputStream(parsed)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            } else {
                val path = if (parsed.scheme == "file") parsed.path ?: uri else uri
                android.graphics.BitmapFactory.decodeFile(path)
            }
        } catch (_: Exception) { null }
    }

    private fun buildThumbnailCacheKey(documentUri: String): String {
        val parsed = Uri.parse(documentUri)
        val fileVersion = if (parsed.scheme == "file") {
            runCatching { File(parsed.path!!).lastModified() }.getOrDefault(0L)
        } else {
            0L
        }
        return "$documentUri#$fileVersion"
    }

    private fun invalidateThumbnail(documentUri: String) {
        thumbnailJobs.remove(documentUri)?.cancel()
        thumbnailEntries.remove(documentUri)?.let { entry ->
            ThumbnailCacheManager.remove(entry.cacheKey)
            entry.flow.value = null
        }
    }
}

class DocumentViewModelFactory(private val dao: DocumentDao, private val strokeDao: StrokeDao, private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return DocumentViewModel(dao, strokeDao, db) as T
    }
}
