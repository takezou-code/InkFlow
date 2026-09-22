package com.vic.inkflow

import androidx.compose.ui.geometry.Offset
import com.vic.inkflow.util.DocTransform
import org.junit.Assert.assertEquals
import org.junit.Test

class DocTransformTest {

    @Test
    fun scaleMatchesLegacyInkCanvasFormula() {
        // 舊式：sx = if (canvasW > 0) canvasW / modelW else 1f
        assertEquals(1080f / 595f, DocTransform.scaleX(1080f, 595f), 0.0001f)
        assertEquals(1f, DocTransform.scaleX(0f, 595f), 0f)
        assertEquals(1f, DocTransform.scaleX(-5f, 595f), 0f)
        assertEquals(1600f / 842f, DocTransform.scaleY(1600f, 842f), 0.0001f)
        assertEquals(1f, DocTransform.scaleY(0f, 842f), 0f)
    }

    @Test
    fun invScaleMatchesLegacyCoerceFormula() {
        // 舊式：modelW / canvasW.coerceAtLeast(1f)
        assertEquals(595f / 1080f, DocTransform.invScaleX(1080f, 595f), 0.0001f)
        assertEquals(595f / 1f, DocTransform.invScaleX(0f, 595f), 0.0001f)
        assertEquals(595f / 1f, DocTransform.invScaleX(-5f, 595f), 0.0001f)
        assertEquals(842f / 1600f, DocTransform.invScaleY(1600f, 842f), 0.0001f)
    }

    @Test
    fun roundTripIsIdentity() {        val canvasW = 1080f
        val canvasH = 1600f
        val modelW = 595f
        val modelH = 842f
        val model = Offset(100f, 200f)
        val canvas = DocTransform.modelToCanvas(model, canvasW, canvasH, modelW, modelH)
        assertEquals(100f * 1080f / 595f, canvas.x, 0.01f)
        assertEquals(200f * 1600f / 842f, canvas.y, 0.01f)
        val back = DocTransform.canvasToModel(canvas, canvasW, canvasH, modelW, modelH)
        assertEquals(model.x, back.x, 0.01f)
        assertEquals(model.y, back.y, 0.01f)
    }

    @Test
    fun uncheckedMatchesLegacyVmFormulaBitwise() {
        // 舊 VM 內聯式：modelW / cW（無守衛，含除零語義）。
        assertEquals(595f / 1080f, DocTransform.invScaleXUnchecked(1080f, 595f), 0f)
        assertEquals(595f / 0f, DocTransform.invScaleXUnchecked(0f, 595f), 0f)
        assertEquals(842f / 1600f, DocTransform.invScaleYUnchecked(1600f, 842f), 0f)
    }

    @Test
    fun guardedOnCoercedEqualsUnchecked() {
        // 1674/1685 模式：cW/cH 先 coerce，兩版必一致。
        val cW = 1080f.coerceAtLeast(1f)
        val cH = 1600f.coerceAtLeast(1f)
        assertEquals(
            100f * DocTransform.invScaleXUnchecked(cW, 595f),
            DocTransform.canvasToModel(Offset(100f, 200f), cW, cH, 595f, 842f).x,
            0f
        )
    }
}
