package com.vic.inkflow.ui.selection

import androidx.compose.ui.geometry.Offset
import com.vic.inkflow.data.StrokeWithSamples
import com.vic.inkflow.util.StrokeTransformUtils

/**
 * 圈選狀態機的意圖。手勢、非同步判定結果、提交結果都只透過這些意圖改狀態，
 * 沒有任何地方直接寫欄位。
 */
sealed interface SelectionIntent {
    /**
     * 手勢結束、開始判定：[region] 立刻生效（提取／AI／泡泡當下就要拿到新圈），
     * 舊選取同步作廢，畫面只留圈出的區域，等背景判定補上內容。
     */
    data class Probing(val revision: Long, val region: SelectionRegion) : SelectionIntent

    /** 背景判定完成。 */
    data class Resolved(
        val revision: Long,
        val region: SelectionRegion,
        val items: SelectionItems,
        val frame: SelectionFrame?
    ) : SelectionIntent

    /** 開始拖曳（平移或縮放）。[anchor] 為 model 座標。 */
    data class BeginTransform(val anchor: Offset) : SelectionIntent

    /** 絕對平移量（model）。不是增量——每一幀都從起點重算。 */
    data class Translate(val translation: Offset) : SelectionIntent

    /** 絕對縮放比例（從手勢起點算）。 */
    data class Scale(val scale: Float) : SelectionIntent

    /** 提交完成：以新的快照回到 READY，變換歸零。 */
    data class Settled(
        val items: SelectionItems,
        val region: SelectionRegion?,
        val frame: SelectionFrame?
    ) : SelectionIntent

    /** DB 回寫期間的預覽墨；null = 清掉。 */
    data class SetCommitPreview(val strokes: List<StrokeWithSamples>?) : SelectionIntent

    /** 全部清空（含區域）。 */
    data object Clear : SelectionIntent
}

/**
 * 純狀態轉移：Intent × State → State。
 *
 * 這個檔案沒有任何 suspend、沒有 IO、沒有 Compose。拖曳跟手與狀態錯亂的修正
 * 有一半在這裡：平移是絕對值、縮放是絕對值、提交只套用一次。
 */
object SelectionReducer {

    fun reduce(state: SelectionState, intent: SelectionIntent): SelectionState = when (intent) {
        is SelectionIntent.Probing ->
            if (state.phase == SelectionPhase.TRANSFORMING) state
            else SelectionState(
                phase = SelectionPhase.RESOLVING,
                revision = intent.revision,
                region = intent.region,
                items = SelectionItems(),
                frame = LassoGeometry.frameOf(SelectionItems(), intent.region),
                transform = SelectionTransform.IDENTITY,
                commitPreview = null
            )

        is SelectionIntent.Resolved -> {
            if (intent.revision < state.revision) state
            else state.copy(
                phase = SelectionPhase.READY,
                revision = intent.revision,
                region = intent.region,
                items = intent.items,
                frame = intent.frame,
                transform = SelectionTransform.IDENTITY,
                commitPreview = null
            )
        }

        is SelectionIntent.BeginTransform -> {
            if (!state.hasItems) state
            else state.copy(
                phase = SelectionPhase.TRANSFORMING,
                transform = SelectionTransform(anchor = intent.anchor)
            )
        }

        is SelectionIntent.Translate -> {
            if (state.phase != SelectionPhase.TRANSFORMING) state
            else state.copy(transform = state.transform.copy(translation = intent.translation))
        }

        is SelectionIntent.Scale -> {
            if (state.phase != SelectionPhase.TRANSFORMING) state
            else state.copy(
                transform = state.transform.copy(
                    scale = StrokeTransformUtils.clampUniformScale(intent.scale)
                )
            )
        }

        is SelectionIntent.Settled -> state.copy(
            phase = SelectionPhase.READY,
            items = intent.items,
            region = intent.region,
            frame = intent.frame,
            transform = SelectionTransform.IDENTITY,
            commitPreview = null
        )

        is SelectionIntent.SetCommitPreview ->
            state.copy(commitPreview = intent.strokes)

        SelectionIntent.Clear ->
            SelectionState(revision = state.revision)
    }
}
