package com.vic.inkflow.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vic.inkflow.ui.theme.GlassSelectionInk
import com.vic.inkflow.ui.theme.ShapeMd
import dev.chrisbanes.haze.HazeState

/** 計算機的歷史一筆：左邊是算式原樣，右邊是收斂後的結果。 */
internal data class CalcHistoryEntry(val expression: String, val result: String)

/**
 * 工程計算機的輸入狀態。
 *
 * 為什麼狀態不放在對話框裡：`AnimatedDialog` 收起來時整個內容會離開 composition，
 * `remember` 的算式、歷史、記憶體全部歸零——使用者按關閉再打開，剛才算的東西就沒了。
 * 狀態由呼叫端（EditorScreen）持有，對話框只是視圖。
 *
 * 按鍵語意照實體工程計算機（Casio ClassWiz／TI-30X 那一套）：
 * `2nd` 只作用在**下一個**按鍵（按完自動解除）、`M+`／`M−` 作用在畫面上的值、
 * `ANS` 是最後一次的結果。
 */
internal class CalcSession {

    var expression by mutableStateOf("")
        private set

    /** true＝DEG（工程計算機的預設，也是多數教學／考卷的單位）。 */
    var degrees by mutableStateOf(true)
        private set

    /** 第二功能層：下一顆按鍵會執行鍵面小字那個功能，按完自動解除。 */
    var second by mutableStateOf(false)
        private set

    var notation by mutableStateOf(CalcNotation.AUTO)
        private set

    var memory by mutableStateOf<Double?>(null)
        private set

    var history by mutableStateOf<List<CalcHistoryEntry>>(emptyList())
        private set

    /** 按＝之後輸入框放的是結果：下一個數字要從頭開始，下一個運算子要接著算。 */
    var justEvaluated by mutableStateOf(false)
        private set

    /** 只有在「按＝」時才寫入；打的途中算式不完整很常見，不該一直跳錯誤。 */
    var error by mutableStateOf<String?>(null)
        private set

    /** 最後一次的結果，ANS 鍵用。 */
    private var answer: Double? = null

    private companion object {
        const val MAX_HISTORY = 20

        /**
         * 倒刪時要「整段吃掉」的函數前綴。
         *
         * 沒有這份清單，刪 `asin(` 會留下 `as`，使用者只能一個字一個字按掉。
         * 比對時取最長的那個，所以 `asin(` 不會被誤認成 `sin(`。
         */
        val FUNCTION_TOKENS = listOf(
            "asin(", "acos(", "atan(", "sinh(", "cosh(", "tanh(",
            "sqrt(", "cbrt(", "log2(", "round(", "floor(", "ceil(", "exp(", "abs(",
            "sin(", "cos(", "tan(", "log(", "ln(", "√(", "∛("
        )
    }

    fun insert(text: String) {
        if (text.isEmpty()) return
        error = null
        if (second) second = false
        if (justEvaluated) {
            justEvaluated = false
            val continuesResult = text.first() in "+-*/^!"
            if (!continuesResult) expression = ""
        }
        if (text == ".") {
            val currentNumber = expression.takeLastWhile { it.isDigit() || it == '.' }
            if (currentNumber.contains('.')) return
            if (currentNumber.isEmpty()) {
                expression += "0."
                return
            }
        }
        expression += text
    }

    fun clear() {
        expression = ""
        error = null
        justEvaluated = false
        second = false
    }

    fun backspace() {
        error = null
        justEvaluated = false
        second = false
        if (expression.isEmpty()) return
        val token = FUNCTION_TOKENS.filter { expression.endsWith(it) }.maxByOrNull { it.length }
        expression = if (token != null) expression.dropLast(token.length) else expression.dropLast(1)
    }

    /** `±`：整段算式換號。工程計算機上這顆鍵幾乎都這樣定義（不是只換最後一個數）。 */
    fun toggleSign() {
        error = null
        justEvaluated = false
        second = false
        expression = if (expression.startsWith("-")) expression.substring(1) else "-$expression"
    }

    fun toggleDegrees() {
        error = null
        degrees = !degrees
    }

    fun toggleSecond() {
        error = null
        second = !second
    }

    /** AUTO → SCI → ENG → AUTO。工程記號是這台機器與手機內建計算機的分界，值得一顆常駐。 */
    fun cycleNotation() {
        error = null
        notation = when (notation) {
            CalcNotation.AUTO -> CalcNotation.SCI
            CalcNotation.SCI -> CalcNotation.ENG
            CalcNotation.ENG -> CalcNotation.AUTO
        }
    }

