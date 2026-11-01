package com.vic.inkflow.ui

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vic.inkflow.ui.theme.GlassSelectionInk
import com.vic.inkflow.ui.theme.ShapeMd
import dev.chrisbanes.haze.HazeState

/** 計算機的歷史一筆：左邊是算式原樣，右邊是收斂後的結果。 */
internal data class CalcHistoryEntry(val expression: String, val result: String)

/**
 * 工程計算機的輸入狀態。
 *
 * 為什麼狀態不放在對話框裡：`AnimatedDialog` 收起來時整個內容會離開 composition，
 * `remember` 的算式、歷史、DEG/RAD 全部歸零——使用者按關閉再打開，剛才算的東西
 * 就沒了。狀態由呼叫端（EditorScreen）持有，對話框只是視圖。
 *
 * 為什麼不用 ViewModel：這裡沒有 IO、沒有生命週期、沒有跨螢幕共用，
 * 一個 state holder 就夠；放 ViewModel 反而要為它多開一個 `SavedStateHandle`。
 */
internal class CalcSession {

    var expression by mutableStateOf("")
        private set

    /** true＝DEG（工程計算機的預設，也是多數教學／考卷的單位）。 */
    var degrees by mutableStateOf(true)
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
        const val MAX_HISTORY = 12

        /**
         * 倒刪時要「整段吃掉」的函數前綴。
         *
         * 沒有這份清單，刪 `asin(` 會留下 `as`，使用者只能一個字一個字按掉。
         * 順序不重要（比對時取最長的），但內容要跟按鍵盤完全對得上。
         */
        val FUNCTION_TOKENS = listOf(
            "asin(", "acos(", "atan(", "sinh(", "cosh(", "tanh(",
            "sqrt(", "cbrt(", "log2(", "round(", "floor(", "ceil(", "exp(", "abs(",
            "sin(", "cos(", "tan(", "log(", "ln(", "√("
        )
    }

    fun insert(text: String) {
        if (text.isEmpty()) return
        error = null
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
    }

    fun backspace() {
        error = null
        justEvaluated = false
        if (expression.isEmpty()) return
        val token = FUNCTION_TOKENS.filter { expression.endsWith(it) }.maxByOrNull { it.length }
        expression = if (token != null) expression.dropLast(token.length) else expression.dropLast(1)
    }

    /** `±`：整段算式換號。工程計算機上這顆鍵幾乎都這樣定義（不是只換最後一個數）。 */
    fun toggleSign() {
        error = null
        justEvaluated = false
        expression = if (expression.startsWith("-")) expression.substring(1) else "-$expression"
    }

    fun toggleDegrees() {
        error = null
        degrees = !degrees
    }

    fun evaluateNow() {
        when (val result = CalcEngine.evaluate(expression, degrees)) {
            is CalcResult.Ok -> {
                val text = CalcEngine.format(result.value)
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
        insert(CalcEngine.format(value))
    }

    /** 即時預覽（打的途中）。錯誤不回傳——那只是「還沒打完」，不是錯誤。 */
    fun preview(): CalcResult =
        if (expression.isBlank()) CalcResult.Err("空白")
        else CalcEngine.evaluate(expression, degrees)
}

/**
 * 工程用計算機面板：工具列按鈕打開的浮動對話框。
 *
 * 走共用三件套（`AnimatedDialog` ＋ `GlassDialogCustom` ＋ `glassClickable`），
 * 不自畫背景、不用 M3 `Button`——按鍵的按壓回饋（掃光＋縮放＋觸感）由共用件負責。
 *
 * 鍵盤配置是「上排函數、下排數字」的實體計算機慣例，不是照抄手機計算機：
 * 這台要能在平板上用大拇指單手算，所以按鍵至少 42dp 高、五欄滿寬。
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CalcDisplay(session, isDark)
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

@Composable
private fun CalcDisplay(session: CalcSession, isDark: Boolean) {
    val preview = session.preview()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fauxGlassPanel(isDark, ShapeMd)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AngleChip(degrees = session.degrees, isDark = isDark, onClick = session::toggleDegrees)
            Spacer(Modifier.weight(1f))
            val last = session.history.firstOrNull()
            if (last != null) {
                Text(
                    text = "上次 ${last.result}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Text(
            text = session.expression.ifBlank { "0" },
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        // 第二行只顯示兩種東西：按＝之後的錯誤，或「目前打得出來」的預覽值。
        val previewText = when {
            session.error != null -> session.error.orEmpty()
            session.justEvaluated -> ""
            preview is CalcResult.Ok -> "= ${CalcEngine.format(preview.value)}"
            else -> ""
        }
        if (previewText.isNotEmpty()) {
            Text(
                text = previewText,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium,
                color = if (session.error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** DEG／RAD 切換：小膠囊，壓一次換一種，狀態永遠看得見。 */
@Composable
private fun AngleChip(degrees: Boolean, isDark: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(26.dp)
            .fauxGlassPanel(isDark, ShapeMd)
            .glassClickable(onClick = onClick, shape = ShapeMd)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (degrees) "DEG" else "RAD",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun CalcKeypad(session: CalcSession, isDark: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CalcRow {
            CalcKey("sin", isDark, Modifier.weight(1f)) { session.insert("sin(") }
            CalcKey("cos", isDark, Modifier.weight(1f)) { session.insert("cos(") }
            CalcKey("tan", isDark, Modifier.weight(1f)) { session.insert("tan(") }
            CalcKey("ln", isDark, Modifier.weight(1f)) { session.insert("ln(") }
            CalcKey("log", isDark, Modifier.weight(1f)) { session.insert("log(") }
        }
        CalcRow {
            CalcKey("√", isDark, Modifier.weight(1f)) { session.insert("√(") }
            CalcKey("x²", isDark, Modifier.weight(1f)) { session.insert("^2") }
            CalcKey("xʸ", isDark, Modifier.weight(1f)) { session.insert("^") }
            CalcKey("x!", isDark, Modifier.weight(1f)) { session.insert("!") }
            CalcKey("1/x", isDark, Modifier.weight(1f)) { session.insert("^-1") }
        }
        CalcRow {
            CalcKey("π", isDark, Modifier.weight(1f)) { session.insert("π") }
            CalcKey("e", isDark, Modifier.weight(1f)) { session.insert("e") }
            CalcKey("ANS", isDark, Modifier.weight(1f)) { session.insertAnswer() }
            CalcKey("(", isDark, Modifier.weight(1f)) { session.insert("(") }
            CalcKey(")", isDark, Modifier.weight(1f)) { session.insert(")") }
        }
        CalcRow {
            CalcKey("7", isDark, Modifier.weight(1f)) { session.insert("7") }
            CalcKey("8", isDark, Modifier.weight(1f)) { session.insert("8") }
            CalcKey("9", isDark, Modifier.weight(1f)) { session.insert("9") }
            CalcKey("÷", isDark, Modifier.weight(1f)) { session.insert("/") }
            CalcKey("C", isDark, Modifier.weight(1f), accent = true) { session.clear() }
        }
        CalcRow {
            CalcKey("4", isDark, Modifier.weight(1f)) { session.insert("4") }
            CalcKey("5", isDark, Modifier.weight(1f)) { session.insert("5") }
            CalcKey("6", isDark, Modifier.weight(1f)) { session.insert("6") }
            CalcKey("×", isDark, Modifier.weight(1f)) { session.insert("*") }
            CalcKey("⌫", isDark, Modifier.weight(1f)) { session.backspace() }
        }
        CalcRow {
            CalcKey("1", isDark, Modifier.weight(1f)) { session.insert("1") }
            CalcKey("2", isDark, Modifier.weight(1f)) { session.insert("2") }
            CalcKey("3", isDark, Modifier.weight(1f)) { session.insert("3") }
            CalcKey("−", isDark, Modifier.weight(1f)) { session.insert("-") }
            CalcKey("±", isDark, Modifier.weight(1f)) { session.toggleSign() }
        }
        CalcRow {
            CalcKey("0", isDark, Modifier.weight(1f)) { session.insert("0") }
            CalcKey(".", isDark, Modifier.weight(1f)) { session.insert(".") }
            CalcKey("+", isDark, Modifier.weight(1f)) { session.insert("+") }
            CalcKey("=", isDark, Modifier.weight(2f), accent = true) { session.evaluateNow() }
        }
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

/**
 * 一顆計算機按鍵。
 *
 * `accent = true` 是「＝」與「C」這種有份量的鍵：亮藥丸＋深墨字
 * （`GlassSelectionInk`，跟工具列選中態同一套色票），在玻璃卡上仍然清楚。
 */
@Composable
private fun CalcKey(
    label: String,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .then(
                if (accent) Modifier.glassSelectionPill(ShapeMd)
                else Modifier.fauxGlassPanel(isDark, ShapeMd)
            )
            .glassClickable(onClick = onClick, shape = ShapeMd),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (accent) GlassSelectionInk else MaterialTheme.colorScheme.onSurface
        )
    }
}
