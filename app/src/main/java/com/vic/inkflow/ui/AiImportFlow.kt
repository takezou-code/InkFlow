package com.vic.inkflow.ui

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.MathSourceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream

/**
 * AI 引入管線（切塊 → KaTeX 渲染 → 混合排版 → 掃空白頁 → 寫入 → 跳轉）。
 * 純流程編排，無 Compose 依賴；狀態（aiPickMode 等）由呼叫方持有。
 * 管線任何一步炸了都只 Toast，不閃退。
 */
fun CoroutineScope.importRawText(
    raw: String,
    context: Context,
    viewModel: EditorViewModel,
    pdfViewModel: PdfViewModel,
    db: AppDatabase,
    documentUri: String,
    sourcePage: Int,
    onRequestPage: (Int) -> Unit
) {
    if (raw.isBlank()) return
    launch {
        try {
            importRawTextInner(context, viewModel, pdfViewModel, db, documentUri, sourcePage, onRequestPage, raw)
        } catch (t: Throwable) {
            Log.e("InkFlowDbg", "import failed", t)
            try {
                Toast.makeText(context, "插入失敗：${t.message}", Toast.LENGTH_LONG).show()
            } catch (_: Throwable) { }
        }
    }
}

suspend fun importRawTextInner(
    context: Context,
    viewModel: EditorViewModel,
    pdfViewModel: PdfViewModel,
    db: AppDatabase,
    documentUri: String,
    sourcePage: Int,
    onRequestPage: (Int) -> Unit,
    raw: String
) {
    val blocks = withContext(Dispatchers.Default) {
        splitAiBlocks(raw)
    }
    if (blocks.isEmpty()) {
        Toast.makeText(context, "沒有可插入的內容", Toast.LENGTH_SHORT).show()
        return
    }
    // 數學渲染（WebView 必須 Main thread；失敗的塊退回 Unicode 文字）
    val mathBlocks = blocks.filterIsInstance<AiMathBlock>()
    val rendered = mutableMapOf<String, RenderedMath>()
    var renderFail = 0
    if (mathBlocks.isNotEmpty()) {
        val act = context as? Activity
        if (act != null) MathSnapshot.ensure(act)
        for (mb in mathBlocks) {
            var ok = false
            try {
                val bmp = MathSnapshot.render(mathBlockHtml(mb.html))
                if (bmp != null) {
                    val f = File(context.filesDir, "math_${System.currentTimeMillis()}_${mb.id}.png")
                    FileOutputStream(f).use { out ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                    rendered[mb.id] = RenderedMath(f, bmp.width, bmp.height)
                    bmp.recycle()
                    ok = true
                }
            } catch (t: Throwable) {
                Log.w("InkFlowDbg", "math render failed ${mb.id}: $t")
            }
            if (!ok) renderFail++
        }
    }
    val resolved = resolveAiBlocks(blocks, rendered)
    // 公式源 sidecar（blockId → 數學塊，寫入時存 TeX）
    val mathById = resolved.filterIsInstance<AiMathBlock>().associateBy { it.id }
    val pages = withContext(Dispatchers.Default) {
        paginateAiBlocks(resolved, rendered, viewModel.modelWidth, viewModel.modelHeight)
    }
    if (pages.isEmpty()) {
        Toast.makeText(context, "沒有可插入的內容", Toast.LENGTH_SHORT).show()
        return
    }
    // 先從第 0 頁掃空白頁（DB 先篩＋點陣確認），填滿才開新頁
    val need = pages.size
    val blanks = withContext(Dispatchers.IO) {
        scanBlankPages(db, pdfViewModel, documentUri, need)
    }
    var after = sourcePage
    var placed = 0
    var mathCount = 0
    var firstTarget = -1
    for ((i, page) in pages.withIndex()) {
        val pageIdx = if (i < blanks.size) {
            blanks[i]
        } else {
            if (!insertOnePageAfter(pdfViewModel, viewModel, context, documentUri, after)) break
            after += 1
            after
        }
        if (firstTarget < 0) firstTarget = pageIdx
        // 點陣圖保證：重開空窗期建的 flow 可能永久 null，先預取＋等圖再寫入跳轉
        pdfViewModel.prefetchPage(pageIdx)
        withTimeoutOrNull(3000) {
            pdfViewModel.getPageBitmap(pageIdx).filter { it != null }.first()
        }
        page.forEach { pl ->
            when (pl) {
                is Placed.T -> viewModel.insertImportedText(documentUri, pageIdx, pl.t.text, pl.t.modelX, pl.t.modelY, pl.t.fontSize)
                is Placed.I -> {
                    val imageUri = Uri.fromFile(pl.file).toString()
                    viewModel.insertImportedImage(
                        documentUri, pageIdx, imageUri,
                        pl.modelX, pl.modelY, pl.modelW, pl.modelH
                    )
                    mathCount++
                    // TeX 源存檔（查不到即純圖，不影響顯示）
                    val mb = mathById[pl.blockId]
                    val tex = mb?.html
                    if (tex != null) {
                        withContext(Dispatchers.IO) {
                            try {
                                db.mathSourceDao().insert(
                                    MathSourceEntity(
                                        documentUri = documentUri,
                                        pageIndex = pageIdx,
                                        imageUri = imageUri,
                                        tex = tex,
                                        display = if (mb.display) 1 else 0
                                    )
                                )
                            } catch (t: Throwable) {
                                Log.w("InkFlowDbg", "math source save failed: $t")
                            }
                        }
                    }
                }
            }
        }
        placed++
    }
    if (placed > 0) {
        onRequestPage(firstTarget)
        Toast.makeText(context, "已插入 ${pages.sumOf { it.size }} 段（公式圖 ${mathCount}，${placed} 頁" + (if (blanks.isNotEmpty()) "，含空白頁再利用" else "") + "）" + if (renderFail > 0) "（${renderFail} 式渲染失敗已退回文字）" else "", Toast.LENGTH_SHORT).show()
    } else {
        Toast.makeText(context, "開新頁失敗，請稍後再試", Toast.LENGTH_SHORT).show()
    }
}

// insertBlankPage 同一時間只接受一頁（進行中會直接丟棄），故用 pageCount 逐頁確認。
suspend fun insertOnePageAfter(
    pdfViewModel: PdfViewModel,
    viewModel: EditorViewModel,
    context: Context,
    documentUri: String,
    afterIndex: Int
): Boolean {
    repeat(5) {
        val before = pdfViewModel.pageCount.value
        if (!pdfViewModel.isPageOperationInProgress.value) {
            pdfViewModel.insertBlankPage(
                context, documentUri, afterIndex,
                pageWidthPt = viewModel.modelWidth,
                pageHeightPt = viewModel.modelHeight
            )
        }
        val done = withTimeoutOrNull(30000) {
            pdfViewModel.pageCount.filter { it == before + 1 }.first()
        }
        if (done != null) return true
        withTimeoutOrNull(10000) {
            pdfViewModel.isPageOperationInProgress.filter { !it }.first()
        }
    }
    return false
}

// 從第 0 頁往後掃空白頁（DB 先篩：有墨/字/圖直接跳過；DB 空的才拿點陣確認）。
// 在 IO 執行緒呼叫；找到 need 個或掃完即停。
suspend fun scanBlankPages(
    db: AppDatabase,
    pdfViewModel: PdfViewModel,
    documentUri: String,
    need: Int
): List<Int> {
    if (need <= 0) return emptyList()
    val found = mutableListOf<Int>()
    val count = pdfViewModel.pageCount.value
    var checked = 0
    var p = 0
    while (p < count && found.size < need) {
        checked++
        try {
            val strokes = db.strokeDao().getStrokesForPageSync(documentUri, p)
            val texts = db.textAnnotationDao().getForPageSync(documentUri, p)
            val images = db.imageAnnotationDao().getForPageSync(documentUri, p)
            val dbEmpty = strokes.isEmpty() && texts.isEmpty() && images.isEmpty()
            if (dbEmpty) {
                val bmp = withTimeoutOrNull(1200) {
                    pdfViewModel.getPageBitmap(p).filterNotNull().first()
                } ?: pdfViewModel.getPageBitmap(p).value
                val ratio = if (bmp != null) whiteRatioOfBitmap(bmp) else 0f
                if (isBlankPage(true, ratio)) found.add(p)
            }
        } catch (t: Throwable) {
            Log.w("InkFlowDbg", "blankscan p=$p failed: $t")
        }
        p++
    }
    Log.d("InkFlowDbg", "BLANKSCAN checked=$checked need=$need used=$found")
    return found
}
