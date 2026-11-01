package com.vic.inkflow.ui

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** 工程計算機的求值結果：成功帶值，失敗帶「給人看的一句話」。 */
internal sealed interface CalcResult {
    data class Ok(val value: Double) : CalcResult
    data class Err(val message: String) : CalcResult
}

/**
 * 工程計算機的算式核心：純 Kotlin、無 Android 依賴、無第三方 parser。
 *
 * 為什麼自己寫 parser 而不是 `ScriptEngine`／`eval`：平板端沒有 JS 引擎可以借用
 * （那個只在 WebView 裡），而 Android 的 `Rhino`／`ScriptEngine` 早就被移除；
 * 帶一個幾百 KB 的 expression library 進 APK 也不值得。這支支援的東西不多，
 * 剛好是一台工程計算機該有的：四則、括號、次方（右結合）、階乘、三角／對數／根號、
 * π／e、隱含乘法（`2π`、`3(1+2)`、`2sin(30)`），以及 DEG／RAD。
 *
 * **錯誤一律回 [CalcResult.Err]，不丟例外**：UI 把它顯示在結果行，使用者改一個字
 * 就重算，不會因為打錯一個括號整台計算機變空白。
 *
 * 可測性：整支是 `internal object`，行為由 `CalcEngineTest` 釘住（含格式化）。
 */
internal object CalcEngine {

    /** `n!` 的上限：171! 就溢出了 double，再多只是 Infinity。 */
    private const val MAX_FACTORIAL = 170

    /**
     * 顯示位數。
     *
     * 12 位是「雙精度能承諾的十進位有效位數」與「螢幕讀得完」的交集：
     * `0.1 + 0.2` 的二進位真值是 0.30000000000000004，12 位會收成 0.3；
     * 再少就會把 1/7 之類的算式截得比手算還不準。
     */
    private const val SIGNIFICANT_DIGITS = 12

    /** 超過這個量級就改用科學記號，否則整行被 0 塞滿。 */
    private const val SCI_UPPER = 1e12
    private const val SCI_LOWER = 1e-6

    fun evaluate(expression: String, degrees: Boolean = true): CalcResult {
        val src = normalise(expression)
        if (src.isBlank()) return CalcResult.Err("空白")
        return try {
            val parser = Parser(src, degrees)
            val value = parser.parseExpression()
            parser.expectEnd()
            if (value.isNaN()) CalcResult.Err("結果不是數字") else CalcResult.Ok(value)
        } catch (e: CalcError) {
            CalcResult.Err(e.message ?: "算式錯誤")
        }
    }

