package com.vic.inkflow.ui.backdrop

import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * 一個特效場景＝一個檔案＋一個 object＋[backdropScenes] 一行。
 *
 * 繪製必須是 t（秒）的**純函數**：不存可變狀態、不訂閱任何東西。
 * 因此「靜模式凍結首幀」「降頻到 45fps」「時間用真實秒數」全部免費成立，
 * 也才畫得出前後一致的回繞。
 */
interface BackdropScene {
    /**
     * 持久化鍵（SharedPreferences `backdrop_scene` 存的就是它）。
     * **一旦發布永不改**：改了等於使用者的選擇消失。加場景安全，改名＝搬家。
     */
    val id: String

    /** 使用者可見名稱。標籤只有這一份——UI 端不准再寫 `when` 對照表。 */
    val label: String

    fun DrawScope.render(t: Float, w: Float, h: Float, dark: Boolean)
}

/**
 * 場景註冊表：順序＝設定頁選擇條的順序。
 * 加場景＝寫一個檔＋在這裡加一行，設定頁與預覽端零改動。
 */
val backdropScenes: List<BackdropScene> = listOf(DuneScene, TideScene, SnowScene)

/** 認不得的 id（含已刪除的霓城）一律回預設，不留黑洞。 */
fun backdropSceneOf(id: String?): BackdropScene =
    backdropScenes.firstOrNull { it.id == id } ?: DuneScene
