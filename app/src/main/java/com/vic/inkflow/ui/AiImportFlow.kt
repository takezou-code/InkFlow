package com.vic.inkflow.ui

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.vic.inkflow.data.AI_MARGIN_H
import com.vic.inkflow.data.AiBlock
import com.vic.inkflow.data.AiMathBlock
import com.vic.inkflow.data.MATH_RENDER_REFERENCE_W_PX
import com.vic.inkflow.data.MathSourceEntity
import com.vic.inkflow.data.Placed
import com.vic.inkflow.data.RenderedMath
import com.vic.inkflow.data.continueTop
import com.vic.inkflow.data.hasMathScent
import com.vic.inkflow.data.isBlankPage
import com.vic.inkflow.data.isSelfPage
import com.vic.inkflow.data.katexSourceOf
import com.vic.inkflow.data.katexTargets
import com.vic.inkflow.data.mathBlockHtml
import com.vic.inkflow.data.measureContentBottom
import com.vic.inkflow.data.paginateAiBlocks
import com.vic.inkflow.data.resolveAiBlocks
import com.vic.inkflow.data.splitAiBlocks
import com.vic.inkflow.data.repository.InkFlowRepositories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileOutputStream

/**
 * AI 引入管線（勾選混排：文字切分＋公式像素裁圖＋KaTeX 第二引擎 → 混合排版 → 掃空白頁 → 寫入 → 跳轉）。
 * 純流程編排，無 Compose 依賴；狀態（aiPickMode 等）由呼叫方持有。
 * 管線任何一步炸了都只 Toast，不閃退。
 */

/**
 * KaTeX 第二引擎：有 TeX 源的數學塊渲染成圖（Main thread；失敗回空，上層退文字）。
 * 回傳 blockId → RenderedMath 與失敗計數。
 *
 * 上限 [MAX_RENDER]：渲染序列化在主執行緒（MathSnapshot 用隱藏 WebView），
 * 一批全失敗時不能讓 UI 卡到分鐘級；超過的算失敗，照樣退文字。
 */
