package com.vic.inkflow.util

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 拖曳排序狀態機（2026-10-13 重寫版）。
 *
 * 上一版的三個病根，對照修法：
 * 1. remember(gridState) 捕獲 onMove/onDragEnd 閉包 → 越拖越亂。
 *    修法：state 只記 gridState＋手勢瞬時量；回調一律經外部 rememberUpdatedState
 *    以 State 傳入，手勢協程每次讀 .value，永遠新鮮。
 * 2. 位置（position）與鍵（key/頁號）混用，結算映射錯誤。
 *    修法：state 內部只玩 position（瞬時，用完即棄）；key 的映射與結算全在呼叫端，
 *    結算時從 key 序列反查位置，不信任手勢裡記的任何 index。
 * 3. 自捲座標系與手指座標系混用。
 *    修法：唯一座標系＝網格容器本地 px。手指位置＝起拖中心＋增量自累加，
 *    不用 change.position（那是觸摸槽本地，跨幀混用就是偏）。
 */
data class Move(val fromIndex: Int, val toIndex: Int)

@Composable
fun rememberReorderableLazyGridState(
    gridState: LazyGridState
): ReorderableLazyGridState {
    return remember(gridState) { ReorderableLazyGridState(gridState) }
}

class ReorderableLazyGridState(
    val gridState: LazyGridState
) {
    /** 被拖項目的當下位置（瞬時；結算不許用它）。 */
    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set

    var isDragging by mutableStateOf(false)
        private set

    /** 被拖卡中心（容器本地 px）＝起拖中心＋增量累加。自捲循環讀它的 y。 */
    var dragCenter by mutableStateOf<Offset?>(null)
        private set

    /** 起拖至今手指走過的總距離 px。自捲啟動門（走滿死區才捲）用它。 */
    var travelPx by mutableStateOf(0f)
        private set

    /** 被拖卡的視覺位移（相對當前格子）：每幀跟增量，換位時扣掉 layout 位移。 */
    var dragVisual by mutableStateOf(Offset.Zero)
        private set

    internal var initialItemIndex: Int? = null
    internal var currentItemIndex: Int? = null

    private var total = Offset.Zero
    private var prevCenter = Offset.Zero
    private var lastSwapDir = 0
    private var travelSinceSwap = 0f

    /** @return 有沒有真的拎起一張卡（點到縫裡不算）。 */
    fun onDragStart(containerPos: Offset): Int? {
        val hit = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            containerPos.y >= item.offset.y && containerPos.y <= item.offset.y + item.size.height &&
            containerPos.x >= item.offset.x && containerPos.x <= item.offset.x + item.size.width
        } ?: return null
        draggingItemIndex = hit.index
        initialItemIndex = hit.index
        currentItemIndex = hit.index
        total = Offset.Zero
        dragVisual = Offset.Zero
        prevCenter = Offset(hit.offset.x + hit.size.width / 2f, hit.offset.y + hit.size.height / 2f)
        dragCenter = prevCenter
        travelPx = 0f
        lastSwapDir = 0
        travelSinceSwap = 0f
        isDragging = true
        return hit.index
    }

    /**
     * @return 該發 onMove 就回傳 Move，不該動回傳 null。
     * 換位條件＝過中心線（遲滯）＋反向死區：靜止停在邊界不許來回翻；
     * 剛換完就往回走，必須走滿 [deadZonePx] 才許換回去。
     */
    fun onDrag(amount: Offset, deadZonePx: Float): Move? {
        if (!isDragging) return null
        total += amount
        dragVisual += amount
        travelPx = total.getDistance()
        travelSinceSwap += amount.getDistance()

        val draggingIndex = currentItemIndex ?: return null
        val draggingItem =
            gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggingIndex }
                ?: return null
        // 中心遞推＝上一幀中心＋本幀增量：layout 換位會讓格子 offset 跳變，
        // 直接讀格子算中心會抖；遞推不受 layout 影響，手有多穩它就有多穩。
        val stableCenter = Offset(prevCenter.x + amount.x, prevCenter.y + amount.y)
        dragCenter = stableCenter

        val target = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            stableCenter.y >= item.offset.y && stableCenter.y <= item.offset.y + item.size.height &&
            stableCenter.x >= item.offset.x && stableCenter.x <= item.offset.x + item.size.width &&
            item.index != draggingIndex
        }
        prevCenter = stableCenter
        if (target == null) return null
        val targetCx = target.offset.x + target.size.width / 2f
        val targetCy = target.offset.y + target.size.height / 2f
        // 上一幀中心還在目標中心另一側＝真的穿過去了（用 stableCenter 遞推，
        // 起點是卡中心，不會第一下誤判）。
        val beforeX = prevCenter.x - amount.x - targetCx
        val beforeY = prevCenter.y - amount.y - targetCy
        val crossedX = beforeX * (stableCenter.x - targetCx) < 0f
        val crossedY = beforeY * (stableCenter.y - targetCy) < 0f
        if (!crossedX && !crossedY) return null
        // 反向死區：跟上次換位方向相反，必須走滿死區。
        val dir = (target.index - draggingIndex).coerceIn(-1, 1)
        if (lastSwapDir != 0 && dir != lastSwapDir && travelSinceSwap < deadZonePx) return null
        lastSwapDir = dir
        travelSinceSwap = 0f
        currentItemIndex = target.index
        // 視覺補償：格子換位後，被拖卡的新格子 offset 跳了；扣掉這段跳變，
        // 卡才會繼續黏在手指下（不扣＝每換一次位就跳一格＝「飄」）。
        dragVisual -= Offset(
            (target.offset.x - draggingItem.offset.x).toFloat(),
            (target.offset.y - draggingItem.offset.y).toFloat()
        )
        return Move(draggingIndex, target.index)
    }

    fun finish(commit: Boolean): Pair<Int, Int>? {
        val from = initialItemIndex
        val to = currentItemIndex
        draggingItemIndex = null
        initialItemIndex = null
        currentItemIndex = null
        dragCenter = null
        dragVisual = Offset.Zero
        travelPx = 0f
        total = Offset.Zero
        isDragging = false
        return if (commit && from != null && to != null && from != to) from to to else null
    }
}