    fun evaluateNow() {
        second = false
        when (val result = CalcEngine.evaluate(expression, degrees)) {
            is CalcResult.Ok -> {
                val text = CalcEngine.format(result.value, notation)
                history = (listOf(CalcHistoryEntry(expression.trim(), text)) + history).take(MAX_HISTORY)
                answer = result.value
                expression = text
                justEvaluated = true
                error = null
            }
            is CalcResult.Err -> error = result.message
        }
    }

    fun insertAnswer() {
        val value = answer
        if (value == null) {
            error = "還沒有算過任何東西"
            return
        }
        insert(CalcEngine.format(value, notation))
    }

    /** 歷史回填：點「上次」就把那一筆的算式叫回來改（實體計算機的多行播放）。 */
    fun recall(entry: CalcHistoryEntry) {
        expression = entry.expression
        justEvaluated = false
        error = null
    }

    fun memoryAdd() {
        second = false
        memory = (memory ?: 0.0) + currentValue()
    }

    fun memorySubtract() {
        second = false
        memory = (memory ?: 0.0) - currentValue()
    }

    fun memoryRecall() {
        val stored = memory
        if (stored == null) {
            error = "記憶體是空的"
            return
        }
        insert(CalcEngine.format(stored, notation))
    }

    fun memoryClear() {
        memory = null
    }

    /** 即時預覽（打的途中）。錯誤不回傳——那只是「還沒打完」，不是錯誤。 */
    fun preview(): CalcResult =
        if (expression.isBlank()) CalcResult.Err("空白")
        else CalcEngine.evaluate(expression, degrees)

    /** M+／M− 作用在「畫面上的值」：有預覽就用預覽，否則用上一次的答案。 */
    private fun currentValue(): Double =
        (preview() as? CalcResult.Ok)?.value ?: answer ?: 0.0
}

/**
 * 工程用計算機面板：工具列按鈕打開的浮動對話框。
 *
 * 版面照實體工程計算機的研究結果（Casio ClassWiz／TI-30X／Sharp EL 的共通慣例）：
 * - **雙行顯示**：上面是算式（小字），下面是結果（大字、隨位數縮字級），
 *   這是「不用回頭看自己剛打了什麼」的唯一解法，也是各家一致的做法。
 * - **2nd 第二功能**：鍵面小字標示替代功能（`sin⁻¹`／`eˣ`／`10ˣ`／`x³`／`∛`），
 *   按下 2nd 後主標籤翻成替代功能、按一顆就自動解除。
 * - **狀態列**：DEG/RAD、2nd、AUTO/SCI/ENG 與記憶體值常駐顯示（實體機的 D/R/G、M 指示）。
 * - **ENG 工程記號**：指數固定 3 的倍數，對應 k／M／G 字首。
 * - **記憶體**：MC／MR／M+／M− 一列小鍵，不佔主鍵盤（主鍵盤留給每天要按的東西）。
 *
 * 視覺：面板走共用三件套（`AnimatedDialog`＋`GlassDialogCustom`），按鍵走
 * `fauxGlassPanel`＋`glassClickable`；「螢幕」是一塊內凹玻璃（自繪內陰影、主色微光、
 * 頂端高光），結果用主題重點色，看起來像真的背光 LCD 而不是一個灰盒子。
 */
@Composable
internal fun EngineeringCalculatorDialog(
    visible: Boolean,
    session: CalcSession,
    isDark: Boolean,
    hazeState: HazeState?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // 複製：計算機的結果十之八九要貼到論文／筆記裡，這顆鍵比任何歷史紀錄都實用。
    val copyResult: () -> Unit = {
        val text = session.expression
        if (text.isNotBlank()) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("InkFlow 計算結果", text))
        }
    }

    AnimatedDialog(visible = visible) {
        GlassDialogCustom(
            onDismissRequest = onDismiss,
            isDark = isDark,
            hazeState = hazeState,
            title = { Text("工程計算機") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CalcScreen(session, isDark)
                    CalcMemoryRow(session, isDark)
                    CalcKeypad(session, isDark)
                }
            },
            buttons = {
                GlassTextButton(text = "複製結果", onClick = copyResult)
                Spacer(Modifier.width(8.dp))
                GlassTextButton(
                    text = "關閉",
                    onClick = onDismiss,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        )
    }
}

