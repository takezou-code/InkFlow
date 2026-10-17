package com.vic.inkflow.ui

import com.vic.inkflow.ui.backdrop.DuneScene
import com.vic.inkflow.ui.backdrop.backdropSceneOf
import com.vic.inkflow.ui.backdrop.backdropScenes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 背景註冊表的回歸網（B0 架構重整時立的）。
 *
 * 背景：場景原本是 enum，設定存的是 enum name；改成 spec 之後，**存的是 id**。
 * 這條線一旦斷，症狀是「使用者的背景選擇靜默消失」，而且不會有人回報——
 * 所以釘在測試裡，不靠人記：
 *   1. id 唯一且非空（重複＝兩個場景搶同一格設定）
 *   2. label 唯一（選擇條上兩個一樣的名字＝使用者分不出來）
 *   3. 已發布的 id 認得出來（改 id 等於搬家，這裡會先爆）
 *   4. 認不得的 id（含刪除過的霓城）回預設，不留黑洞
 */
class BackdropRegistryTest {

    @Test
    fun everySceneHasIdAndLabel() {
        assertTrue("註冊表是空的", backdropScenes.isNotEmpty())
        backdropScenes.forEach {
            assertTrue("id 空白的場景：$it", it.id.isNotBlank())
            assertTrue("label 空白的場景：${it.id}", it.label.isNotBlank())
        }
    }

    @Test
    fun idsAreUnique() {
        val dup = backdropScenes.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue("id 重複：$dup", dup.isEmpty())
    }

    @Test
    fun labelsAreUnique() {
        val dup = backdropScenes.groupBy { it.label }.filterValues { it.size > 1 }.keys
        assertTrue("label 重複（選擇條上會分不出來）：$dup", dup.isEmpty())
    }

    /** 已發布的 id：**不准改**。要改就先寫相容對照，否則使用者的設定會靜默失去。 */
    @Test
    fun publishedIdsStillResolve() {
        listOf("DUNE", "TIDE", "SNOW", "STARFIELD", "EMBER", "PETAL", "SUNSETSEA").forEach { id ->
            assertEquals("id '$id' 認不出來了（舊設定會靜默失效）", id, backdropSceneOf(id).id)
        }
    }

    @Test
    fun unknownIdFallsBackToDefault() {
        assertEquals(DuneScene, backdropSceneOf("NEON"))
        assertEquals(DuneScene, backdropSceneOf(""))
        assertEquals(DuneScene, backdropSceneOf(null))
    }
}