    /**
     * 把算式貼回輸入框時用的「乾淨」形式：全形與排版符號都轉成 ASCII 運算子。
     *
     * 這一步同時是「使用者從 PDF 複製算式」的入口——`×`、`÷`、`−`（U+2212）、
     * 千分位逗號、全形空白都會出現在複製來的文字裡，不轉就只會回「看不懂的字元」。
     */
    fun normalise(raw: String): String {
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                '×', '·', '⋅', '＊' -> out.append('*')
                '÷', '／' -> out.append('/')
                '−', '–', '—', '－' -> out.append('-')
                '＋' -> out.append('+')
                '（' -> out.append('(')
                '）' -> out.append(')')
                '！' -> out.append('!')
                '．' -> out.append('.')
                '，', ',', ' ', '\u00A0', '\u3000', '\t', '\n', '\r' -> Unit
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * 顯示用格式：整數不帶小數點、浮點誤差收乾淨、極端量級走科學記號。
     *
     * 收斂到固定 12 位有效數字（不是「固定小數位」）：1/3 顯示 0.333333333333，
     * 而 12345678901234 不會被截成 12345678901200。
     */
    fun format(value: Double): String {
        if (value.isNaN()) return "錯誤"
        if (value.isInfinite()) return if (value > 0) "∞" else "−∞"
        if (value == 0.0) return "0"
        val magnitude = abs(value)
        if (magnitude >= SCI_UPPER || magnitude < SCI_LOWER) return scientific(value)
        if (value == floor(value) && magnitude < SCI_UPPER) return value.toLong().toString()
        val rounded = BigDecimal(value)
            .round(MathContext(SIGNIFICANT_DIGITS, RoundingMode.HALF_UP))
            .stripTrailingZeros()
        return rounded.toPlainString()
    }

    private fun scientific(value: Double): String {
        // %.10e → "1.2345000000e+16"；尾零與多餘的點收掉，指數用 "e16" 這種寫法
        val raw = String.format(Locale.US, "%.10e", value)
        val parts = raw.split('e')
        val mantissa = parts[0].trimEnd('0').trimEnd('.')
        val exponent = parts[1].toInt()
        return "${mantissa}e$exponent"
    }

    /** 內部錯誤：只有 parser 自己丟，外面一律轉成 [CalcResult.Err]。 */
    private class CalcError(override val message: String?) : Exception(message)

    /**
     * 遞迴下降 parser。
     *
     * 文法（由寬到窄）：
     * ```
     * expression := term (('+' | '-') term)*
     * term       := unary (('*' | '/') unary | 隱含乘法)*
     * unary      := ('+' | '-') unary | power
     * power      := postfix ('^' unary)?        // 右結合，且容許 2^-3
     * postfix    := primary ('!')*
     * primary    := 數字 | 常數 | 函數 '(' expression ')' | '(' expression ')'
     * ```
     * `-2^2` 因此是 −4（一元負號比次方鬆），與多數工程計算機一致；
     * 要 (−2)² 就自己加括號。
     */
    private class Parser(private val text: String, private val degrees: Boolean) {
        private var index = 0

        fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipSpaces()
                when (peek()) {
                    '+' -> { index++; value += parseTerm() }
                    '-' -> { index++; value -= parseTerm() }
                    else -> return value
                }
            }
        }

        fun expectEnd() {
            skipSpaces()
            if (index < text.length) fail("看不懂的「${text.substring(index)}」")
        }

        private fun parseTerm(): Double {
            var value = parseUnary()
            while (true) {
                skipSpaces()
                when {
                    peek() == '*' -> { index++; value *= parseUnary() }
                    peek() == '/' -> {
                        index++
                        val divisor = parseUnary()
                        if (divisor == 0.0) fail("除以零")
                        value /= divisor
                    }
                    // 隱含乘法：2π、3(1+2)、2sin(30)。工程計算機沒有這條會很難用。
                    startsPrimary() -> value *= parseUnary()
                    else -> return value
                }
            }
        }

        private fun parseUnary(): Double {
            skipSpaces()
            return when (peek()) {
                '+' -> { index++; parseUnary() }
                '-' -> { index++; -parseUnary() }
                else -> parsePower()
            }
        }

        private fun parsePower(): Double {
            val base = parsePostfix()
            skipSpaces()
            if (peek() != '^') return base
            index++
            val exponent = parseUnary()
            if (base < 0 && exponent != floor(exponent)) fail("負數的無理次方沒有實數解")
            return base.pow(exponent)
        }

        private fun parsePostfix(): Double {
            var value = parsePrimary()
            while (true) {
                skipSpaces()
                if (peek() != '!') return value
                index++
                value = factorial(value)
            }
        }

        private fun parsePrimary(): Double {
            skipSpaces()
            val ch = peek()
            return when {
                ch == '(' -> {
                    index++
                    val value = parseExpression()
                    skipSpaces()
                    if (peek() != ')') fail("缺少 )")
                    index++
                    value
                }
                ch.isDigit() || ch == '.' -> parseNumber()
                ch == 'π' -> { index++; PI }
                ch.isLetter() || ch == '√' -> parseIdentifier()
                ch == '\u0000' -> fail("算式不完整")
                else -> fail("看不懂的字元「$ch」")
            }
        }