/**
 * 內凹的「螢幕」。
 *
 * 三層疊起來才有玻璃深度（都不靠 `Modifier.shadow()`，那是明令禁止的）：
 * ① 面板底（深色主題偏暗、淺色主題偏亮）；
 * ② 上緣往下的暗漸層＝光從上面打下來時凹槽的陰影；
 * ③ 右下角的主色微光＝結果那一行的「背光」，讓數字看起來是發亮的。
 */
@Composable
private fun Modifier.calculatorScreen(isDark: Boolean): Modifier {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val radius = 16.dp
    return this
        .clip(ShapeMd)
        .drawBehind {
            val corner = CornerRadius(radius.toPx(), radius.toPx())
            drawRoundRect(
                color = if (isDark) Color.Black.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.60f),
                cornerRadius = corner
            )
            drawRoundRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = if (isDark) 0.30f else 0.14f),
                    0.5f to Color.Transparent
                ),
                cornerRadius = corner
            )
            drawRoundRect(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width * 0.84f, size.height * 1.05f),
                    radius = size.maxDimension * 0.85f
                ),
                cornerRadius = corner
            )
            drawRoundRect(
                color = ink.copy(alpha = 0.10f),
                cornerRadius = corner,
                style = Stroke(width = 1.dp.toPx())
            )
            // 頂端一道細高光：玻璃邊的反光，沒有這條會像貼紙
            val inset = radius.toPx() + 10.dp.toPx()
            drawLine(
                color = Color.White.copy(alpha = 0.55f),
                start = Offset(inset, 1.dp.toPx()),
                end = Offset(size.width - inset, 1.dp.toPx()),
                strokeWidth = 1.dp.toPx()
            )
        }
}

@Composable
private fun CalcScreen(session: CalcSession, isDark: Boolean) {
    val ink = glassContentColor(isDark)
    val accent = MaterialTheme.colorScheme.primary
    val preview = session.preview()
    val last = session.history.firstOrNull()
    val showingResult = session.justEvaluated && last != null

    val expressionText = when {
        showingResult && last != null -> "${last.expression} ="
        session.expression.isBlank() -> "0"
        else -> session.expression
    }
    val resultText = when {
        showingResult && last != null -> last.result
        else -> (preview as? CalcResult.Ok)?.let { CalcEngine.format(it.value, session.notation) }
    }
    val errorText = session.error

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .calculatorScreen(isDark)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StatusChip(
                text = if (session.degrees) "DEG" else "RAD",
                isDark = isDark,
                active = session.degrees,
                onClick = session::toggleDegrees
            )
            StatusChip(
                text = "2nd",
                isDark = isDark,
                active = session.second,
                onClick = session::toggleSecond
            )
            StatusChip(
                text = session.notation.name,
                isDark = isDark,
                active = session.notation != CalcNotation.AUTO,
                onClick = session::cycleNotation
            )
            Spacer(Modifier.weight(1f))
            val stored = session.memory
            if (stored != null) {
                StatusChip(
                    text = "M ${CalcEngine.format(stored, session.notation)}",
                    isDark = isDark,
                    active = true,
                    onClick = session::memoryRecall
                )
            }
        }
        Text(
            text = expressionText,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            style = MaterialTheme.typography.titleMedium,
            color = ink.copy(alpha = 0.66f),
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        // 結果字級隨位數縮：12 位以上的科學記號不能把它撐出面板（各家計算機的共同做法）
        val big = when {
            errorText != null -> 18.sp
            resultText == null -> 26.sp
            resultText.length <= 10 -> 36.sp
            resultText.length <= 14 -> 28.sp
            else -> 22.sp
        }
        Text(
            text = errorText ?: resultText ?: "",
            modifier = Modifier.fillMaxWidth().height(46.dp),
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = big),
            color = if (errorText != null) MaterialTheme.colorScheme.error else accent,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 螢幕上的小膠囊：DEG／2nd／AUTO-ENG。亮起來＝目前生效。 */
@Composable
private fun StatusChip(
    text: String,
    isDark: Boolean,
    active: Boolean,
    onClick: () -> Unit
) {
    val ink = glassContentColor(isDark)
    Box(
        modifier = Modifier
            .height(24.dp)
            .clip(ShapeMd)
            .then(if (active) Modifier.glassSelectionPill(ShapeMd) else Modifier.fauxGlassPanel(isDark, ShapeMd))
            .glassClickable(onClick = onClick, shape = ShapeMd)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) GlassSelectionInk else ink.copy(alpha = 0.72f),
            maxLines = 1
        )
    }
}

