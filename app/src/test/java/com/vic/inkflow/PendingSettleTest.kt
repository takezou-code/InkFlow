package com.vic.inkflow

import com.vic.inkflow.ui.PendingImageSettle
import com.vic.inkflow.ui.PendingTextSettle
import com.vic.inkflow.data.ImageAnnotationEntity
import com.vic.inkflow.data.TextAnnotationEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提交後「落定等待」的回歸網（防放手瞬間閃爍）。
 *
 * ## 這個 bug 的因果
 *
 * 圖片/文字的移動、縮放、旋轉都是 `viewModelScope.launch(Dispatchers.IO)` **非同步**
 * 寫 DB，而預覽位移的歸零是**同步**的。提交後到 DB 熱流回報前的那幾幀，繪製會用
 * 「DB 舊值 + 0」→ 圖片先閃回原位，DB 更新後再跳到新位置（實機回報：閃一下）。
 *
 * 修法：提交後**不歸零**，把「這次 commit 應該讓 DB 變成什麼」記成 Pending*，
 * 等熱流回報的實體真的等於預期值，才清預覽。
 *
 * 所以 `matches` 就是整個機制的核心 —— 它決定「什麼時候算落定」。
 */
class PendingSettleTest {

    private fun img(
        id: String = "img1",
        x: Float = 100f, y: Float = 200f,
        w: Float = 200f, h: Float = 150f,
        rot: Float = 0f,
    ) = ImageAnnotationEntity(
        id = id, documentUri = "doc", pageIndex = 0,
        uri = "file:///x.jpg",
        modelX = x, modelY = y, modelWidth = w, modelHeight = h, rotation = rot
    )

    private fun settle(
        id: String = "img1",
        x: Float = 100f, y: Float = 200f,
        w: Float = 200f, h: Float = 150f,
        rot: Float = 0f,
    ) = PendingImageSettle(id, x, y, w, h, rot)

    // ── 圖片 ──

    @Test
    fun identicalImageSettles() {
        assertTrue(settle().matches(img()))
    }

    @Test
    fun differentPositionDoesNotSettle() {
        // 這是「閃爍」那一幀：DB 還是舊位置 → 不能歸零預覽。
        assertFalse(settle(x = 300f).matches(img(x = 100f)))
    }

    @Test
    fun differentSizeDoesNotSettle() {
        assertFalse(settle(w = 400f).matches(img(w = 200f)))
    }

    @Test
    fun differentRotationDoesNotSettle() {
        assertFalse(settle(rot = 90f).matches(img(rot = 0f)))
    }

    @Test
    fun differentIdDoesNotSettle() {
        assertFalse(settle(id = "a").matches(img(id = "b")))
    }

    @Test
    fun tinyFloatErrorStillSettles() {
        // DB 走 REAL 落盤，model 座標算完再存會有微小誤差。
        // 若用精確相等，預覽會永遠卡住不歸零（圖片停在「DB 值 + 殘留位移」）。
        assertTrue(settle(x = 100.00001f).matches(img(x = 100f)))
        assertTrue(settle(rot = 45.0001f).matches(img(rot = 45f)))
    }

    @Test
    fun rotationWrapsToSameAngle() {
        // 提交 -30° 會被 normalize 成 330°，比對時要視為同一個值。
        assertTrue(settle(rot = 330f).matches(img(rot = 330f)))
    }

    // ── 文字 ──

    private fun txt(id: String = "t1", x: Float = 50f, y: Float = 80f, size: Float = 20f) =
        TextAnnotationEntity(
            id = id, documentUri = "doc", pageIndex = 0,
            text = "hello", modelX = x, modelY = y, fontSize = size, colorArgb = 0
        )

    private fun tSettle(id: String = "t1", x: Float = 50f, y: Float = 80f, size: Float = 20f) =
        PendingTextSettle(id, x, y, size)

    @Test
    fun identicalTextSettles() {
        assertTrue(tSettle().matches(txt()))
    }

    @Test
    fun movedTextDoesNotSettle() {
        assertFalse(tSettle(y = 300f).matches(txt(y = 80f)))
    }

    @Test
    fun resizedTextDoesNotSettle() {
        assertFalse(tSettle(size = 40f).matches(txt(size = 20f)))
    }

    @Test
    fun textTinyErrorStillSettles() {
        assertTrue(tSettle(size = 20.00001f).matches(txt(size = 20f)))
    }

    @Test
    fun textDifferentIdDoesNotSettle() {
        assertFalse(tSettle(id = "a").matches(txt(id = "b")))
    }
}