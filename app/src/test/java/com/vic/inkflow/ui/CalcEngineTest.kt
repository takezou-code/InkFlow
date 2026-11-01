package com.vic.inkflow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.PI

/**
 * 工程計算機的算式核心。
 *
 * 這支只釘「行為」不釘實作：優先序、右結合、隱含乘法、DEG/RAD、錯誤訊息的存在性，
 * 以及顯示格式。格式化是最容易被「看起來差不多」騙過的一段（0.1+0.2 會變
 * 0.30000000000000004、1/3 會變 0.3333333333333333），所以逐字串釘死。
 */
class CalcEngineTest {

    private fun ok(expression: String, degrees: Boolean = true): Double {
        val result = CalcEngine.evaluate(expression, degrees)
        assertTrue("「$expression」應該算得出來，實際是 $result", result is CalcResult.Ok)
        return (result as CalcResult.Ok).value
    }

    private fun err(expression: String, degrees: Boolean = true): String {
        val result = CalcEngine.evaluate(expression, degrees)
        assertTrue("「$expression」應該被擋下來，實際是 $result", result is CalcResult.Err)
        return (result as CalcResult.Err).message
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-9) {
        assertTrue(
            "expected $expected, got $actual",
            abs(expected - actual) <= tolerance * maxOf(1.0, abs(expected))
        )
    }

    @Test
    fun precedenceAndParentheses() {
        assertEquals(14.0, ok("2+3*4"), 0.0)
        assertEquals(20.0, ok("(2+3)*4"), 0.0)
        assertEquals(2.0, ok("10-4-4"), 0.0)
        assertEquals(1.0, ok("8/4/2"), 0.0)
        assertEquals(0.0, ok("(1+2)-(1+2)"), 0.0)
    }

    @Test
    fun powerIsRightAssociativeAndBindsTighterThanUnaryMinus() {
        assertEquals(512.0, ok("2^3^2"), 0.0)
        assertEquals(-4.0, ok("-2^2"), 0.0)
        assertEquals(4.0, ok("(-2)^2"), 0.0)
        assertEquals(0.25, ok("2^-2"), 0.0)
        assertTrue(err("(-8)^(1/3)").isNotEmpty())
    }

    @Test
    fun implicitMultiplication() {
        assertClose(2 * PI, ok("2π"))
        assertEquals(6.0, ok("3(1+1)"), 0.0)
        assertClose(1.0, ok("2sin(30)"))
        assertClose(2 * Math.E, ok("2e"), 1e-12)
        // 指數記號不能被誤認成「2×e3」：1e3 是 1000
        assertEquals(1000.0, ok("1e3"), 0.0)
        assertClose(2000.0 * Math.E, ok("2e3e"), 1e-9)
    }

    @Test
    fun factorial() {
        assertEquals(120.0, ok("5!"), 0.0)
        assertEquals(1.0, ok("0!"), 0.0)
        assertEquals(726.0, ok("3!+6!"), 0.0)
        assertTrue(err("3.5!").contains("階乘"))
        assertTrue(err("200!").contains("170"))
        assertTrue(err("(-1)!").contains("階乘"))
    }

    @Test
    fun trigonometryFollowsAngleMode() {
        assertClose(0.5, ok("sin(30)", degrees = true))
        assertClose(1.0, ok("sin(π/2)", degrees = false))
        assertClose(90.0, ok("asin(1)", degrees = true))
        assertClose(PI / 2, ok("asin(1)", degrees = false))
        // 雙曲函數不吃角度，兩種模式要一樣
        assertClose(ok("tanh(1)", degrees = true), ok("tanh(1)", degrees = false))
        assertTrue(err("asin(2)").contains("−1..1"))
    }

    @Test
    fun rootsAndLogs() {
        assertEquals(3.0, ok("√(9)"), 0.0)
        assertEquals(3.0, ok("sqrt(9)"), 0.0)
        assertEquals(-2.0, ok("cbrt(-8)"), 0.0)
        // 鍵盤上的 2nd 直接送 ∛(，不是每個使用者都會打 cbrt
        assertEquals(-2.0, ok("∛(-8)"), 0.0)
        assertEquals(2.0, ok("∛(8)"), 0.0)
        assertEquals(3.0, ok("log(1000)"), 0.0)
        assertEquals(5.0, ok("log2(32)"), 0.0)
        assertClose(1.0, ok("ln(e)"))
        assertTrue(err("√(-1)").contains("非負"))
        assertTrue(err("ln(0)").contains("正數"))
    }

