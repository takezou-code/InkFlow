package com.vic.inkflow.ui

import com.vic.inkflow.data.ImageAnnotation
import com.vic.inkflow.data.StrokeWithSamples
import com.vic.inkflow.data.TextAnnotation

/**
 * S1 雙寫：把頁空間實體的 docY 重算（docY = pageIndex × stride + 頁內頂邊）。
 *
 * 為什麼是 top-level 而不是某個類別的 private extension：docY 的公式同時被
 * 選取流程（[com.vic.inkflow.ui.editor.SelectionController]）與復原流程
 * （[EditorViewModel] 的 redo）用到。公式寫兩份就等於「兩端會漂移」的同款問題
 * ——這裡是同一個 app 內部的兩條路徑，漂移的症狀是 docY 對不上、墨畫在錯的紙上。
 *
 * stride 由呼叫端帶進來：它是當下文件的頁高（紙改了會變），不是常數。
 */
fun StrokeWithSamples.redocWith(stride: Float): StrokeWithSamples {
    val s = stroke
    return copy(stroke = s.copy(docY = s.pageIndex * stride + s.boundsTop))
}

fun TextAnnotation.redocWith(stride: Float): TextAnnotation =
    copy(docY = pageIndex * stride + modelY)

fun ImageAnnotation.redocWith(stride: Float): ImageAnnotation =
    copy(docY = pageIndex * stride + modelY)
