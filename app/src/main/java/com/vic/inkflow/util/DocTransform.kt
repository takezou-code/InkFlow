package com.vic.inkflow.util

import androidx.compose.ui.geometry.Offset

/**
 * 紙面座標變換（model 空間 ↔ canvas 像素），pure object。
 *
 * - model→canvas 用乘：`canvas = model * scaleX(canvasW, modelW)`（舊各處 `sx/sy`）。
 * - canvas→model 用乘：`model = canvas * invScaleX(canvasW, modelW)`（舊各處
 *   `modelW / canvasW.coerceAtLeast(1f)`）。
 * - 零尺寸守衛與舊 InkCanvas 行為逐字一致：scale 側 `canvas <= 0` 退回 1f；
 *   inv 側 `canvas.coerceAtLeast(1f)`。
 *
 * Pure Kotlin（僅用 compose-ui 的 Offset 值物件，與 DocLayout 同規格），
 * 手勢層/資料層/單測共用同一份數學。渲染側舊 `* sx` 內聯寫法暫不動，
 * 下一步再遷。
 */
object DocTransform {

    /** model→canvas 乘係數：sx = canvasW / modelW。 */
    fun scaleX(canvasW: Float, modelW: Float): Float =
        if (canvasW > 0f) canvasW / modelW else 1f

    /** model→canvas 乘係數：sy = canvasH / modelH。 */
    fun scaleY(canvasH: Float, modelH: Float): Float =
        if (canvasH > 0f) canvasH / modelH else 1f

    /** canvas→model 乘係數：modelW / canvasW.coerceAtLeast(1f)。 */
    fun invScaleX(canvasW: Float, modelW: Float): Float =
        modelW / canvasW.coerceAtLeast(1f)

    /** canvas→model 乘係數：modelH / canvasH.coerceAtLeast(1f)。 */
    fun invScaleY(canvasH: Float, modelH: Float): Float =
        modelH / canvasH.coerceAtLeast(1f)

    /**
     * canvas→model 乘係數（無守衛逐字版）：modelW / canvasW。
     * 與舊 VM/SelectionGeometry 內聯式同義（含除零語義），遷移期用；
     * 新程式碼請用守衛版 [invScaleX]。
     */
    fun invScaleXUnchecked(canvasW: Float, modelW: Float): Float =
        modelW / canvasW

    /** 無守衛逐字版（見 [invScaleXUnchecked]）。 */
    fun invScaleYUnchecked(canvasH: Float, modelH: Float): Float =
        modelH / canvasH

    /** canvas 像素點 → model 座標。 */
    fun canvasToModel(
        p: Offset,
        canvasW: Float,
        canvasH: Float,
        modelW: Float,
        modelH: Float
    ): Offset = Offset(
        p.x * invScaleX(canvasW, modelW),
        p.y * invScaleY(canvasH, modelH)
    )

    /** model 座標 → canvas 像素點。 */
    fun modelToCanvas(
        p: Offset,
        canvasW: Float,
        canvasH: Float,
        modelW: Float,
        modelH: Float
    ): Offset = Offset(
        p.x * scaleX(canvasW, modelW),
        p.y * scaleY(canvasH, modelH)
    )
}