/** 記憶體列：MC／MR／M+／M−。實體機也是獨立一小排，不塞進主鍵盤。 */
@Composable
private fun CalcMemoryRow(session: CalcSession, isDark: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MemChip("MC", isDark, session::memoryClear)
        MemChip("MR", isDark, session::memoryRecall)
        MemChip("M+", isDark, session::memoryAdd)
        MemChip("M−", isDark, session::memorySubtract)
    }
}

@Composable
private fun MemChip(label: String, isDark: Boolean, onClick: () -> Unit) {
    val ink = glassContentColor(isDark)
    Box(
        modifier = Modifier
            .height(24.dp)
            .fauxGlassPanel(isDark, ShapeMd)
            .glassClickable(onClick = onClick, shape = ShapeMd)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = ink.copy(alpha = 0.78f),
            maxLines = 1
        )
    }
}

@Composable
private fun CalcKeypad(session: CalcSession, isDark: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CalcRow {
            CalcKey("2nd", isDark = isDark, tier = CalcKeyTier.FUNCTION, active = session.second, modifier = Modifier.weight(1f)) {
                session.toggleSecond()
            }
            // 三角三鍵：2nd 是反三角（實體機的小字慣例）
            CalcKey("sin", alt = "sin⁻¹", isDark = isDark, tier = CalcKeyTier.FUNCTION, modifier = Modifier.weight(1f)) {
                session.insert(if (session.second) "asin(" else "sin(")
            }
            CalcKey("cos", alt = "cos⁻¹", isDark = isDark, tier = CalcKeyTier.FUNCTION, modifier = Modifier.weight(1f)) {
                session.insert(if (session.second) "acos(" else "cos(")
            }
            CalcKey("tan", alt = "tan⁻¹", isDark = isDark, tier = CalcKeyTier.FUNCTION, modifier = Modifier.weight(1f)) {
                session.insert(if (session.second) "atan(" else "tan(")
            }
            CalcKey("ln", alt = "eˣ", isDark = isDark, tier = CalcKeyTier.FUNCTION, modifier = Modifier.weight(1f)) {
                session.insert(if (session.second) "exp(" else "ln(")
            }
        }
        CalcRow {
            CalcKey("x²", alt = "x³", isDark = isDark, tier = CalcKeyTier.FUNCTION, modifier = Modifier.weight(1f)) {
                session.insert(if (session.second) "^3" else "^2")
            }
            CalcKey("xʸ", isDark = isDark, tier = CalcKeyTier.FUNCTION, modifier = Modifier.weight(1f)) {
                session.insert("^")
            }
            CalcKey("√", alt = "∛", isDark = isDark, tier = CalcKeyTier.FUNCTION, modifier = Modifier.weight(1f)) {
                session.insert(if (session.second) "∛(" else "√(")
            }
            CalcKey("x!", isDark = isDark, tier = CalcKeyTier.FUNCTION, modifier = Modifier.weight(1f)) {
                session.insert("!")
            }
            CalcKey("log", alt = "10ˣ", isDark = isDark, tier = CalcKeyTier.FUNCTION, modifier = Modifier.weight(1f)) {
                session.insert(if (session.second) "10^" else "log(")
            }
        }
        CalcRow {
            CalcKey("1/x", isDark = isDark, tier = CalcKeyTier.FUNCTION, modifier = Modifier.weight(1f)) {
                session.insert("^-1")
            }
            CalcKey("π", isDark = isDark, tier = CalcKeyTier.FUNCTION, modifier = Modifier.weight(1f)) {
                session.insert("π")
            }
            CalcKey("e", isDark = isDark, tier = CalcKeyTier.FUNCTION, modifier = Modifier.weight(1f)) {
                session.insert("e")
            }
            CalcKey("(", isDark = isDark, tier = CalcKeyTier.FUNCTION, modifier = Modifier.weight(1f)) {
                session.insert("(")
            }
            CalcKey(")", isDark = isDark, tier = CalcKeyTier.FUNCTION, modifier = Modifier.weight(1f)) {
                session.insert(")")
            }
        }
        CalcRow {
            DigitKey("7", session, isDark)
            DigitKey("8", session, isDark)
            DigitKey("9", session, isDark)
            CalcKey("÷", isDark = isDark, tier = CalcKeyTier.OPERATOR, modifier = Modifier.weight(1f)) {
                session.insert("/")
            }
            CalcKey("C", isDark = isDark, tier = CalcKeyTier.OPERATOR, modifier = Modifier.weight(1f)) {
                session.clear()
            }
        }
        CalcRow {
            DigitKey("4", session, isDark)
            DigitKey("5", session, isDark)
            DigitKey("6", session, isDark)
            CalcKey("×", isDark = isDark, tier = CalcKeyTier.OPERATOR, modifier = Modifier.weight(1f)) {
                session.insert("*")
            }
            CalcKey("⌫", isDark = isDark, tier = CalcKeyTier.OPERATOR, modifier = Modifier.weight(1f)) {
                session.backspace()
            }
        }
        CalcRow {
            DigitKey("1", session, isDark)
            DigitKey("2", session, isDark)
            DigitKey("3", session, isDark)
            CalcKey("−", isDark = isDark, tier = CalcKeyTier.OPERATOR, modifier = Modifier.weight(1f)) {
                session.insert("-")
            }
            CalcKey("±", isDark = isDark, tier = CalcKeyTier.OPERATOR, modifier = Modifier.weight(1f)) {
                session.toggleSign()
            }
        }
        CalcRow {
            DigitKey("0", session, isDark)
            CalcKey(".", isDark = isDark, tier = CalcKeyTier.DIGIT, modifier = Modifier.weight(1f)) {
                session.insert(".")
            }
            CalcKey("ANS", isDark = isDark, tier = CalcKeyTier.OPERATOR, modifier = Modifier.weight(1f)) {
                session.insertAnswer()
            }
            CalcKey("+", isDark = isDark, tier = CalcKeyTier.OPERATOR, modifier = Modifier.weight(1f)) {
                session.insert("+")
            }
            CalcKey("=", isDark = isDark, tier = CalcKeyTier.ACCENT, modifier = Modifier.weight(1f)) {
                session.evaluateNow()
            }
        }
    }
}