suspend fun renderMathBlocks(
    context: Context,
    mathBlocks: List<AiMathBlock>,
    maxRender: Int = MAX_RENDER,
    useMathJax: Boolean = false
): Pair<Map<String, RenderedMath>, Int> {
    val rendered = mutableMapOf<String, RenderedMath>()
    var renderFail = 0
    if (mathBlocks.isNotEmpty()) {
        val act = context as? Activity
        if (act != null) {
            if (useMathJax) MathSnapshotMj.ensure(act) else MathSnapshot.ensure(act)
        }
        for ((i, mb) in mathBlocks.withIndex()) {
            var ok = false
            if (i >= maxRender) {
                renderFail++
                continue
            }
            try {
                val src = katexSourceOf(mb)
                val renderSrc = if (useMathJax) src else src.replace("\\boxed", "")
                val bmp = if (useMathJax) MathSnapshotMj.render(mathBlockHtml(renderSrc))
                else MathSnapshot.render(mathBlockHtml(renderSrc))
                if (bmp != null) {
                    // 經 PdfManager 漏斗落 workingDir：跟圖庫/相機圖同一種命。
                    // 舊碼寫 filesDir 根，還原不斷不斷（見漏斗註解）。
                    val f = com.vic.inkflow.util.PdfManager.newAnnotationImageFile(context, ".png")
                    FileOutputStream(f).use { out ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                    rendered[mb.id] = RenderedMath(f.absolutePath, bmp.width, bmp.height)
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

private const val MAX_RENDER = 12

/** 勾選混排插入：一批勾選＝文字切分＋公式像素裁圖（Main），放置與純文字路共用。 */
// 同文件同時只跑一批：管線含 600ms 截圖等待＋渲染，不加鎖兩批會互踩接續游標與頁碼。
private val importMutex = Mutex()

/** 勾選混排插入：一批勾選＝文字切分＋公式像素裁圖（Main），放置與純文字路共用。 */
fun CoroutineScope.importPickedJson(
    json: String,
    context: Context,
    viewModel: EditorViewModel,
    pdfViewModel: PdfViewModel,
    repos: InkFlowRepositories,
    documentUri: String,
    sourcePage: Int,
    onRequestPage: (Int) -> Unit,
    activity: Activity?,
    webView: android.webkit.WebView?,
    supportsMathCrop: Boolean = true,
    promoteComplexParas: Boolean = true,
    cropAllPicks: Boolean = false,
    useMathJax: Boolean = false,
    // 錨定頁提供者：寫入前一刻重讀已提交當前頁（呼叫方傳 { currentPageIndex }）。
    // 使用者中途翻頁就跟著走，不寫回舊頁。
    latestPage: () -> Int = { sourcePage },
    // 放置結束（成功／失敗／中止皆觸發，Main 執行緒）：呼叫方用來開關導航壓制。
    onSettled: (() -> Unit)? = null
) {
    if (json.isBlank()) return
    launch {
        try {
            importMutex.withLock {
                importPickedJsonInner(context, viewModel, pdfViewModel, repos, documentUri, sourcePage, latestPage, onRequestPage, activity, webView, supportsMathCrop, promoteComplexParas, cropAllPicks, useMathJax, json)
            }
        } catch (t: Throwable) {
            Log.e("InkFlowDbg", "import picked failed", t)
            try {
                Toast.makeText(context, "插入失敗：${t.message}", Toast.LENGTH_LONG).show()
            } catch (_: Throwable) { }
        } finally {
            try {
                onSettled?.invoke()
            } catch (_: Throwable) { }
        }
    }
}

private data class Pick(val kind: Char, val text: String, val rect: WebCrop.CssBox?, val idx: Int)

private fun parsePicks(json: String): List<Pick> {
    val out = mutableListOf<Pick>()
    try {
        val arr = org.json.JSONArray(json)
        for (i in 0 until minOf(arr.length(), 40)) {
            val o = arr.optJSONObject(i) ?: continue
            val k = o.optString("k", "t").firstOrNull() ?: 't'
            val t = o.optString("t", "").take(20000)
            // 裁圖定位索引（collect 腳本留下的 __inkcropEls 位置）；舊資料沒這個欄位＝-1。
            val idx = o.optInt("i", -1)
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
            out.add(Pick(if (k == 'm') 'm' else 't', t, box, idx))
        }
    } catch (_: Throwable) { }
    return out
}

suspend fun importPickedJsonInner(
    context: Context,
    viewModel: EditorViewModel,
    pdfViewModel: PdfViewModel,
    repos: InkFlowRepositories,
    documentUri: String,
    sourcePage: Int,
    latestPage: () -> Int,
    onRequestPage: (Int) -> Unit,
    activity: Activity?,
    webView: android.webkit.WebView?,
    supportsMathCrop: Boolean = true,
    promoteComplexParas: Boolean = true,
    cropAllPicks: Boolean = false,
    useMathJax: Boolean = false,
    json: String
) {
    Log.d("InkFlowDbg", "IMPORT begin sourcePage=$sourcePage jsonLen=${json.length}")
    val picks = parsePicks(json)
    if (picks.isEmpty()) {
        Toast.makeText(context, "沒有可插入的內容", Toast.LENGTH_SHORT).show()
        return
    }
    // 保序拼塊：ChatGPT 走「整塊像素裁圖（可分段）」，Gemini 走「文字切分＋數學味裁圖」。
    // 兩條路共用同一套放置與寫入；差別只在取像方式。
    val blocks = mutableListOf<AiBlock>()
    val rendered = mutableMapOf<String, RenderedMath>()
    var cropFail = 0
    var si = 0
    val canCrop = activity != null && webView != null
    val hasRect = picks.any { it.rect != null || it.idx >= 0 }
    val willCrop = canCrop && hasRect && (cropAllPicks || supportsMathCrop)
    // F1：等一幀，讓圈選高亮殘影先退（否則裁到藍 tint，反白後變米黃）
    if (willCrop) kotlinx.coroutines.delay(600)
    // Gemini 舊路徑：批次前記下捲動位置，整批拍完捲回原位。
    // ChatGPT 的分段裁圖自己管捲動（每個 pick 內 save/restore），不必在這裡包一層。
    val legacyScroll = canCrop && !cropAllPicks && picks.any { it.rect != null }
    if (legacyScroll) WebCrop.saveScroll(webView!!)
    try {
        for (p in picks) {
            val blockCrop = cropAllPicks && canCrop && (p.rect != null || p.idx >= 0)
            val mathCrop = !cropAllPicks && supportsMathCrop && p.rect != null && canCrop &&
                (p.kind == 'm' || (p.text.isNotBlank() && hasMathScent(p.text)))
            when {
                blockCrop -> {
                    // 自動分段：每一段都必須「放進一頁不被縮小」。
                    // 顯示高 = 圖高(px) × 內容寬 / 參考寬，所以能容納的最大圖高由紙張決定；
                    // 換算回 CSS px 就是這次裁圖的切塊高度。塊比它高 → 自動切成 N 段。
                    val devScale = webView!!.scale.takeIf { it > 0f }
                        ?: context.resources.displayMetrics.density
                    val contentWpt = (viewModel.modelWidth - AI_MARGIN_H * 2f).coerceAtLeast(1f)
                    val maxHpt = (viewModel.modelHeight - 128f).coerceAtLeast(1f)
                    val boxW = (p.rect?.w ?: 400f).coerceAtLeast(1f)
                    val pxW = (boxW * devScale).coerceAtLeast(1f)
                    val shownScale = minOf(
                        contentWpt / MATH_RENDER_REFERENCE_W_PX,
                        contentWpt / pxW
                    ).coerceAtLeast(0.0001f)
                    val maxChunkCss = ((maxHpt / shownScale / devScale) * 0.94f)
                        .coerceIn(240f, 1400f)
                    val blockH = p.rect?.h ?: 0f
                    if (blockH > 0f) {
                        Log.d(
                            "InkFlowDbg",
                            "IMPORT-SPLIT idx=${p.idx} blockH=${blockH.toInt()} maxChunk=${maxChunkCss.toInt()} " +
                                "expectN=${kotlin.math.ceil(blockH / maxChunkCss).toInt()}"
                        )
                    }
                    val rms = try {
                        WebCrop.cropPickedBlock(
                            activity!!, webView!!, p.idx,
                            p.rect ?: WebCrop.CssBox(0f, 0f, 1f, 1f),
                            "p$si",
                            maxChunkCss = maxChunkCss
                        )
                    } catch (t: Throwable) {
                        Log.w("InkFlowDbg", "block crop failed p$si: $t")
                        emptyList()
                    }
                    if (rms.isEmpty()) {
                        cropFail++
                        Log.w(
                            "InkFlowDbg",
                            "IMPORT-CROP-FAIL idx=${p.idx} h=${p.rect?.h} text=${p.text.take(40).replace("\n", " ")}"
                        )
                        // 退文字：含 TeX 的段落仍會被第二引擎（MathJax）補成圖。
                        blocks.addAll(
                            withContext(Dispatchers.Default) {
                                splitAiBlocks(p.text, promoteInlineMath = true)
                            }
                        )
                    } else {
                        // 一個 pick 可能切成多張（超高塊）：依序排入，保原順序。
                        for (rm in rms) {
                            val id = "s${si++}"
                            rendered[id] = rm
                            blocks.add(AiMathBlock(id, html = "", display = true, fallback = ""))
                        }
                    }
                }
                mathCrop -> {
                    val id = "s${si++}"
                    val rm = try {
                        WebCrop.cropFormula(activity!!, webView!!, p.idx, p.rect!!, id)
                    } catch (t: Throwable) {
                        Log.w("InkFlowDbg", "crop failed $id: $t")
                        null
                    }
                    if (rm != null) rendered[id] = rm else cropFail++
                    blocks.add(AiMathBlock(id, html = "", display = true, fallback = p.text))
                }
                p.text.isNotBlank() -> blocks.addAll(
                    withContext(Dispatchers.Default) {
                        splitAiBlocks(p.text, promoteInlineMath = promoteComplexParas)
                    }
                )
            }
        }
    } finally {
        if (legacyScroll) WebCrop.restoreScroll(webView!!)
    }
    if (blocks.isEmpty()) {
        Toast.makeText(context, "沒有可插入的內容", Toast.LENGTH_SHORT).show()
        return
    }
    // 第二引擎：裁圖失敗但還有 TeX 的塊走離線渲染（ChatGPT=MathJax，Gemini=KaTeX）。
    // 必須在 resolveAiBlocks 之前：那一步會把沒有圖的數學塊換成文字。
    var katexFail = 0
    val katexTodo = katexTargets(blocks, rendered)
    if (katexTodo.isNotEmpty()) {
        Log.d("InkFlowDbg", "KATEX begin n=${katexTodo.size} cropFail=$cropFail jax=$useMathJax")
        val (km, kf) = renderMathBlocks(context, katexTodo, useMathJax = useMathJax)
        rendered.putAll(km)
        katexFail = kf
        Log.d("InkFlowDbg", "KATEX end ok=${km.size} fail=$kf")
    }
    val resolved = resolveAiBlocks(blocks, rendered)
    val mathById = resolved.filterIsInstance<AiMathBlock>().associateBy { it.id }
    // 錨定：寫入前一刻重讀已提交當前頁（600ms 截圖等待＋渲染期間使用者可能已翻頁）。
    // 有空位就接著寫，沒空位就在錨定頁後面開新頁；不再掃全文件空白頁（舊行為會把一批回覆打散到文件頭）。
    val countNow = pdfViewModel.pageCount.value
    val anchorPage = latestPage().coerceIn(0, (countNow - 1).coerceAtLeast(0))
    if (anchorPage != sourcePage) Log.d("InkFlowDbg", "IMPORT anchor moved src=$sourcePage anchor=$anchorPage")
    // 接續錨定頁：自家頁且有空間 → 首頁游標從 contentBottom 開始（IO 量測，null=開新頁）
    val contTop = withContext(Dispatchers.IO) {
        resolveContinueTop(repos, pdfViewModel, documentUri, anchorPage, viewModel.modelHeight)
    }
    val pages = withContext(Dispatchers.Default) {
        paginateAiBlocks(
            resolved, rendered, viewModel.modelWidth, viewModel.modelHeight,
            startTop = contTop ?: 64f, // = paginate 預設 marginTop
            fontMetrics = ::androidFontMetrics
        )
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
    // 這一整批回答＝一條文字流：之後改任何一段，下面的自動重排（見 EditorViewModel 的
    // planFlowReflow）。id 每批不同，所以兩次匯入是兩條獨立的流，不會互相推擠。
    val flowId = "ai-" + java.util.UUID.randomUUID()
    placePages(context, viewModel, pdfViewModel, repos, documentUri, anchorPage, onRequestPage, pages, mathById, failNote,
        headTarget = if (contTop != null) anchorPage else null, flowId = flowId)
}

/**
 * 共用放置：接續錨定頁 → 不夠在錨定頁後面開新頁 → 寫入（文字/圖＋TeX 存檔）→ 跳轉 → Toast。
 * 不掃全文件空白頁：一批回覆固定落在錨定區，不打散。
 */
suspend fun placePages(
    context: Context,
    viewModel: EditorViewModel,
    pdfViewModel: PdfViewModel,
    repos: InkFlowRepositories,
    documentUri: String,
    anchorPage: Int,
    onRequestPage: (Int) -> Unit,
    pages: List<List<Placed>>,
    mathById: Map<String, AiMathBlock>,
    failNote: String,
    // 接續錨定頁：pages[0] 直接寫回此頁（不開新頁），其餘緊貼其後開新頁；null=全部開新頁
    headTarget: Int? = null,
    /** 這批文字的文字流 id（見 EditorViewModel 的 reflow）；null = 全部當獨立文字框。 */
    flowId: String? = null
) {
    val count = pdfViewModel.pageCount.value
    if (count <= 0) {
        Toast.makeText(context, "文件尚未載入，請稍後再試", Toast.LENGTH_SHORT).show()
        return
    }
    val anchor = anchorPage.coerceIn(0, count - 1)
    var after = anchor
    var placed = 0
    var mathCount = 0
    var firstTarget = -1
    suspend fun writeOne(pageIdx: Int, page: List<Placed>) {
        if (firstTarget < 0) firstTarget = pageIdx
        Log.d("InkFlowDbg", "IMPORT-WRITE page=$pageIdx segs=${page.size} kinds=${page.map { if (it is Placed.T) "T" else "I" }}")
        // 點陣圖保證：重開空窗期建的 flow 可能永久 null，先預取＋等圖再寫入跳轉
        pdfViewModel.prefetchPage(pageIdx)
        withTimeoutOrNull(3000) {
            pdfViewModel.getPageBitmap(pageIdx).filter { it != null }.first()
        }
        // 落定等待：insertImported* 是 fire-and-forget（VM 側 launch），這裡用 DB 計數確認提交，
        // 否則 placed／log 跑在 DB 前面，連打兩批也會互相踩。
        val wantT = page.count { it is Placed.T }
        val wantI = page.count { it is Placed.I }
        val (textBefore, imgBefore) = withContext(Dispatchers.IO) {
            repos.texts.getForPageSync(documentUri, pageIdx).size to
                repos.images.getForPageSync(documentUri, pageIdx).size
        }
        page.forEach { pl ->
            when (pl) {
                is Placed.T -> viewModel.insertImportedText(
                    documentUri, pageIdx, pl.t.text, pl.t.modelX, pl.t.modelY, pl.t.fontSize,
                    flowId = flowId
                )
                is Placed.I -> {
                    val imageUri = Uri.fromFile(java.io.File(pl.path)).toString()
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
                                repos.mathSources.insert(
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
        val settled = withContext(Dispatchers.IO) {
            withTimeoutOrNull(5000) {
                while (true) {
                    val t = repos.texts.getForPageSync(documentUri, pageIdx).size
                    val im = repos.images.getForPageSync(documentUri, pageIdx).size
                    if (t >= textBefore + wantT && im >= imgBefore + wantI) return@withTimeoutOrNull true
                    kotlinx.coroutines.delay(100)
                }
                @Suppress("UNREACHABLE_CODE") false
            } ?: false
        }
        if (!settled) Log.w("InkFlowDbg", "IMPORT-WRITE page=$pageIdx not settled in 5s (wantT=$wantT wantI=$wantI)")
        placed++
    }
    var rest = pages
    if (headTarget != null && rest.isNotEmpty()) {
        val head = headTarget.coerceIn(0, pdfViewModel.pageCount.value - 1)
        writeOne(head, rest[0])
        rest = rest.drop(1)
    }
    // R2 結構操作：開新頁會讓舊復原格頁號錯位，動頁前清棧。
    // 同批先寫入的匯入格一併作廢（整批匯入超出復原範圍）。
    var stacksCleared = false
    for (page in rest) {
        if (!stacksCleared) {
            viewModel.clearUndoStacks()
            stacksCleared = true
        }
        if (!insertOnePageAfter(pdfViewModel, viewModel, context, documentUri, after)) break
        after += 1
        writeOne(after, page)
    }
    if (placed > 0) {
        Log.d("InkFlowDbg", "CONTINUE place anchor=$anchor headTarget=$headTarget firstTarget=$firstTarget placed=$placed")
        onRequestPage(firstTarget)
        Toast.makeText(context, "已插入 ${pages.sumOf { it.size }} 段（公式圖 ${mathCount}，${placed} 頁" + (if (headTarget != null) "，含接續當前頁" else "") + "）" + failNote, Toast.LENGTH_SHORT).show()
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

// 接續前頁：空白自家頁（DB 空＋紙白）→ 回 64f 直接寫當前頁頂；
// 有墨自家頁（DB 有墨＋紙大致白，原生 PDF 出局）且下方夠兩行 → 回接續游標；
// 否則 null（開新頁）。在 IO 執行緒呼叫。
suspend fun resolveContinueTop(
    repos: InkFlowRepositories,
    pdfViewModel: PdfViewModel,
    documentUri: String,
    sourcePage: Int,
    modelH: Float
): Float? {
    if (sourcePage < 0 || sourcePage >= pdfViewModel.pageCount.value) return null
    Log.d("InkFlowDbg", "CONTINUE begin p=$sourcePage pageCount=${pdfViewModel.pageCount.value} modelH=$modelH")
    try {
        val strokes = repos.strokes.getStrokesForPageSync(documentUri, sourcePage)
        val texts = repos.texts.getForPageSync(documentUri, sourcePage)
        val images = repos.images.getForPageSync(documentUri, sourcePage)
        val dbEmpty = strokes.isEmpty() && texts.isEmpty() && images.isEmpty()
        Log.d("InkFlowDbg", "CONTINUE DB p=$sourcePage strokes=${strokes.size} texts=${texts.size} images=${images.size} dbEmpty=$dbEmpty")
        val bmp = withTimeoutOrNull(1200) {
            pdfViewModel.getPageBitmap(sourcePage).filterNotNull().first()
        } ?: pdfViewModel.getPageBitmap(sourcePage).value
        val ratio = if (bmp != null) whiteRatioOfBitmap(bmp) else 0f
        // 空白當前頁：DB 空＋紙白 → 直接寫當前頁頂（64f = paginate 預設 marginTop），不開新頁。
        // 原生 PDF 有字（白比例低）或點陣拿不到 → 回 null 走開新頁，避免蓋住原文。
        if (dbEmpty) {
            Log.d("InkFlowDbg", "CONTINUE blank-gate p=$sourcePage ratio=$ratio blank=${isBlankPage(true, ratio)} bmpNull=${bmp == null}")
            if (bmp != null && isBlankPage(true, ratio)) return 64f
            return null
        }
        Log.d("InkFlowDbg", "CONTINUE gate p=$sourcePage ratio=$ratio self=${isSelfPage(dbEmpty, ratio)} bmpNull=${bmp == null}")
        if (!isSelfPage(dbEmpty, ratio)) {
            Log.d("InkFlowDbg", "CONTINUE skip p=$sourcePage ratio=$ratio (native?)")
            return null
        }
        val trace = mutableListOf<String>()
        val bottom = measureContentBottom(strokes.map { it.stroke }, texts, images, ::textMetricsOf, trace = trace) ?: run {
            Log.d("InkFlowDbg", "CONTINUE skip p=$sourcePage unmeasurable (stamp?)")
            return null
        }
        trace.forEach { Log.d("InkFlowDbg", "CONTINUE $it") }
        val top = continueTop(bottom, modelH, textMetricsOf(16f).first) // 16 = paginate 預設字號
        Log.d("InkFlowDbg", "CONTINUE decision p=$sourcePage bottom=$bottom top=$top modelH=$modelH")
        return top
    } catch (t: Throwable) {
        Log.w("InkFlowDbg", "continue p=$sourcePage failed: $t")
        return null
    }
}