        private fun parseNumber(): Double {
            val start = index
            while (peek().isDigit()) index++
            if (peek() == '.') {
                index++
                while (peek().isDigit()) index++
            }
            // 指數記號：只有「e 後面真的接數字」才算數字的一部分。
            // 否則 `2e` 應該是 2×e（尤拉數），不是壞掉的 2e。
            if (peek() == 'e' || peek() == 'E') {
                var lookahead = index + 1
                if (lookahead < text.length && (text[lookahead] == '+' || text[lookahead] == '-')) lookahead++
                if (lookahead < text.length && text[lookahead].isDigit()) {
                    index = lookahead
                    while (peek().isDigit()) index++
                }
            }
            val raw = text.substring(start, index)
            return raw.toDoubleOrNull() ?: fail("數字「$raw」看不懂")
        }

        private fun parseIdentifier(): Double {
            val start = index
            // 名稱可以含數字：`log2`、`log10` 都是真實存在的函數名。
            // 只有**開頭**必須是字母（數字開頭的一律走 parseNumber）。
            index++
            while (true) {
                val ch = peek()
                if (ch.isLetterOrDigit() || ch == '√') index++ else break
            }
            val name = text.substring(start, index).lowercase()
            skipSpaces()
            if (peek() != '(') {
                return when (name) {
                    "e" -> E
                    "pi", "π" -> PI
                    "tau" -> PI * 2
                    "ans" -> fail("ANS 要先按過一次＝")
                    else -> fail("「$name」缺少 (")
                }
            }
            index++
            val argument = parseExpression()
            skipSpaces()
            if (peek() != ')') fail("$name( 缺少 )")
            index++
            return applyFunction(name, argument)
        }

        private fun applyFunction(name: String, x: Double): Double {
            fun toRadians(v: Double) = if (degrees) v * PI / 180.0 else v
            fun fromRadians(v: Double) = if (degrees) v * 180.0 / PI else v
            return when (name) {
                "sin" -> sin(toRadians(x))
                "cos" -> cos(toRadians(x))
                "tan" -> tan(toRadians(x))
                "asin" -> {
                    if (x < -1 || x > 1) fail("asin 只吃 −1..1")
                    fromRadians(asin(x))
                }
                "acos" -> {
                    if (x < -1 || x > 1) fail("acos 只吃 −1..1")
                    fromRadians(acos(x))
                }
                "atan" -> fromRadians(atan(x))
                "sinh" -> sinh(x)
                "cosh" -> cosh(x)
                "tanh" -> tanh(x)
                "ln" -> { if (x <= 0) fail("ln 只吃正數"); ln(x) }
                "log" -> { if (x <= 0) fail("log 只吃正數"); log10(x) }
                "log2" -> { if (x <= 0) fail("log2 只吃正數"); log2(x) }
                "sqrt", "√" -> { if (x < 0) fail("√ 只吃非負數"); sqrt(x) }
                "cbrt" -> cbrt(x)
                "exp" -> exp(x)
                "abs" -> abs(x)
                "floor" -> floor(x)
                "ceil" -> ceil(x)
                "round" -> round(x)
                else -> fail("沒有「$name」這個函數")
            }
        }

        private fun factorial(value: Double): Double {
            if (value < 0 || value != floor(value)) fail("階乘只吃非負整數")
            if (value > MAX_FACTORIAL) fail("最多只算到 ${MAX_FACTORIAL}!")
            var result = 1.0
            var k = 2.0
            while (k <= value) {
                result *= k
                k += 1.0
            }
            return result
        }

        private fun startsPrimary(): Boolean {
            val ch = peek()
            return ch.isDigit() || ch == '.' || ch == '(' || ch == 'π' || ch == '√' || ch.isLetter()
        }

        private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

        private fun skipSpaces() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun fail(message: String): Nothing = throw CalcError(message)
    }
}
