package com.vic.inkflow.util

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object PdfManager {
    private const val TAG = "PdfManager"

    /** App 私有外部儲存目錄（不需任何權限，空間比 filesDir 大）。 */
    private fun pdfDir(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir  // fallback 到內部儲存
        return dir.also { it.mkdirs() }
    }

    // ─── 新功能：複製 PDF 到 App 私有目錄 ─────────────────────────────────────

    /** 將外部 content:// PDF 複製到 App 私有目錄，回傳 file:// Uri。失敗回傳 null。 */
    suspend fun copyPdfToAppDir(context: Context, sourceUri: Uri): Uri? =
        withContext(Dispatchers.IO) {
            try {
                val dest = File(pdfDir(context), "${UUID.randomUUID()}.pdf")
                context.contentResolver.openInputStream(sourceUri)!!.use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
                Uri.fromFile(dest)
            } catch (e: Exception) {
                Log.e(TAG, "copyPdfToAppDir failed", e)
                null
            }
        }

    suspend fun copyImageToAppDir(context: Context, sourceUri: Uri): Uri? =
        withContext(Dispatchers.IO) {
            var dest: File? = null
            try {
                dest = File(pdfDir(context), "${UUID.randomUUID()}.jpg")
                val input = context.contentResolver.openInputStream(sourceUri)
                if (input == null) {
                    dest.delete()
                    return@withContext null
                }
                input.use { stream ->
                    FileOutputStream(dest).use { output -> stream.copyTo(output) }
                }
                Uri.fromFile(dest)
            } catch (e: Exception) {
                Log.e(TAG, "copyImageToAppDir failed", e)
                dest?.delete()
                null
            }
        }

    // ─── 新功能：建立空白 PDF ─────────────────────────────────────────────────

    /** 建立一個單頁 A4 空白 PDF，存到 App 私有目錄，回傳 file:// Uri。失敗回傳 null。 */
    suspend fun createBlankPdf(
        context: Context,
        pageWidthPt: Float = PDRectangle.A4.width,
        pageHeightPt: Float = PDRectangle.A4.height
    ): Uri? =
        withContext(Dispatchers.IO) {
            try {
                val dest = File(pdfDir(context), "${UUID.randomUUID()}.pdf")
                PDDocument().use { doc ->
                    doc.addPage(PDPage(PDRectangle(pageWidthPt, pageHeightPt)))
                    doc.save(dest)
                }
                Uri.fromFile(dest)
            } catch (e: Exception) {
                Log.e(TAG, "createBlankPdf failed", e)
                null
            }
        }

    // ─── 原子存檔（五處頁操作共用）：tmp + rename，跨掛載 fallback 複製 ───

    /**
     * 把已改動的 [doc] 原子寫回 [file]（先寫 tmp 再 rename；失敗拋異常由呼叫方轉 false）。
     * 內建存檔耗時 log（超大檔定位用；與 PdfViewModel 的 op 級 perf log 時間戳對齊）。
     */
    private fun saveAtomically(file: File, doc: PDDocument) {
        val tmpFile = File(file.parent, "${file.nameWithoutExtension}.tmp_${System.currentTimeMillis()}.pdf")
        val tSave0 = System.currentTimeMillis()
        try {
            doc.save(tmpFile)
            if (!tmpFile.renameTo(file)) {
                // renameTo can fail across mount points; fall back to copy+delete
                tmpFile.copyTo(file, overwrite = true)
                tmpFile.delete()
            }
            Log.d(TAG, "fileSplit: save=${System.currentTimeMillis() - tSave0}ms sizeMb=${file.length() / 1048576} pages=${doc.numberOfPages}")
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }

    // ─── 新功能：在現有 PDF 末尾加入空白頁 ───────────────────────────────────

    /**
     * 將 [afterIndex] 換算成插入位置（新頁要放的索引）。
     * Int.MAX_VALUE = 接尾（呼叫端慣例，見 mergePdfs / appendBlankPage）。
     * 注意：不可寫 afterIndex + 1 再 coerce——MAX_VALUE + 1 會溢位成 MIN_VALUE
     * 而被箍到 0 = 每次插到最前面（多選合併順序反轉的元兇）。
     */
    internal fun resolveInsertionIndex(afterIndex: Int, pageCount: Int): Int =
        if (afterIndex == Int.MAX_VALUE || afterIndex >= pageCount - 1) pageCount
        else (afterIndex + 1).coerceIn(0, pageCount)

    /** 在 file:// URI 的 PDF 中，於 [afterIndex] 頁之後插入一頁空白頁。
     *  頁面尺寸由 [pageWidthPt] 和 [pageHeightPt] 決定（預設為 A4 直向）。
     *  afterIndex = -1 或超過末頁時，直接追加到最後。
     *  採用「寫入暫存檔 → 原子重命名」策略，確保操作失敗時原始 PDF 不受損。 */
    suspend fun insertBlankPage(
        fileUri: Uri,
        afterIndex: Int,
        pageWidthPt: Float = PDRectangle.A4.width,
        pageHeightPt: Float = PDRectangle.A4.height
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (fileUri.scheme != "file") {
                Log.w(TAG, "insertBlankPage: only file:// URIs are supported")
                return@withContext false
            }
            try {
                val file = File(fileUri.path!!)
                val tLoad0 = System.currentTimeMillis()
                PDDocument.load(file).use { doc ->
                    Log.d(TAG, "fileSplit: load=${System.currentTimeMillis() - tLoad0}ms")
                    val newPage = PDPage(PDRectangle(pageWidthPt, pageHeightPt))
                    val insertBefore = resolveInsertionIndex(afterIndex, doc.numberOfPages)
                    if (insertBefore < doc.numberOfPages) {
                        doc.pages.insertBefore(newPage, doc.getPage(insertBefore))
                    } else {
                        doc.addPage(newPage)
                    }
                    // Atomic write: save to a temp file first, then rename over the original.
                    // This prevents file corruption if the process is killed mid-write.
                    saveAtomically(file, doc)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "insertBlankPage failed", e)
                false
            }
        }

    /** Returns the number of pages in a PDF addressed by a file:// Uri. */
    suspend fun getPdfPageCount(fileUri: Uri): Int =
        withContext(Dispatchers.IO) {
            if (fileUri.scheme != "file") {
                Log.w(TAG, "getPdfPageCount: only file:// URIs are supported")
                return@withContext 0
            }
            try {
                val file = File(fileUri.path!!)
                PDDocument.load(file).use { doc -> doc.numberOfPages }
            } catch (e: Exception) {
                Log.e(TAG, "getPdfPageCount failed", e)
                0
            }
        }

    /**
     * Inserts all pages from [sourceFileUri] into [targetFileUri] after [afterIndex].
     * Both URIs must be file:// and the target file is updated atomically via a temp file.
     */
    suspend fun insertPdfPages(
        targetFileUri: Uri,
        sourceFileUri: Uri,
        afterIndex: Int
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (targetFileUri.scheme != "file" || sourceFileUri.scheme != "file") {
                Log.w(TAG, "insertPdfPages: only file:// URIs are supported")
                return@withContext false
            }
            try {
                val targetFile = File(targetFileUri.path!!)
                val sourceFile = File(sourceFileUri.path!!)
                val tLoad0 = System.currentTimeMillis()
                PDDocument.load(targetFile).use { targetDoc ->
                    Log.d(TAG, "fileSplit: targetLoad=${System.currentTimeMillis() - tLoad0}ms")
                    PDDocument.load(sourceFile).use { sourceDoc ->
                        val sourceCount = sourceDoc.numberOfPages
                        if (sourceCount <= 0) {
                            Log.w(TAG, "insertPdfPages: source PDF has no pages")
                            return@use
                        }

                        val insertionIndex = resolveInsertionIndex(afterIndex, targetDoc.numberOfPages)
                        Log.d(
                            TAG,
                            "insertPdfPages: targetCount=${targetDoc.numberOfPages}, sourceCount=$sourceCount, afterIndex=$afterIndex, insertionIndex=$insertionIndex"
                        )

                        // Deterministic insertion: import one page at a time and place it
                        // directly at the next insertion slot. This avoids unstable page-tree
                        // reordering when trying to batch-move already imported pages.
                        var insertPos = insertionIndex
                        repeat(sourceCount) { srcIndex ->
                            val imported = targetDoc.importPage(sourceDoc.getPage(srcIndex))
                            // importPage appends at tail first; remove that tail occurrence,
                            // then insert at the intended position.
                            targetDoc.removePage(targetDoc.numberOfPages - 1)
                            if (insertPos < targetDoc.numberOfPages) {
                                targetDoc.pages.insertBefore(imported, targetDoc.getPage(insertPos))
                            } else {
                                targetDoc.addPage(imported)
                            }
                            insertPos++
                        }

                        saveAtomically(targetFile, targetDoc)
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "insertPdfPages failed", e)
                false
            }
        }

    /** @deprecated Use insertBlankPage instead. */
    @Deprecated("Use insertBlankPage(fileUri, afterIndex) instead", ReplaceWith("insertBlankPage(fileUri, Int.MAX_VALUE)"))
    suspend fun appendBlankPage(fileUri: Uri): Boolean = insertBlankPage(fileUri, Int.MAX_VALUE)

    /** 讀取 content:// URI 的顯示名稱，失敗回傳 null。 */
    private suspend fun displayNameOf(context: Context, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
        }

    /**
     * 將多份 PDF 按 [sourceUris] 順序合併成一份新文件。
     * 第一份拷貝為底，後面逐份 [insertPdfPages] 接到尾巴；中間拷貝全刪，只留成品。
     * 壞檔/加密檔跳過並記入回傳的失敗名單（部分合併照樣可用，不整批作廢）。
     * @return Triple(成品 file:// Uri（全失敗則 null）, 失敗顯示名稱, 底檔顯示名稱?)
     */
    suspend fun mergePdfs(
        context: Context,
        sourceUris: List<Uri>
    ): Triple<Uri?, List<String>, String?> =
        withContext(Dispatchers.IO) {
            if (sourceUris.isEmpty()) return@withContext Triple(null, emptyList(), null)
            val failed = mutableListOf<String>()
            // 先全部拷進私有目錄（加密/壞檔在算頁數時現形）。
            val copied = mutableListOf<Pair<Uri, String>>()
            for (uri in sourceUris) {
                val name = displayNameOf(context, uri) ?: uri.lastPathSegment ?: "未命名"
                val local = copyPdfToAppDir(context, uri)
                if (local == null) {
                    failed.add(name)
                    continue
                }
                copied.add(local to name)
            }
            if (copied.isEmpty()) return@withContext Triple(null, failed, null)

            val (baseUri, baseName) = copied.first()
            try {
                for ((localUri, name) in copied.drop(1)) {
                    if (getPdfPageCount(localUri) <= 0) {
                        failed.add(name)
                        continue
                    }
                    if (!insertPdfPages(baseUri, localUri, Int.MAX_VALUE)) {
                        failed.add(name)
                    }
                }
                Triple(baseUri, failed, baseName)
            } catch (e: Exception) {
                Log.e(TAG, "mergePdfs failed", e)
                Triple(null, failed, null)
            } finally {
                // 中間拷貝全刪；成品（base）保留，呼叫端負責。
                for ((localUri, _) in copied.drop(1)) {
                    runCatching { localUri.path?.let { File(it).delete() } }
                }
            }
        }

    /** 刪除 file:// URI PDF 中第 [pageIndex] 頁。若僅剩一頁則拒絕刪除並回傳 false。
     *  採用「寫入暫存檔 → 原子重命名」策略，確保操作失敗時原始 PDF 不受損。 */
    suspend fun deletePage(fileUri: Uri, pageIndex: Int): Boolean =
        withContext(Dispatchers.IO) {
            if (fileUri.scheme != "file") {
                Log.w(TAG, "deletePage: only file:// URIs are supported")
                return@withContext false
            }
            try {
                val file = File(fileUri.path!!)
                val tLoad0 = System.currentTimeMillis()
                PDDocument.load(file).use { doc ->
                    Log.d(TAG, "fileSplit: load=${System.currentTimeMillis() - tLoad0}ms")
                    if (doc.numberOfPages <= 1) {
                        Log.w(TAG, "deletePage: cannot delete the only page")
                        return@withContext false
                    }
                    doc.removePage(pageIndex)
                    // Atomic write: save to a temp file first, then rename over the original.
                    // This prevents file corruption if the process is killed mid-write.
                    saveAtomically(file, doc)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "deletePage failed", e)
                false
            }
        }

    /** 將 PDF 的第 [fromIndex] 頁移動到 [toIndex]。
     *  採用「寫入暫存檔 → 原子重命名」策略，確保操作失敗時原始 PDF 不受損。 */
    suspend fun movePage(fileUri: Uri, fromIndex: Int, toIndex: Int): Boolean =
        withContext(Dispatchers.IO) {
            if (fileUri.scheme != "file") {
                Log.w(TAG, "movePage: only file:// URIs are supported")
                return@withContext false
            }
            if (fromIndex == toIndex) return@withContext true
            try {
                val file = File(fileUri.path!!)
                val tLoad0 = System.currentTimeMillis()
                PDDocument.load(file).use { doc ->
                    Log.d(TAG, "fileSplit: load=${System.currentTimeMillis() - tLoad0}ms")
                    if (fromIndex < 0 || fromIndex >= doc.numberOfPages || 
                        toIndex < 0 || toIndex >= doc.numberOfPages) return@withContext false
                    
                    val pageToMove = doc.getPage(fromIndex)
                    doc.removePage(fromIndex) // 移除後，後面的頁面 index 會減 1
                    
                    if (toIndex >= doc.numberOfPages) { // 移到最後一頁
                        doc.addPage(pageToMove)
                    } else {
                        // 因為已移除 fromIndex，toIndex 代表的就是最終想要的絕對位置索引
                        doc.pages.insertBefore(pageToMove, doc.getPage(toIndex))
                    }

                    saveAtomically(file, doc)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "movePage failed", e)
                false
            }
        }

    /** 批次刪除 file:// URI PDF 中的多個頁面。[pageIndices] 必須是在原始文件中的絕對索引。
     *  採用「一次載入 → 全數刪除 → 一次存檔」策略，大幅提升 I/O 效能。*/
    suspend fun deletePages(fileUri: Uri, pageIndices: List<Int>): Boolean =
        withContext(Dispatchers.IO) {
            if (fileUri.scheme != "file" || pageIndices.isEmpty()) return@withContext false
            try {
                val file = File(fileUri.path!!)
                val tLoad0 = System.currentTimeMillis()
                PDDocument.load(file).use { doc ->
                    Log.d(TAG, "fileSplit: load=${System.currentTimeMillis() - tLoad0}ms")
                    if (doc.numberOfPages <= pageIndices.size) {
                        Log.w(TAG, "deletePages: cannot delete all pages")
                        return@withContext false
                    }
                    
                    // 從後往前刪除，這樣已經刪除的頁面就不會影響到前面頁面的索引
                    val sortedDescending = pageIndices.sortedDescending()
                    for (index in sortedDescending) {
                        if (index in 0 until doc.numberOfPages) {
                            doc.removePage(index)
                        }
                    }

                    saveAtomically(file, doc)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "deletePages failed", e)
                false
            }
        }

    // ─── 雙 Scheme 支援 ───────────────────────────────────────────────────────

    /**
     * 開啟 ParcelFileDescriptor，同時支援 file:// 與 content:// URI。
     * 必須在 IO 執行緒調用。
     */
    fun openPdfFileDescriptor(context: Context, uri: Uri): ParcelFileDescriptor? {
        return try {
            if (uri.scheme == "file") {
                ParcelFileDescriptor.open(
                    File(uri.path!!),
                    ParcelFileDescriptor.MODE_READ_ONLY
                )
            } else {
                context.contentResolver.openFileDescriptor(uri, "r")
            }
        } catch (e: Exception) {
            Log.e(TAG, "openPdfFileDescriptor failed for $uri", e)
            null
        }
    }

    /**
     * Reads the width and height (in PDF points) of the first page of the given PDF.
     * Returns null if the PDF cannot be read.
     * Must be called on the IO thread.
     */
    fun readFirstPageSize(context: Context, uri: Uri): Pair<Float, Float>? {
        val pfd = openPdfFileDescriptor(context, uri) ?: return null
        return try {
            val renderer = PdfRenderer(pfd)
            try {
                val page = renderer.openPage(0)
                try {
                    val w = page.width.toFloat()
                    val h = page.height.toFloat()
                    if (w > 0f && h > 0f) Pair(w, h) else null
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "readFirstPageSize failed for $uri", e)
            null
        } finally {
            pfd.close()
        }
    }

    // ─── 向後相容（舊有 content:// URI 仍可用）───────────────────────────────

    /** @deprecated 新文件改用 copyPdfToAppDir()。舊有 content:// 記錄靠此繼續讀取。 */
    @Deprecated("Use copyPdfToAppDir() for new imports. This is kept for backward compatibility with existing content:// URIs in the database.")
    fun takePersistableUriPermission(context: Context, uri: Uri): Boolean {
        return try {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            }
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "takePersistableUriPermission failed for $uri", e)
            false
        }
    }

    fun closePdfRenderer(renderer: PdfRenderer?) {
        try {
            renderer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PdfRenderer", e)
        }
    }
}