@Composable
private fun RowScope.DigitKey(label: String, session: CalcSession, isDark: Boolean) {
    CalcKey(label, isDark = isDark, tier = CalcKeyTier.DIGIT, modifier = Modifier.weight(1f)) {
        session.insert(label)
    }
}

@Composable
private fun CalcRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

private enum class CalcKeyTier {
    /** 函數鍵：低調，字色淡一階。 */
    FUNCTION,

    /** 數字鍵：一般玻璃。 */
    DIGIT,

    /** 運算鍵：主色薄霧，跟數字鍵一眼分得開。 */
    OPERATOR,

    /** ＝：亮藥丸＋深墨字，整台機器最重的一顆。 */
    ACCENT
}

/**
 * 一顆計算機按鍵。
 *
 * 三種材質分層（不是只有顏色差）：函數鍵最淡、數字鍵一般、運算鍵帶主色薄霧、
 * `=` 是亮藥丸＋深墨字。`alt` 是 2nd 的第二功能，印在鍵面上方的小字——
 * 這是實體計算機貼 shift 標籤的位置，使用者不用查說明書就知道按 2nd 會變什麼。
 */
@Composable
private fun CalcKey(
    label: String,
    isDark: Boolean,
    tier: CalcKeyTier,
    modifier: Modifier = Modifier,
    alt: String? = null,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = glassContentColor(isDark)
    val tint = when (tier) {
        CalcKeyTier.FUNCTION -> accent.copy(alpha = if (isDark) 0.04f else 0.02f)
        CalcKeyTier.DIGIT -> Color.Transparent
        CalcKeyTier.OPERATOR -> accent.copy(alpha = if (isDark) 0.13f else 0.09f)
        CalcKeyTier.ACCENT -> Color.Transparent
    }
    val mainColor = when (tier) {
        CalcKeyTier.FUNCTION -> ink.copy(alpha = 0.78f)
        CalcKeyTier.DIGIT -> ink
        CalcKeyTier.OPERATOR -> accent
        CalcKeyTier.ACCENT -> GlassSelectionInk
    }
    val secondActive = active
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(ShapeMd)
            .then(
                if (tier == CalcKeyTier.ACCENT || secondActive) Modifier.glassSelectionPill(ShapeMd)
                else Modifier.fauxGlassPanel(isDark, ShapeMd)
            )
            .background(tint, ShapeMd)
            .glassClickable(onClick = onClick, shape = ShapeMd),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (alt != null) {
                // 2nd 開啟時角色互換：替代功能變主標籤，原功能退成小字
                Text(
                    text = if (secondActive) label else alt,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = if (secondActive) GlassSelectionInk.copy(alpha = 0.7f)
                    else accent.copy(alpha = 0.85f),
                    maxLines = 1
                )
            }
            Text(
                text = if (secondActive && alt != null) alt else label,
                style = MaterialTheme.typography.titleMedium,
                color = if (secondActive) GlassSelectionInk else mainColor,
                maxLines = 1
            )
        }
    }
}
