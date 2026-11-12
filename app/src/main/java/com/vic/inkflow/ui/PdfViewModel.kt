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

import com.vic.inkflow.data.repository.InkFlowRepositories

import com.vic.inkflow.data.AppDatabase

import com.vic.inkflow.util.PdfManager

import com.vic.inkflow.util.PageOpJournal

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.CoroutineScope

import kotlinx.coroutines.SupervisorJob

import kotlinx.coroutines.cancel

import kotlinx.coroutines.delay

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

        /**
         * 顯示渲染倍率的**下限**：低於此值連 100% 的紙都糊，而 100% 下的紙寬倍率
         * 已經大於 2，所以這個下限只在極端小視窗（分屏）才會生效。
         */
        internal const val MIN_RENDER_SCALE = 2f

        /**
         * 放大時追到這個倍率為止。再往上不是更好，是 OOM：A4 在 6x 是 3570×5052
         * px × 4 bytes ≈ 72 MB，一頁就把 LruCache（maxMemory/8）吃掉大半。
         * 真的需要更大解析度時，正確做法是渲染分塊瓦片，不是把整頁拉大。
         */
        internal const val MAX_ZOOM_RENDER_SCALE = 6f

        /**
         * 單頁位元組預算。取 [Runtime] 堆的 1/32（512MB → 16MB），上限 16MB。
         *
         * 為什麼從 1/12 收到 1/32：頁面圖的像素住在 **native heap**，不在 Java heap，
         * 而 GC 只看 Java heap。單頁 42MB 的時候，可見頁三四張就 130MB——
         * 加上捲動產生的垃圾，native heap 會直接撐爆（實測：原生堆用到 94% 就閃退）。
         * 16MB 已經足夠在任何螢幕上畫得清楚（A4 滿版約 1520×2150 = 13MB）。
         */
        internal fun pageBudgetBytes(maxMemoryBytes: Long): Long =
            (maxMemoryBytes / 32).coerceIn(4L * 1024 * 1024, 16L * 1024 * 1024)

        /**
         * 「便宜檔」的單頁位元組預算：捲動中與窗外頁用。
         *
         * 這是整個修法的關鍵數字。捲動時每一頁被掠過都會產生一張全解析度的頁面圖，
         * 掠過之後那張圖變成垃圾——但 Java heap 有 512MB，GC 不會為了幾十 MB 的
         * native 記憶體專門跑一趟，於是 native heap 只漲不跌，最後 OOM 閃退。
         *
         * 所以捲動中只畫「看得懂但便宜」的檔案（單頁 4MB 以內），落定後才升級。
         */
        internal const val CHEAP_PAGE_BYTES = 4L * 1024 * 1024

        /** 便宜檔的倍率下限：再低連版面都認不出來了。 */
        internal const val CHEAP_MIN_SCALE = 1f

        /**
         * 便宜檔的倍率：讓單頁位元組不超過 [CHEAP_PAGE_BYTES]。
         *
         * 純函式（可單測）：頁面尺寸 × 倍率 → 位元組，套 [CHEAP_PAGE_BYTES]。
         */
        internal fun cheapRenderScale(pageWidthPt: Float, pageHeightPt: Float): Float {
            if (pageWidthPt <= 0f || pageHeightPt <= 0f) return MIN_RENDER_SCALE
            val pixels = CHEAP_PAGE_BYTES / 4.0
            val scale = kotlin.math.sqrt(pixels / (pageWidthPt.toDouble() * pageHeightPt.toDouble()))
            return scale.toFloat().coerceIn(CHEAP_MIN_SCALE, MIN_RENDER_SCALE)
        }

        /**
         * 自上次 GC 以來產生多少位元組的頁面圖，超過這條線就收一次垃圾。
         *
         * 為什麼需要手動 GC：Android 8 之後 Bitmap 的像素在 native heap，
         * 而 ART 的 GC 只按 **Java heap** 的使用量決定要不要跑。這個 App 的
         * Java heap 有 512MB 且用量很低 → GC 幾乎不跑 → native heap 只漲不跌。
         * 實測：捲動 20 頁後 native 堆裡躺著 380MB 的點陣圖，之後 GC 一跑就掉回 154MB。
         *
         * 門檻給得寬（64MB）：它是安全網，不是常態路徑——正常捲動時
         * 便宜檔本來就只產 3MB 一頁，門檻不該被常常摸到。
         */
        internal const val GC_TRIGGER_BYTES = 64L * 1024 * 1024

        /**
         * 硬性門檻：累積到這裡就算還在捲也照收（那時不收就是等 OOM）。
         * 正常使用摸不到——便宜檔一頁 4MB，要捲 64 頁才到。
         */
        internal const val GC_FORCE_BYTES = 4 * GC_TRIGGER_BYTES

        /**
         * 捲動落定的判定窗：可見範圍停止變動多久算「落定」。
         *
         * Workspace 每個捲動幀都會呼叫 [setVisiblePageRange]；停止捲動後就不再呼叫。
         * 用它當「現在還在捲嗎」的開關：捲動中用便宜檔，落定後可見頁升級成全解析度。
         *
         * 為什麼從 350ms 放寬到 700ms：連續翻頁（一頁接一頁 swipe）的間隔大約
         * 0.4–0.6s，350ms 會讓**每一頁之間**都插一次全解析度重渲（單頁 17MB），
         * 使用者的感受就是「滑一下卡一下」。700ms 讓連續翻頁完全不觸發升級，
         * 真的停下來才變清楚。
         */
        internal const val SCROLL_SETTLE_MS = 700L

        /** 落定檢查的間隔：一個捲動階段只養一個 job，每 tick 醒一次看停了沒。 */
        private const val SCROLL_SETTLE_TICK_MS = 100L

        /**
         * 扣住的點陣圖（pending）在可見範圍外還留幾頁。
         *
         * 留一點是因為捲動方向會抖：剛捲出去又捲回來時圖還在，不必重渲。
         * 留太多就等於把「捲過的頁」整套釘在記憶體裡（那正是閃退的來源）。
         */
        private const val PENDING_KEEP_MARGIN = 3


        /**
         * **活著的**頁面點陣圖位元組預算（高清層）。
         *
         * 為什麼要有這條線：Android 8 之後 Bitmap 的像素住在 **native heap**，
         * 不在 Java heap 裡，所以 `Runtime.maxMemory()` 看不到它、GC 也不會幫你收。
         * 但頁面圖是整個 App 最大的記憶體來源（單頁 13MB 起跳，一份 190 頁的
         * 教科書 PDF 捲一圈就是幾百 MB）。
         *
         * 這條線真正擋住的是「無上限」：舊設計只有 `bitmapCache`（LruCache）有上限，
         * 但同一張圖還被 flow 的值拿著（UI 直接顯示它）——**LruCache 逐出 ≠ 圖被釋放**。
         * 於是捲過的每一頁都留在 native heap 裡，捲得越多漲得越多，
         * 最後 native heap 撐爆、App 直接閃退（實測：捲到一半 native heap 裡躺著
         * 13 張 13MB 的頁面圖，Bitmap (malloced) 合計 173MB）。
         *
         * 取堆的 1/8：可見頁（約 4 頁 × 13MB）放得下，又有上限，不會再往上長。
         */
        internal fun liveBitmapBudgetBytes(maxMemoryBytes: Long): Long =
            (maxMemoryBytes / 8).coerceAtLeast(48L * 1024 * 1024)

        /**
         * 縮圖層的活著位元組預算。縮圖單張只有 0.3MB，但頁數一多一樣會漲
         * （190 頁 × 0.3MB ≈ 60MB），所以一樣要天花板。
         */
        internal fun liveThumbnailBudgetBytes(maxMemoryBytes: Long): Long =
            (maxMemoryBytes / 20).coerceAtLeast(16L * 1024 * 1024)

        /**
         * 這一頁現在還值得渲嗎？
         *
         * - [hasSubscriber]：螢幕上有人在看（LazyColumn item 還活著）→ 一定要渲。
         * - [msSinceRequest]：沒人看時，只有「剛被要求過」才值得渲。
         *
         * 為什麼需要：捲動時 LazyColumn 會把**經過的頁**一頁組成，每一頁都會發起
         * 一次高清渲染。那些頁在排到隊時早就捲走了——渲它等於白燒 CPU、白吃一份
         * 13MB 的記憶體，還排在「你正在看的那頁」前面，於是捲動時每一頁都要等
         * 好幾個無關渲染排完才會變清晰 → 明顯卡頓。
         *
         * [msSinceRequest] 的存在是為了蓋住一個空窗：Compose 的 `collectAsState`
         * 是**下一幀**才訂閱，所以頁面剛組成的那一瞬間還沒人訂閱。
         * 給它一個短時間窗，剛組成的頁就不會被誤判成「沒人要」。
         */
        internal fun shouldRenderPage(hasSubscriber: Boolean, msSinceRequest: Long): Boolean =
            hasSubscriber || msSinceRequest <= REQUEST_FRESH_MS

        /** 剛被要求過的頁面，多久之內還算「有人要」（蓋住 collectAsState 的下一幀空窗）。 */
        internal const val REQUEST_FRESH_MS = 500L

        /**
         * 挑出要回收的頁（純函式，所以能單測）。
         *
         * 規則只有兩條，但兩條都是生死線：
         *
         *  1. **從最舊的开始砍。** 新的頁比較可能馬上被看見。
         *  2. **有人顯示的不砍。** 砍了螢幕上就是閃白，比記憶體吃緊更難受。
         *
         * 砍到活著位元組回到預算內就停。回不去也沒關係——那代表現在
         * 螢幕上的頁就已經超過預算了，這時寧可超一點也不能讓畫面跳掉。
         */
        internal fun selectColdEvictions(
            candidates: List<BitmapCandidate>,
            liveBytes: Long,
            budget: Long
        ): List<Int> {
            if (liveBytes <= budget) return emptyList()
            var total = liveBytes
            val out = mutableListOf<Int>()
            for (c in candidates.sortedBy { it.age }) {
                if (total <= budget) break
                if (c.hasSubscriber) continue
                out.add(c.index)
                total -= c.bytes
            }
            return out
        }

        /**
         * 這一頁允許的最大倍率：同時受三件事夾住。
         *
         *  - [MAX_RENDER_DIMENSION_PX]：絕對上限，決定單邊像素數（GPU／點陣圖皆然）。
         *  - 位元組預算：`w×h×4 ≤ budget`。這才是真正的 OOM 守門。
         *  - [MAX_ZOOM_RENDER_SCALE]：見該常數註解。
         *
         * 三者取最小，之後夾到 [MIN_RENDER_SCALE] 以上。
         * 純函式、無 Android 依賴，所以能直接釘單測。
         */
        internal fun maxScaleForPage(
            pageWidthPt: Float,
            pageHeightPt: Float,
            maxMemoryBytes: Long
        ): Float {
            if (pageWidthPt <= 0f || pageHeightPt <= 0f) return MIN_RENDER_SCALE
            val longest = maxOf(pageWidthPt, pageHeightPt)
            val dimCap = (MAX_RENDER_DIMENSION_PX / longest).toDouble()
            val pixels = pageBudgetBytes(maxMemoryBytes) / 4.0
            val budgetScale = kotlin.math.sqrt(pixels / (pageWidthPt.toDouble() * pageHeightPt.toDouble()))
            return minOf(dimCap, budgetScale, MAX_ZOOM_RENDER_SCALE.toDouble())
                .toFloat()
                .coerceAtLeast(MIN_RENDER_SCALE)
        }

        /**
         * 紙寬（已含縮放）→ 該頁需要的顯示倍率，夾到可行區間。
         *
         * 這裡的 `paperWidthPx` 必須是**紙寬**而不是視窗寬：縮放後紙比視窗寬，
         * 用視窗寬算會得到一個永遠不變的倍率，放大時 PDF 就只能被拉大而變糊
         * （墨是向量重畫所以不糊——於是同一頁上兩層清晰度打架）。
         */
        internal fun resolveRenderScale(paperWidthPx: Float, pageWidthPt: Float, maxScale: Float): Float {
            if (paperWidthPx <= 0f || pageWidthPt <= 0f) return MIN_RENDER_SCALE
            val ideal = paperWidthPx / pageWidthPt
            val hi = maxOf(MIN_RENDER_SCALE, maxScale)
            return ideal.coerceIn(MIN_RENDER_SCALE, hi)
        }

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

    /**
     * 一張活著的頁面圖，給回收政策用的描述。
     *
     * @param bytes 這張圖佔多少位元組
     * @param hasSubscriber 螢幕上有人在看（LazyColumn item 還活著）→ 不能砍
     * @param age 越舊越先被砍
     */
    internal data class BitmapCandidate(
        val index: Int,
        val bytes: Long,
        val hasSubscriber: Boolean,
        val age: Long
    )

    private val repos by lazy { InkFlowRepositories(AppDatabase.getDatabase(getApplication())) }

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
        repos.pageOps.shiftDocYBelow(documentUri, y0, dy)
    }

    /** 刪頁：第 index 頁的列已先刪除，docY >= (index+1)*stride 上移一頁。 */
    private suspend fun shiftDocYForDeleteLocked(documentUri: String, deletedIndex: Int) {
        val stride = currentStrideOrNull() ?: return
        val y0 = (deletedIndex + 1) * stride
        repos.pageOps.shiftDocYBelow(documentUri, y0, -stride)
    }

    /** 移頁：中間區間整批平移一頁，被搬頁跳到目標（兩區間不相交，順序無關）。 */
    private suspend fun shiftDocYForMoveLocked(documentUri: String, fromIndex: Int, toIndex: Int) {
        val stride = currentStrideOrNull() ?: return
        if (fromIndex == toIndex) return
        if (fromIndex < toIndex) {
            // (from, to] 上移一頁：docY [(from+1)*s, (to+1)*s) -= s
            val y0 = (fromIndex + 1) * stride
            val y1 = (toIndex + 1) * stride
            repos.pageOps.shiftDocYRange(documentUri, y0, y1, -stride)
        } else {
            // [to, from) 下移一頁：docY [to*s, from*s) += s
            val y0 = toIndex * stride
            val y1 = fromIndex * stride
            repos.pageOps.shiftDocYRange(documentUri, y0, y1, stride)
        }
        // 被搬頁 from -> to
        val pageDy = (toIndex - fromIndex) * stride
        repos.pageOps.shiftDocYRange(documentUri, fromIndex * stride, (fromIndex + 1) * stride, pageDy)
    }

    fun getBookmarkedPages(documentUri: String): kotlinx.coroutines.flow.Flow<List<Int>> {
        return repos.bookmarks.getBookmarkedPages(documentUri)
    }

    fun toggleBookmark(documentUri: String, pageIndex: Int, isBookmarked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (isBookmarked) {
                repos.bookmarks.insert(com.vic.inkflow.data.BookmarkEntity(documentUri, pageIndex))
            } else {
                repos.bookmarks.deleteForPage(documentUri, pageIndex)
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
     * 頁面需求時間戳（epoch ms）：這一頁是「什麼時候被要求渲染的」。
     *
     * 用途：判斷排到隊時這一頁還要不要渲（見 [shouldRenderPage]）。
     * 捲動時經過的頁也會被要求，但等它排到時早就沒人看了。
     */
    private val bitmapRequestedAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val thumbnailRequestedAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /**
     * 頁面圖的年齡序號：數字越小越舊，回收時從最舊的砍。
     *
     * 為什麼要自己記而不用 LruCache 的順序：LruCache 只知道自己那份，
     * 而 flow 的值才是真正把圖釘在記憶體裡的那個（LruCache 逐出 ≠ 圖被釋放）。
     */
    private val bitmapAge = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val thumbnailAge = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /**
     * 每頁「實際用哪個倍率渲出來的」（只記高清層）。
     *
     * 為什麼要記：決定「這頁夠清楚了沒」必須看**事實**，不能看「計畫已經套用過」。
     * 捲動中新增的頁是便宜檔，若只看計畫的 bookkeeping 就會漏掉它們，
     * 使用者停在那一頁上永遠是模糊的。見 [applyRenderPlan]。
     */
    private val bitmapScale = java.util.concurrent.ConcurrentHashMap<Int, Float>()
    private val ageSeq = java.util.concurrent.atomic.AtomicLong(0)

    private fun stampAge(ages: MutableMap<Int, Long>, index: Int) {
        ages[index] = ageSeq.incrementAndGet()
    }

    /**
     * 渲染代際門控（超大檔頁操作防擁塞）：
     * 每次關 renderer（頁操作/重開）就世代+1，並取消整個 renderScope。
     * 等鎖中的舊渲染醒來發現世代變了直接丟棄，不佔鎖不畫圖；
     * 等待中的直接被 cancel，頁操作不用排 37 秒的隊。
     */
    private var renderGen = 0L
    private var renderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * P0 快滑世代：快旗每次升/落（transition）+1，排隊中的高清渲染憑票驗證，
     * 被超車的直接丟棄（落定由 ensureHighQualityAround 按序只補 5 頁）。
     * 頁操作的 renderGen 不動，兩條世代正交。
     */
    private var scrollGen = 0L
    /**
     * 渲染hold：捲動中（不限快慢）高清一律等待，靜止 0.5s 落定才放行。
     * 縮圖不受影響（掠過就靠它）。由 Workspace 每次排放舉旗、落定放旗。
     */
    private val renderHold = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 捲動中：凍結高清（已排隊的等，放行後憑票＋訂閱雙驗才渲）。 */
    fun holdRenders() {
        renderHold.set(true)
    }

    /** 落定：放行高清。票不換代——排隊中的可見頁按原順序直接渲，不重排。 */
    fun releaseRenders() {
        renderHold.set(false)
    }

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

    /**
     * P0 落定：只貼可見範圍，其餘保留待下次（避免 N 個中間頁同幀 Crossfade＋GPU 爆發）。
     * 不碰 rendersPaused（快滑路徑從不立暫停旗，與雙指手勢路徑正交）。
     */
    fun flushPendingRenders(visible: IntRange) {
        visible.forEach { index ->
            pendingBitmaps.remove(index)?.let { bmp ->
                bitmapFlowCache[index]?.let { flow -> if (flow.value == null) flow.value = bmp }
            }
            pendingThumbs.remove(index)?.let { bmp ->
                thumbnailFlowCache[index]?.let { flow -> if (flow.value == null) flow.value = bmp }
            }
        }
        // 窗外還扣著的圖直接丟：那些頁已經被捲過去了，貼上只是白佔記憶體。
        // 舊碼只清可見頁 → 扣著的圖永遠留在 map 裡，捲一圈就積一整套文件。
        dropPendingOutside(visible, pendingBitmaps, bitmapAge, bitmapScale)
        dropPendingOutside(visible, pendingThumbs, thumbnailAge, null)
    }

    /** 丟掉 [visible]±[PENDING_KEEP_MARGIN] 之外扣著的點陣圖（含它的年齡／倍率紀錄）。 */
    private fun dropPendingOutside(
        visible: IntRange,
        pending: MutableMap<Int, Bitmap>,
        ages: MutableMap<Int, Long>?,
        scales: MutableMap<Int, Float>?
    ) {
        if (pending.isEmpty()) return
        val lo = visible.first - PENDING_KEEP_MARGIN
        val hi = visible.last + PENDING_KEEP_MARGIN
        val it = pending.keys.iterator()
        while (it.hasNext()) {
            val index = it.next()
            if (index in lo..hi) continue
            it.remove()
            ages?.remove(index)
            scales?.remove(index)
        }
    }

    private val _pageSizeVersion = MutableStateFlow(0)
    val pageSizeVersion: StateFlow<Int> = _pageSizeVersion.asStateFlow()

    private fun bumpPageSizeVersion() { _pageSizeVersion.value += 1 }

    /**
     * v6：縮放感知的**分級**渲染計畫。
     *
     * ## 為什麼要分兩級
     *
     * 直覺做法是「倍率直接乘 docZoom」，但那是 OOM：A4 在 docZoom=4 要渲成
     * 6400×9060 px × 4 bytes ≈ 221 MB，一頁就爆。而且記憶體吃光之後使用者看到的
     * 不是「清晰」，是**捲動時每頁都在重渲**——比糊更糟。
     *
     * 所以：只有**可見頁**吃滿倍率，窗外頁維持低清，捲過去時才升級。
     * 使用者看得到的永遠是清楚的，看不到的省下來。這也是主流 PDF 閱讀器的做法。
     *
     * ## 兩個倍率各是什麼
     *
     *  - [loRenderScale]：窗外頁用。**等於舊的 `setDisplayRenderScale` 語義**
     *    （視窗寬／頁寬，夾 2..3）——舊行為原封不動保留，所以沒縮放時行為完全一致，
     *    這條改動對 100% 閱讀是零風險的。
     *  - [hiRenderScale]：可見頁用。理想倍率是**紙寬**／頁寬（紙寬含 docZoom），
     *    再用 [maxScaleForPage] 夾到記憶體容得下的上限。
     */
    @Volatile private var loRenderScale = MIN_RENDER_SCALE
    @Volatile private var hiRenderScale = MIN_RENDER_SCALE

    /** 吃滿倍率的頁範圍；其餘用 [loRenderScale]。空 = 全部低清。 */
    @Volatile private var hiTierRange: IntRange = IntRange.EMPTY

    /** 已套用的計畫，用來算出「哪幾頁的**有效倍率**真的變了」——只有那些頁需要重渲。 */
    private var appliedHiScale = -1f
    private var appliedLoScale = -1f
    private var appliedRange: IntRange = IntRange.EMPTY
    private var appliedScrolling = false

    /** 換文件／重開 renderer 後把計畫的 bookkeeping 歸零，下一次落定一定會重檢。 */
    private fun resetRenderPlanBookkeeping() {
        appliedHiScale = -1f
        appliedLoScale = -1f
        appliedRange = IntRange.EMPTY
        appliedScrolling = false
    }

    /**
     * 最後一次「可見頁範圍變動」的時刻（uptime ms）。
     *
     * Workspace 在每個捲動幀都會呼叫 [setVisiblePageRange]，停止捲動後就不再呼叫。
     * 它就是「現在還在捲嗎」的答案——整個分級渲染的開關。
     */
    @Volatile private var lastVisibleChangeMs = 0L

    /** 落定後把可見頁升級成全解析度的排程。 */
    private var settleUpgradeJob: Job? = null

    /**
     * 供 UI 納入 remember key 的重取信號（PageWorkspace 的 `remember(index, renderEpoch)`）。
     *
     * 縮放**不**再推它：那是舊「evictAll + 置 null」時代的機制，靠「強制重取 flow」
     * 讓倍率變化生效，而強制重取正是縮放閃白的來源之一。現在改為原地重渲
     * （[applyRenderPlan]），flow 實例不變，所以縮放不需要它。
     *
     * 它仍保留給頁操作（插刪頁 → 世代更換）這類「資料本身變了、必須重取」的路徑，
     * 那些情況推它是對的。**不要**拿它來做縮放失效。
     */
    private val _renderEpoch = MutableStateFlow(0)
    val renderEpoch: StateFlow<Int> = _renderEpoch.asStateFlow()

    /**
     * 舊入口保留給未接上縮放的呼叫者（等同「紙寬＝視窗寬」的退化情形）。
     * 新程式請用 [setRenderPlan] + [setVisiblePageRange]。
     */
    fun setDisplayRenderScale(scale: Float) {
        val s = scale.coerceIn(MIN_RENDER_SCALE, 3f)
        loRenderScale = s
        applyRenderPlan()
    }

    /**
     * 縮放落定後由 Workspace 呼叫：依**紙寬**（已含 docZoom）決定可見頁倍率。
     *
     * @param paperWidthPx 紙的可視寬 px。必須是視窗寬 × max(docZoom, 1)，不是視窗寬。
     * @param pageWidthPt 頁寬（PDF pt）。
     */
    fun setRenderPlan(paperWidthPx: Float, pageWidthPt: Float) {
        val maxScale = maxScaleForPage(
            pageWidthPt = pageWidthPt,
            pageHeightPt = pageWidthPt * 1.4142f, // A4 直式近似即可：只當預算守門，不是版面依據
            maxMemoryBytes = Runtime.getRuntime().maxMemory()
        )
        // 便宜檔（捲動中／窗外頁）：單頁 [CHEAP_PAGE_BYTES] 以內。
        // 舊值是「視窗寬／頁寬夾 2..3」——那其實跟高清差不多貴（單頁 11MB 起），
        // 捲一圈就製造幾百 MB 的垃圾，是 PDF 捲動閃退的根源。
        loRenderScale = cheapRenderScale(pageWidthPt, pageWidthPt * 1.4142f)
        hiRenderScale = resolveRenderScale(paperWidthPx, pageWidthPt, maxScale)
        android.util.Log.d(
            "InkFlowDbg",
            "renderPlan paper=${paperWidthPx.toInt()}px lo=$loRenderScale hi=$hiRenderScale max=$maxScale range=$hiTierRange"
        )
        applyRenderPlan()
    }

    /**
     * 可見頁範圍變更。捲動時呼叫（不重渲任何東西本身）。
     *
     * 範圍變了只是「哪些頁吃滿倍率」變了；真正要重渲的頁由 [applyRenderPlan] 判定。
     *
     * 這裡同時是「現在還在捲嗎」的訊號源：每個捲動幀都會呼叫一次，
     * 停止捲動後就不再呼叫 → [scrollingNow] 會在 [SCROLL_SETTLE_MS] 後翻成 false
     * → 那時才把可見頁升級成全解析度（捲動中一律用便宜檔）。
     */
    fun setVisiblePageRange(range: IntRange) {
        lastVisibleChangeMs = SystemClock.uptimeMillis()
        scheduleSettledUpgrade()
        if (range == hiTierRange) return
        hiTierRange = range
        applyRenderPlan()
    }

    /**
     * 排「落定後升級」：停止變動 [SCROLL_SETTLE_MS] 之後，把可見頁從便宜檔
     * 換成全解析度。使用者停在某一頁時那一頁才會變清楚。
     *
     * 一個捲動階段只養一個 job（不是每個捲動幀 cancel/launch 一個）：
     * 捲動中它只是每 [SCROLL_SETTLE_TICK_MS] 醒一次看停了沒，成本近乎零。
     */
    private fun scheduleSettledUpgrade() {
        if (settleUpgradeJob?.isActive == true) return
        settleUpgradeJob = viewModelScope.launch {
            while (scrollingNow()) {
                // 捲不停的時候也看一眼：只有累積過頭（GC_FORCE_BYTES）才會真的收。
                collectIdleGarbage()
                delay(SCROLL_SETTLE_TICK_MS)
            }
            // 落定：這裡要**無條件**重檢一次實際倍率（force），不能只看
            // 「計畫變了沒」。因為捲動中新增的頁是便宜檔，而計畫的 bookkeeping
            // 可能早就記成「已套用」，只看它就會永遠停在模糊。
            applyRenderPlan(force = true)
            // 收垃圾挑在這一刻：使用者已經停了，STW 沒人看得到。
            collectIdleGarbage()
        }
    }

    /** 現在還在捲嗎？（可見範圍最近有變動 → 還在捲） */
    private fun scrollingNow(): Boolean =
        (SystemClock.uptimeMillis() - lastVisibleChangeMs) < SCROLL_SETTLE_MS

    /**
     * 這一頁該用哪個倍率。**只影響「還沒渲的頁」**——已經渲好的頁不會因為
     * 開始捲動就被降級（見 [applyRenderPlan]）。
     *
     * **捲動中一律用便宜檔**：使用者還在移動，這一頁下一秒就被換掉了，
     * 這時渲全解析度只是製造記憶體垃圾（見 [CHEAP_PAGE_BYTES] 的說明）。
     * 落定後可見頁才吃滿倍率，窗外頁維持便宜檔。
     */
    private fun scaleForPage(pageIndex: Int): Float =
        if (scrollingNow()) loRenderScale
        else if (pageIndex in hiTierRange) hiRenderScale
        else loRenderScale

    /**
     * 套用計畫：把**該清楚的頁**弄成高清。
     *
     * 判斷依據是每頁**實際渲過的倍率**（[bitmapScale]），不是「計畫變了沒」。
     * 差別在自我修復：升級的渲染若被新一代 `scrollGen` 作廢、或中途失敗，
     * 「計畫」會以為已經做完，那頁就永遠停在模糊；而看實際倍率的話，
     * 下一次落定檢查就會再修一次。
     *
     * 兩個關鍵決定：
     *
     *  1. **移除快取項，但不清 flow 的值。** `renderPage` 開頭會查快取，舊圖還在
     *     就會直接返回、永遠不重渲——所以必須 `bitmapCache.remove`。但 flow 仍持有
     *     那張圖的參考，它繼續顯示，新圖在底下渲好再換上。這就是「不閃白」的來源：
     *     舊的 `evictAll()` 加上置 null，會讓整份文件在每次縮放時白一下。
     *  2. **捲動中什麼都不動。** 已經清楚的頁繼續清楚（中途換成便宜檔只會閃一下
     *     模糊，更難看），新出現的頁由 [scaleForPage] 自動走便宜檔。停止捲動後
     *     才由 [scheduleSettledUpgrade] 升級。
     */
    private fun applyRenderPlan(force: Boolean = false) {
        val hi = hiRenderScale
        val lo = loRenderScale
        val range = hiTierRange
        val scrolling = scrollingNow()
        if (!force && hi == appliedHiScale && lo == appliedLoScale &&
            range == appliedRange && scrolling == appliedScrolling
        ) return
        appliedHiScale = hi
        appliedLoScale = lo
        appliedRange = range
        appliedScrolling = scrolling

        if (scrolling) return

        bitmapFlowCache.forEach { (index, flow) ->
            if (index !in range) return@forEach
            // 沒人顯示的頁不渲：等它被組成時 getPageBitmap 會自己補。
            if (flow.subscriptionCount.value == 0) return@forEach
            // 已經是這個倍率渲好的 → 不動（拖曳／旋轉時倍率來回抖動必須零成本）。
            if (bitmapScale[index] == hi) return@forEach
            // 有圖但不知道倍率（不該發生）：不確定就別動，寧可糊一張也不要亂重渲。
            if (bitmapScale[index] == null && flow.value != null) return@forEach
            bitmapCache.remove(index)
            launchBitmapRender(flow, index)
        }
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
            bitmapScale.remove(index)
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
            bitmapScale.remove(key)
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
        bitmapScale.clear()
        resetRenderPlanBookkeeping()
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
                repos.transaction {
                repos.pageOps.insertPagesAfter(documentUri, optimisticNewIndex, 1)
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
                repos.transaction {
                repos.pageOps.insertPagesAfter(documentUri, insertionIndex, insertedPageCount)
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
                repos.transaction {
                repos.pageOps.insertPagesAfter(documentUri, insertionIndex, totalInserted)
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

            // 移頁前拍下所有縮圖：移頁只是重排，每一格顯示的紙其實沒變，
            // 之後把舊縮圖搬到新索引，避免所有卡片先閃 spinner 再重渲
            // （手感像「放手跳一下」）。拍在 closeRendererOnly 之前，否則 cache 已空。
            val carriedThumbs = thumbnailCache.snapshot()
            var pageMoved = false

            val ok = runCatching {
                val tOp0 = System.currentTimeMillis()
                renderMutex.withLock { closeRendererOnly() }

                // 1. Move page in PDF file
                val tFile0 = System.currentTimeMillis()
                val moved = com.vic.inkflow.util.PdfManager.movePage(fileUri, fromIndex, toIndex)
                val tFileMs = System.currentTimeMillis() - tFile0

                if (moved) {
                    PageOpJournal.markFileDone(getApplication(), entry)
                    pageMoved = true

                    // 2. Transact DB index updates
                    val tDb0 = System.currentTimeMillis()
                    repos.transaction {
                    // P2：五張頁範圍表的搬頁三步驟收成一個呼叫（原本是 5 段 with(dao){} 重複）。
                    // 內部順序（tempIndex → shift → tempIndex 回填）與重構前逐表相同。
                    repos.pageOps.movePage(documentUri, fromIndex, toIndex)
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
                // 移頁只是重排：把舊縮圖搬到新索引，避免所有卡片先閃 spinner 再重渲。
                // 只在真的搬成功時才搬（失敗時文件沒變、搬了反而錯）。
                if (pageMoved) {
                    carriedThumbs.forEach { (oldIndex, bmp) ->
                        val newIndex = when {
                            oldIndex == fromIndex -> toIndex
                            fromIndex < toIndex && oldIndex in (fromIndex + 1)..toIndex -> oldIndex - 1
                            fromIndex > toIndex && oldIndex in toIndex until fromIndex -> oldIndex + 1
                            else -> oldIndex
                        }
                        if (newIndex in 0 until currentCount) thumbnailCache.put(newIndex, bmp)
                    }
                }
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
                repos.transaction {
                    for (index in sortedIndices) {
                        // P2：五張頁範圍表的刪頁+位移收成一個呼叫（原本是 5 段 with(dao){} 重複）。
                        repos.pageOps.deletePage(documentUri, index)
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
        settleUpgradeJob?.cancel()
        settleUpgradeJob = null
        resetRenderPlanBookkeeping()
        hiTierRange = IntRange.EMPTY
        _currentPdfUri.value = null
        pdfRenderer?.close()
        parcelFileDescriptor?.close()
        pdfRenderer = null
        parcelFileDescriptor = null
        _pageCount.value = 0
        bitmapCache.evictAll()
        thumbnailCache.evictAll()
        bitmapScale.clear()
        thumbnailFlowCache.clear()
        bitmapFlowCache.clear()
        bitmapRequestedAt.clear()
        thumbnailRequestedAt.clear()
        bitmapAge.clear()
        thumbnailAge.clear()
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
        // 需求時間戳：這頁是「現在」被要求的。等它排到渲染隊伍時若已無人問津
        // （捲過去了），就整個跳過——見 renderPage 的需求檢查。
        bitmapRequestedAt[pageIndex] = System.currentTimeMillis()
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
        val ticket = scrollGen
        renderScope.launch {
            // M2 慢滑跟手清：只在快旗時等待落定；慢滑直接渲（手指推到哪清到哪）。
            // 票被超車（快旗升/落）直接丟棄，不欠渲染債。
            var waits = 0
            while (
                (_isScrollingFast.value || renderHold.get()) &&
                ticket == scrollGen && waits < 100
            ) {
                kotlinx.coroutines.delay(100)
                waits++
            }
            if (ticket != scrollGen) return@launch
            // 等過的排隊者：沒人訂閱（已滑走拆掉）且快取也空就不渲，
            // 滑回來 remember 重跑會重發。沒等過的（靜止時組成）照常渲，
            // 避開「flow 剛建、collect 還沒訂上」的 race。
            if (waits > 0 && flow.subscriptionCount.value == 0 && bitmapCache[pageIndex] == null) {
                return@launch
            }
            // 先把縮圖補上再渲高清：兩者共用同一把 PDF 鎖，先排隊的先跑。
            // 縮圖便宜（240px），先上桌使用者才不會盯著白紙等高清。
            ensureThumbnailReady(pageIndex)
            // F2 黑頁修復：剛插頁重開空窗期 pdfRenderer 為 null，這次必回 null；
            // 以前只試一次就永久黑，現在退避重試（flow 留空等重試，不毒化快取）。
            repeat(4) { attempt ->
                if (ticket != scrollGen) return@launch
                // 這頁已經沒人要看（捲過去了）→ 不重試。重試只是白等 600ms×4，
                // 而且會在 log 裡留下假的「retry」噪音。
                if (!isPageStillWanted(pageIndex, highQuality = true)) return@launch
                val bmp = renderPage(pageIndex, highQuality = true, ticket = ticket)
                if (bmp != null) {
                    stampAge(bitmapAge, pageIndex)
                    if (rendersPaused.get()) pendingBitmaps[pageIndex] = bmp
                    else flow.value = bmp
                    // 渲完就把「多餘的舊圖」收回來：捲過的頁留在 native heap 裡
                    // 是閃退的主因（見 evictColdBitmaps）。
                    evictColdBitmaps()
                    return@launch
                }
                android.util.Log.d("PdfViewModel", "getPageBitmap retry $attempt page=$pageIndex")
                kotlinx.coroutines.delay(600L * (attempt + 1))
            }
        }
    }

    /**
     * 這頁的縮圖好了沒有？沒有就地補一張（縮圖便宜，佔不到一幀）。
     *
     * 為什麼要在高清之前做：兩種渲染共用同一把 PDF 鎖，誰先排隊誰先跑。
     * 組成的順序是「先高清後縮圖」，高清一次要幾百毫秒，縮圖會被壓在後面，
     * 於是捲動時看到的是**一片白**而不是模糊的縮圖——使用者感受就是「卡住」。
     * 這裡把縮圖插到高清前面，順序就反過來了。
     */
    private suspend fun ensureThumbnailReady(pageIndex: Int) {
        val cached = thumbnailCache[pageIndex]
        if (cached != null) {
            // 快取有就順手投給 flow（可能上一輪被回收過值）。
            thumbnailFlowCache[pageIndex]?.let { flow ->
                if (flow.value == null) flow.value = cached
            }
            return
        }
        val thumb = renderPage(pageIndex, highQuality = false, requireInterest = false) ?: return
        stampAge(thumbnailAge, pageIndex)
        thumbnailFlowCache[pageIndex]?.let { flow ->
            if (flow.value == null) {
                if (rendersPaused.get() && !_isScrollingFast.value) pendingThumbs[pageIndex] = thumb
                else flow.value = thumb
            }
        }
    }

    fun getPageThumbnail(pageIndex: Int): StateFlow<Bitmap?> {
        thumbnailRequestedAt[pageIndex] = System.currentTimeMillis()
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
                stampAge(thumbnailAge, pageIndex)
                // P0 快滑掠過就靠縮圖：高速期間縮圖照投（不扣留），高清才等落定。
                if (rendersPaused.get() && !_isScrollingFast.value) pendingThumbs[pageIndex] = it
                else flow.value = it
                evictColdBitmaps()
            }
        }
    }

    fun prefetchPage(pageIndex: Int) {
        if (pageIndex < 0 || pageIndex >= pageCount.value) return
        if (bitmapCache[pageIndex] != null) return

        val ticket = scrollGen
        renderScope.launch {
            renderPage(pageIndex, highQuality = true, ticket = ticket, requireInterest = false)
                ?.let { stampAge(bitmapAge, pageIndex) }
        }
    }

    /** P0 快滑：高速期間鄰頁只預熱縮圖（便宜，240px 寬封頂），高清等落定。 */
    fun prefetchThumbnail(pageIndex: Int) {
        if (pageIndex < 0 || pageIndex >= pageCount.value) return
        if (thumbnailCache[pageIndex] != null) return

        renderScope.launch {
            // 預取本來就是「這一頁還沒被組成」，沒有訂閱、需求時間戳也舊，
            // 所以不受需求檢查管（否則預取會全部被跳過，等於沒有預取）。
            renderPage(pageIndex, highQuality = false, requireInterest = false)
                ?.let { stampAge(thumbnailAge, pageIndex) }
        }
    }

    /**
     * 落定：以當前頁為中心補高清，優先序本頁→下一頁→上一頁→下兩頁→上兩頁
     *（例：當前 3 → 3,4,2,5,1）。只發這 5 頁（flow 存在但空、且快取也空才發；
     * compose 中的 getPageBitmap 會兜底剩餘，雙發時第二個命中快取直接返回）。
     * mutex 串行，發射順序即渲染順序。越界下標此處擋掉。
     */
    fun ensureHighQualityAround(center: Int, radius: Int = 2) {
        if (center < 0 || center >= pageCount.value) return
        buildList {
            add(center)
            for (d in 1..radius) {
                add(center + d)
                add(center - d)
            }
        }.forEach { index ->
            if (index < 0 || index >= pageCount.value) return@forEach
            if (bitmapCache[index] != null) return@forEach
            val flow = bitmapFlowCache[index] ?: return@forEach
            // 沒人顯示的頁不補：補了也是白渲一份 13MB，等它真的被組成再說
            //（compose 的 getPageBitmap 會自己補）。
            if (flow.subscriptionCount.value == 0) return@forEach
            if (flow.value == null) launchBitmapRender(flow, index)
        }
    }

    /**
     * P1 有界：只留可見±margin 內的高清 flow；窗外且無訂閱的（已拆掉的 item）
     * 置空＋移出快取＋摘 map（重組時 remember 重跑 getPageBitmap 自動補渲）。
     * 有訂閱的不動（LazyColumn 場外緩衝頁正在顯示，不能閃白）。
     *
     * 縮圖也一起清：舊碼只清高清 flow，縮圖 flow 留著不動——190 頁的 PDF
     * 捲一圈就是 190 張縮圖釘在記憶體裡（單張 0.3MB，加起來 60MB），
     * 一樣會把手機的記憶體吃光。縮圖有人訂閱（側欄縮圖網格）就不動。
     */
    fun trimBitmapFlowsToWindow(visible: IntRange, margin: Int = 5) {
        val lo = (visible.first - margin).coerceAtLeast(0)
        val hi = visible.last + margin
        val it = bitmapFlowCache.entries.iterator()
        while (it.hasNext()) {
            val (index, flow) = it.next()
            if (index in lo..hi) continue
            if (flow.subscriptionCount.value == 0) {
                flow.value = null
                bitmapCache.remove(index)
                bitmapAge.remove(index)
                bitmapScale.remove(index)
                it.remove()
            }
        }
        val tit = thumbnailFlowCache.entries.iterator()
        while (tit.hasNext()) {
            val (index, flow) = tit.next()
            if (index in lo..hi) continue
            if (flow.subscriptionCount.value == 0) {
                flow.value = null
                thumbnailCache.remove(index)
                thumbnailAge.remove(index)
                tit.remove()
            }
        }
        evictColdBitmaps()
    }
    
    fun setScrollingFast(isFast: Boolean) {
        if (_isScrollingFast.value == isFast) return
        _isScrollingFast.value = isFast
        // 每次升/落都換代：排隊中的中間頁高清憑舊票作廢，不欠渲染債。
        scrollGen++
    }

    private suspend fun renderPage(
        pageIndex: Int,
        highQuality: Boolean,
        ticket: Long? = null,
        requireInterest: Boolean = true
    ): Bitmap? {
        if (pageIndex < 0 || pageIndex >= _pageCount.value) return null

        val cache = if (highQuality) bitmapCache else thumbnailCache
        cache[pageIndex]?.let { return it }

        return withContext(Dispatchers.IO) {
            val gen = renderGen
            renderMutex.withLock {
                // 頁操作已換代：舊渲染直接丟棄，不畫不佔鎖。
                if (gen != renderGen) return@withLock null
                // P0 快滑：等鎖期間票被超車（快旗升/落），高清直接丟棄；縮圖便宜照渲。
                if (highQuality && ticket != null && ticket != scrollGen) return@withLock null
                // 排到隊了，但這一頁可能已經被捲走、沒人再看。
                // 這時渲它 = 白燒 CPU + 白吃一份 13MB 的記憶體，還擋住「正在看的那頁」。
                if (requireInterest && !isPageStillWanted(pageIndex, highQuality)) {
                    return@withLock null
                }
                pdfRenderer?.let { renderer ->
                    try {
                        val page = renderer.openPage(pageIndex)
                        // v6：可見頁吃滿倍率、窗外頁低清（見 setRenderPlan）。
                        // 這裡是唯一決定「這一頁畫多大」的地方。
                        val scale = if (highQuality) scaleForPage(pageIndex) else 0.4f
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
                        // 記下事實倍率：applyRenderPlan 靠它判斷「夠清楚了沒」。
                        if (highQuality) bitmapScale[pageIndex] = scale
                        noteRenderedBytes(bitmap.byteCount.toLong())
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

    /**
     * 這一頁現在還有人要嗎？
     *
     * 兩個來源：螢幕上有人在看（flow 有訂閱），或是剛被要求過（時間窗內）。
     * 詳見 [shouldRenderPage] 的理由——這是「捲動時不要白渲被捲過的頁」那道閘門。
     */
    private fun isPageStillWanted(pageIndex: Int, highQuality: Boolean): Boolean {
        val flow = if (highQuality) bitmapFlowCache[pageIndex] else thumbnailFlowCache[pageIndex]
        val hasSubscriber = flow != null && flow.subscriptionCount.value > 0
        val requestedAt = (
            if (highQuality) bitmapRequestedAt[pageIndex] else thumbnailRequestedAt[pageIndex]
            ) ?: 0L
        val age = (System.currentTimeMillis() - requestedAt).coerceAtLeast(0L)
        return shouldRenderPage(hasSubscriber, age)
    }

    /**
     * 自上次 GC 以來產生的頁面圖位元組。超過門檻就收一次垃圾。
     *
     * 為什麼需要（重大：PDF 捲動會閃退）：Android 8 之後 Bitmap 的像素在
     * **native heap**，而 ART 的 GC 只按 **Java heap** 的用量決定要不要跑。
     * 這個 App 的 Java heap 有 512MB 且用量很低 → GC 幾乎不跑 → 被丟掉的頁面圖
     * 一直占著 native heap 不放。實測：捲 20 頁後 native 堆裡躺著 380MB 的
     * 點陣圖，GC 一跑就掉回 154MB —— 這些全是「垃圾但沒人收」。
     *
     * 所以在產生垃圾的路上自己收：門檻 [GC_TRIGGER_BYTES] 給得寬，正常捲動摸不到；
     * 真摸到了就是 native heap 真的在漲，那時收一次比等 OOM 划算。
     */
    private val renderedBytesSinceGc = java.util.concurrent.atomic.AtomicLong(0)
    private val gcArmed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 只記帳、不立刻收：收的時機見 [collectIdleGarbage]。 */
    private fun noteRenderedBytes(bytes: Long) {
        if (renderedBytesSinceGc.addAndGet(bytes) >= GC_TRIGGER_BYTES) gcArmed.set(true)
    }

    /**
     * **閒置才收垃圾。**
     *
     * 為什麼不在累積到門檻的當下就收：`System.gc()` 是 stop-the-world，
     * 捲動中收一次就是使用者眼裡的一頓（實測 fling 途中被 GC 打斷最明顯）。
     * 而頁面圖垃圾在 native heap，早 0.2 秒收或晚 0.2 秒收沒有人看得出差別——
     * 只要在 native heap 撐爆之前收掉就好。
     *
     * 所以門檻到了只「上膛」，真的收的時機是落定／閒置（[scheduleSettledUpgrade]
     * 的尾巴）；萬一使用者一路捲不停、累積到 [GC_FORCE_BYTES]，那就照收，
     * 因為那時不收就是等著 OOM。
     */
    private fun collectIdleGarbage() {
        if (!gcArmed.get()) return
        val forced = renderedBytesSinceGc.get() >= GC_FORCE_BYTES
        if (!forced && (scrollingNow() || renderHold.get() || _isScrollingFast.value)) return
        if (!gcArmed.compareAndSet(true, false)) return
        renderedBytesSinceGc.set(0)
        viewModelScope.launch(Dispatchers.Default) {
            android.util.Log.d(
                "PdfViewModel",
                "GC: 頁面圖垃圾已累積 >=${GC_TRIGGER_BYTES / 1024 / 1024}MB，閒置收一次"
            )
            System.gc()
        }
    }

    /**
     * 把活著的頁面圖壓回預算內：**從最舊的開始，砍掉沒人看的**。
     *
     * ## 為什麼需要這一段（重大：PDF 捲動會閃退）
     *
     * `bitmapCache` 是 LruCache，有上限；但同一張圖還被 `bitmapFlowCache` 的
     * flow 值拿著——UI 就是直接顯示那個值的。**LruCache 逐出 ≠ 圖片被釋放**，
     * 所以舊設計的「上限」形同虛設：捲過的每一頁都留在 native heap 裡
     * （Android 8+ 的 Bitmap 像素不在 Java heap，GC 也管不到）。
     * 一份 190 頁的 PDF 捲一圈 → 幾百 MB 的點陣圖 → native heap 撐爆 → 閃退。
     *
     * ## 為什麼可以砍
     *
     * 「沒人訂閱」= 沒有 LazyColumn item 在顯示它 = 已經被捲過去的頁。
     * 有人訂閱的頁不砍：那是螢幕上正在看的，砍了就是閃白。
     * 砍掉後 flow 的值被清空、entry 也從快取移除，所以滑回來時
     * `getPageBitmap` 會重新發起渲染（記得 flow 實例不變，不會閃白成黑頁）。
     */
    private fun evictColdBitmaps() {
        val maxMemory = Runtime.getRuntime().maxMemory()
        evictColdLayer(
            flows = bitmapFlowCache,
            cache = bitmapCache,
            ages = bitmapAge,
            budget = liveBitmapBudgetBytes(maxMemory),
            label = "hi",
            maxMemory = maxMemory,
            pending = pendingBitmaps,
            scales = bitmapScale
        )
        evictColdLayer(
            flows = thumbnailFlowCache,
            cache = thumbnailCache,
            ages = thumbnailAge,
            budget = liveThumbnailBudgetBytes(maxMemory),
            label = "thumb",
            maxMemory = maxMemory,
            pending = pendingThumbs,
            // 縮圖與高清共用頁索引，不能把 bitmapScale 一起清掉。
            scales = null
        )
    }

    /**
     * 一層（高清／縮圖）的回收：活著位元組超過預算就從最舊的砍起。
     *
     * 活著 = flow 的值 ＋ 快取裡的 ＋ 尚未投遞的 pending（同一張圖只算一次，
     * 用參照比對）。快取裡有、flow 沒有的也算進去——它一樣佔記憶體。
     *
     * **砍掉時三者都要清。** 以前只清 flow／快取，pending 留著 → 那張圖
     * 仍被 map 釘住、記憶體沒回來，而且候選清單裡已經沒有它了，
     * 下一次回收也看不到它（等於永遠收不掉）。
     */
    private fun evictColdLayer(
        flows: MutableMap<Int, MutableStateFlow<Bitmap?>>,
        cache: LruCache<Int, Bitmap>,
        ages: MutableMap<Int, Long>,
        budget: Long,
        label: String,
        maxMemory: Long,
        pending: MutableMap<Int, Bitmap>,
        scales: MutableMap<Int, Float>?
    ) {
        val live = HashMap<Int, Bitmap>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Bitmap, Boolean>())
        flows.forEach { (index, flow) ->
            flow.value?.let {
                live[index] = it
                seen.add(it)
            }
        }
        cache.snapshot().forEach { (index, bmp) ->
            if (!seen.contains(bmp)) {
                live[index] = bmp
                seen.add(bmp)
            }
        }
        pending.forEach { (index, bmp) ->
            if (!seen.contains(bmp)) {
                live[index] = bmp
                seen.add(bmp)
            }
        }
        val candidates = live.map { (index, bmp) ->
            BitmapCandidate(
                index = index,
                bytes = bmp.byteCount.toLong(),
                // 螢幕上正在顯示的頁不砍：砍了就是閃白。
                hasSubscriber = flows[index]?.subscriptionCount?.value?.let { it > 0 } ?: false,
                age = ages[index] ?: Long.MAX_VALUE
            )
        }
        val liveBytes = candidates.sumOf { it.bytes }
        val evicted = selectColdEvictions(candidates, liveBytes, budget)
        evicted.forEach { index ->
            flows[index]?.let { flow -> flow.value = null }
            cache.remove(index)
            ages.remove(index)
            pending.remove(index)
            scales?.remove(index)
        }
        // 只在真的砍了東西、或已經超預算時才落 log：這函式每個捲動幀都可能被呼叫，
        // 無條件印會把 logcat 灌滿（查別的問題時很難看）。
        if (evicted.isNotEmpty() || liveBytes > budget) {
            android.util.Log.d(
                "PdfViewModel",
                "evict[$label] live=${liveBytes / 1024}KB budget=${budget / 1024}KB " +
                    "maxMem=${maxMemory / 1024 / 1024}MB pages=${candidates.size} " +
                    "subscribed=${candidates.count { it.hasSubscriber }} evicted=${evicted.size} " +
                    "pending=${pending.size} paused=${rendersPaused.get()}"
            )
        }
    }


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
        bitmapScale.clear()
        thumbnailFlowCache.clear()
        bitmapFlowCache.clear()
        bitmapRequestedAt.clear()
        thumbnailRequestedAt.clear()
        bitmapAge.clear()
        thumbnailAge.clear()
    }
}
