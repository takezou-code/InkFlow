package com.vic.inkflow.ui

/**
 * 拖放排序後，這一頁要移到哪個位置；**沒有實際換位就回 null**（不動文件）。
 *
 * ## 為什麼把這條規則抽出來
 *
 * 拖放本身是手勢，腳本測不了（`input swipe` 從第一毫秒就在動，長按永遠不會完成；
 * 要用一格一格的 `motionevent` 才模拟得出來，而那仍然不是「真人拖」）。
 * 但「放開之後要移到哪」是**純邏輯**，可以測：
 *
 *  - 目標位置是從**預覽順序**反查回來的，不是格線位置（預覽順序在拖曳中已經換位）；
 *  - 位置沒變 → 不呼叫 movePage（否則會產生一次無意義的結構操作、把 undo 棧清掉）。
 *
 * @param initial 起拖時的頁碼順序（快照）。
 * @param preview 拖曳中的預覽順序（套件 onMove 同步更新）。
 * @param page 被拖的那一頁的頁碼。
 */
internal fun reorderTargetPos(initial: List<Int>, preview: List<Int>, page: Int): Int? {
    if (page !in initial) return null
    val target = preview.indexOf(page)
    if (target < 0 || target == initial.indexOf(page)) return null
    return target
}
