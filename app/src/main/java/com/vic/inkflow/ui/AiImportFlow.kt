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
 * AI 引入管線（勾選混排：文字切分＋公式像素裁圖＋KaTeX 第二引擎 → 混合排版 → 掃空白頁 → 寫入 → 跳轉）。
 * 純流程編排，無 Compose 依賴；狀態（aiPickMode 等）由呼叫方持有。
 * 管線任何一步炸了都只 Toast，不閃退。
 */

/**
 * KaTeX 第二引擎：有 TeX 源的數學塊渲染成圖（Main thread；失敗回空，上層退文字）。
 * 回傳 blockId → RenderedMath 與失敗計數。
 */
suspend fun renderMathBlocks(
    context: Context,
    mathBlocks: List<AiMathBlock>
): Pair<Map<String, RenderedMath>, Int> {
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
    return rendered to renderFail
}

/** 勾選混排插入：一批勾選＝文字切分＋公式像素裁圖（Main），放置與純文字路共用。 */
fun CoroutineScope.importPickedJson(
    json: String,
    context: Context,
    viewModel: EditorViewModel,
    pdfViewModel: PdfViewModel,
    db: AppDatabase,
    documentUri: String,
    sourcePage: Int,
    onRequestPage: (Int) -> Unit,
    activity: Activity?,
    webView: android.webkit.WebView?
) {
    if (json.isBlank()) return
    launch {
        try {
            importPickedJsonInner(context, viewModel, pdfViewModel, db, documentUri, sourcePage, onRequestPage, activity, webView, json)
        } catch (t: Throwable) {
            Log.e("InkFlowDbg", "import picked failed", t)
            try {
                Toast.makeText(context, "插入失敗：${t.message}", Toast.LENGTH_LONG).show()
            } catch (_: Throwable) { }
        }
    }
}

private data class Pick(val kind: Char, val text: String, val rect: WebCrop.CssBox?)

private fun parsePicks(json: String): List<Pick> {
    val out = mutableListOf<Pick>()
    try {
        val arr = org.json.JSONArray(json)
        for (i in 0 until minOf(arr.length(), 40)) {
            val o = arr.optJSONObject(i) ?: continue
            val k = o.optString("k", "t").firstOrNull() ?: 't'
            val t = o.optString("t", "").take(20000)
            var box: WebCrop.CssBox? = null
            try {
                val r = o.optJSONArray("r")
                if (r != null && r.length() >= 4) {
                    box = WebCrop.CssBox(
                        r.optDouble(0).toFloat(), r.optDouble(1).toFloat(),
                        r.optDouble(2).toFloat(), r.optDouble(3).toFloat()
                    )
                }
            } catch (_: Throwable) { }
            out.add(Pick(if (k == 'm') 'm' else 't', t, box))
        }
    } catch (_: Throwable) { }
    return out
}

suspend fun importPickedJsonInner(
    context: Context,
    viewModel: EditorViewModel,
    pdfViewModel: PdfViewModel,
    db: AppDatabase,
    documentUri: String,
    sourcePage: Int,
    onRequestPage: (Int) -> Unit,
    activity: Activity?,
    webView: android.webkit.WebView?,
    json: String
) {
    val picks = parsePicks(json)
    if (picks.isEmpty()) {
        Toast.makeText(context, "沒有可插入的內容", Toast.LENGTH_SHORT).show()
        return
    }
    // 保序拼塊：文字切分，含公式節點走像素裁圖（Main；失敗退文字路）
    val blocks = mutableListOf<AiBlock>()
    data class Shot(val id: String, val box: WebCrop.CssBox, val fallback: String)
    val shots = mutableListOf<Shot>()
    var si = 0
    for (p in picks) {
        if (p.kind == 'm' && p.rect != null) {
            val id = "s${si++}"
            blocks.add(AiMathBlock(id, html = "", display = true, fallback = p.text))
            shots.add(Shot(id, p.rect, p.text))
        } else if (p.text.isNotBlank()) {
            // 數學味文字塊：整塊截圖（徽章會變，內容不會變）；截不到退文字路
            if (p.rect != null && hasMathScent(p.text)) {
                val id = "s${si++}"
                blocks.add(AiMathBlock(id, html = "", display = false, fallback = p.text))
                shots.add(Shot(id, p.rect, p.text))
            } else {
                blocks.addAll(withContext(Dispatchers.Default) { splitAiBlocks(p.text) })
            }
        }
    }
    if (blocks.isEmpty()) {
        Toast.makeText(context, "沒有可插入的內容", Toast.LENGTH_SHORT).show()
        return
    }
    val rendered = mutableMapOf<String, RenderedMath>()
    var cropFail = 0
    if (shots.isNotEmpty() && activity != null && webView != null) {
        for (s in shots) {
            try {
                val rm = WebCrop.cropFormula(activity, webView, s.box, s.id)
                if (rm != null) rendered[s.id] = rm else cropFail++
            } catch (t: Throwable) {
                Log.w("InkFlowDbg", "crop failed ${s.id}: $t")
                cropFail++
            }
        }
    } else if (shots.isNotEmpty()) {
        cropFail = shots.size
    }
    val resolved = resolveAiBlocks(blocks, rendered)
    // 第二引擎：fallback 文字裡殘留的 $TeX$（data-math 還原的）走 KaTeX 渲染
    val katexTargets = resolved.filterIsInstance<AiMathBlock>().filter { it.id !in rendered }
    var katexFail = 0
    if (katexTargets.isNotEmpty()) {
        val (km, kf) = renderMathBlocks(context, katexTargets)
        rendered.putAll(km)
        katexFail = kf
    }
    val mathById = resolved.filterIsInstance<AiMathBlock>().associateBy { it.id }
    val pages = withContext(Dispatchers.Default) {
        paginateAiBlocks(resolved, rendered, viewModel.modelWidth, viewModel.modelHeight)
    }
    if (pages.isEmpty()) {
        Toast.makeText(context, "沒有可插入的內容", Toast.LENGTH_SHORT).show()
        return
    }
    val failBits = listOf(
        if (cropFail > 0) "${cropFail} 式裁圖失敗" else "",
        if (katexFail > 0) "${katexFail} 式渲染失敗" else ""
    ).filter { it.isNotEmpty() }
    val failNote = if (failBits.isNotEmpty()) "（" + failBits.joinToString("，") + "已退文字）" else ""
    placePages(context, viewModel, pdfViewModel, db, documentUri, sourcePage, onRequestPage, pages, mathById, failNote)
}

/**
 * 共用放置：掃空白頁 → 不夠開新頁 → 寫入（文字/圖＋TeX 存檔）→ 跳轉 → Toast。
 * importRawTextInner 與 importPickedJsonInner 共用，行為一致。
 */
suspend fun placePages(
    context: Context,
    viewModel: EditorViewModel,
    pdfViewModel: PdfViewModel,
    db: AppDatabase,
    documentUri: String,
    sourcePage: Int,
    onRequestPage: (Int) -> Unit,
    pages: List<List<Placed>>,
    mathById: Map<String, AiMathBlock>,
    failNote: String
) {
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
                    // TeX 源存檔（截圖塊 html 為空→存空字串，編輯時提示無源可改）
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
        Toast.makeText(context, "已插入 ${pages.sumOf { it.size }} 段（公式圖 ${mathCount}，${placed} 頁" + (if (blanks.isNotEmpty()) "，含空白頁再利用" else "") + "）" + failNote, Toast.LENGTH_SHORT).show()
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