    @Test
    fun divisionByZeroAndGarbageAreErrorsNotCrashes() {
        assertTrue(err("1/0").contains("除以零"))
        assertTrue(err("1/(2-2)").contains("除以零"))
        assertTrue(err("").isNotEmpty())
        assertTrue(err("   ").isNotEmpty())
        assertTrue(err("2+").isNotEmpty())
        assertTrue(err("(1+2").isNotEmpty())
        assertTrue(err("2&3").isNotEmpty())
        assertTrue(err("foo(1)").contains("foo"))
        assertTrue(err("sin 30").isNotEmpty())
    }

    @Test
    fun normaliseAcceptsTextCopiedFromRealDocuments() {
        assertEquals("2*3/4", CalcEngine.normalise("2×3÷4"))
        assertEquals("2-1", CalcEngine.normalise("2 − 1"))
        assertEquals("(1)", CalcEngine.normalise("（1）"))
        assertEquals("1234.5", CalcEngine.normalise("1,234.5"))
        assertEquals(6.0, ok("2 × 3"), 0.0)
        assertEquals(1.0, ok("2 − 1"), 0.0)
    }

    @Test
    fun formatHidesBinaryNoiseAndSwitchesNotation() {
        assertEquals("0.3", CalcEngine.format(0.1 + 0.2))
        assertEquals("0.333333333333", CalcEngine.format(1.0 / 3.0))
        assertEquals("15", CalcEngine.format(15.0))
        assertEquals("0", CalcEngine.format(-0.0))
        assertEquals("10", CalcEngine.format(10.000000000000002))
        assertEquals("1e13", CalcEngine.format(1e13))
        assertEquals("1.5e16", CalcEngine.format(1.5e16))
        assertEquals("1e-7", CalcEngine.format(1e-7))
        assertEquals("-2.5", CalcEngine.format(-2.5))
        assertEquals("∞", CalcEngine.format(Double.POSITIVE_INFINITY))
        assertEquals("−∞", CalcEngine.format(Double.NEGATIVE_INFINITY))
        assertEquals("錯誤", CalcEngine.format(Double.NaN))
    }

    @Test
    fun scientificAndEngineeringNotation() {
        // SCI：一律 a×10ⁿ，a ∈ [1,10)
        assertEquals("1.2345e4", CalcEngine.format(12345.0, CalcNotation.SCI))
        assertEquals("1e-3", CalcEngine.format(0.001, CalcNotation.SCI))
        assertEquals("-2.5e0", CalcEngine.format(-2.5, CalcNotation.SCI))
        // ENG：指數固定 3 的倍數，尾數 ∈ [1,1000)——看到 12.345e3 就是 12.345k
        assertEquals("12.345e3", CalcEngine.format(12345.0, CalcNotation.ENG))
        assertEquals("-12.345e3", CalcEngine.format(-12345.0, CalcNotation.ENG))
        assertEquals("1e-3", CalcEngine.format(0.001, CalcNotation.ENG))
        assertEquals("999.999e3", CalcEngine.format(999.999e3, CalcNotation.ENG))
        assertEquals("1e6", CalcEngine.format(1e6, CalcNotation.ENG))
        // 收斂到 12 位會把尾數推過界：999.999999999999 → 1000，要退回 1e3
        assertEquals("1e3", CalcEngine.format(999.999999999999, CalcNotation.ENG))
        assertEquals("1e1", CalcEngine.format(9.99999999999999, CalcNotation.SCI))
        // AUTO 不受影響（預設外觀不變）
        assertEquals("12345", CalcEngine.format(12345.0))
    }

    @Test
    fun notationFormattedResultCanBeFedBackIn() {
        listOf(12345.0, 0.001, -12345.0, 1e6, 999.999999999999).forEach { value ->
            CalcNotation.values().forEach { notation ->
                val text = CalcEngine.format(value, notation)
                val again = CalcEngine.evaluate(text)
                assertTrue("round-trip 失敗：$notation $text → $again", again is CalcResult.Ok)
            }
        }
    }

    @Test
    fun formattedResultCanBeFedBackIn() {
        // 按＝之後輸入框放的就是格式化結果；它必須還能再被解析（否則「接著算」會爆）
        listOf(1e13, 1.5e16, 1e-7, 1.0 / 3.0, 12345.678).forEach { value ->
            val text = CalcEngine.format(value)
            val again = CalcEngine.evaluate(text)
            assertTrue("round-trip 失敗：$text → $again", again is CalcResult.Ok)
        }
    }
}