/**
 * @param deadZonePx 反向換位死區（8dp 級）。正向只認過中心線。
 * @param onMove 高頻：呼叫端同步改內存預覽順序（position 當幀有效）。
 * @param onDragStart 起拖位置；呼叫端拍 initialOrder 快照＋記被拖頁。
 * @param onDragEnd 正常放手一次；呼叫端從 key 序列結算（不准用手勢記的 index）。
 * @param onDragCancel 系統取消一次；呼叫端用快照回滾，不提交。
 * @param onLift 真的拎起一張卡時一次（起飛震動放這裡）。
 */
fun Modifier.reorderable(
    state: ReorderableLazyGridState,
    enabled: Boolean = true,
    deadZonePx: Float = 0f,
    onLift: State<(() -> Unit)?>,
    onDragStart: State<((Int) -> Unit)?>,
    onMove: State<((Move) -> Unit)?>,
    onDragEnd: State<(() -> Unit)?>,
    onDragCancel: State<(() -> Unit)?>
): Modifier = pointerInput(state, enabled, deadZonePx) {
    if (!enabled) return@pointerInput
    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            val index = state.onDragStart(offset)
            if (index != null) {
                onLift.value?.invoke()
                onDragStart.value?.invoke(index)
            }
        },
        onDrag = { change, dragAmount ->
            change.consume()
            state.onDrag(dragAmount, deadZonePx)?.let { move ->
                onMove.value?.invoke(move)
            }
        },
        onDragEnd = {
            state.finish(commit = true)
            onDragEnd.value?.invoke()
        },
        onDragCancel = {
            state.finish(commit = false)
            onDragCancel.value?.invoke()
        }
    )
}
