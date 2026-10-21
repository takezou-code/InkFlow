package com.vic.inkflow.ui.editor

import com.vic.inkflow.ui.DrawCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Undo／redo 的**機械層**：兩個堆、兩個旗標、世代號。
 *
 * 為什麼只搬機械層、不搬「什麼時候該記一格」：
 * 觸控契約（AGENTS 第 5、7 條）規定「手勢一筆只記一格」「結構操作前必須
 * `clearUndoStacks()`」「`dispatchRawDelta` 是唯一寫者」——那些是**決策**，
 * 留在 [com.vic.inkflow.ui.EditorViewModel] 才看得見因果鏈。而「這格進了棧、
 * redo 分支要清掉、redo 完要把命令放回 undo 棧」是**機械**，與業務無關，
 * 放在一起只會讓那個類別變大，不會讓它變清楚。
 *
 * 這個類別刻意**不知道**任何業務規則：
 *  - 不決定什麼時候記一格（呼叫端決定）；
 *  - 不執行命令（命令怎麼套用是 VM 的事，牽涉 repos 與畫面）；
 *  - 不知道 DrawCommand 是什麼以外的東西。
 *
 * 兩個容易寫錯、所以住在這裡的規則：
 *  - **新命令必須清掉 redo 分支**。不清的話，使用者可以 undo 回去、做別的事、
 *    再 redo 進一段從來不存在的歷史（「從未發生的未來」）。
 *  - **世代號**。飛行中的擦除結算會晚幾毫秒才回填 undo；若期間清了棧，那一格
 *    帶著舊 pageIndex 進來就是幽靈資料。世代號就是擋這個。
 */
internal class UndoRedoController {

    companion object {
        /** 歷史上限。超過就裁掉最舊的一格（會話快照膨脹）。 */
        const val MAX_DEPTH = 200
    }

    private val undoStack: ArrayDeque<DrawCommand> = ArrayDeque()
    private val redoStack: ArrayDeque<DrawCommand> = ArrayDeque()

    /**
     * 結構操作（清棧）的世代號。飛行中的擦除結算會晚幾毫秒才回填 undo，
     * 若期間清了棧，那一格帶著舊 pageIndex 進來就是幽靈資料；用世代號擋掉。
     */
    @Volatile
    private var generation = 0

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /** 記一格新命令。 */
    fun push(command: DrawCommand) {
        undoStack.addLast(command)
        // R2：歷史上限 200 格，裁頭（最舊先掉；無上限長會話快照膨脹）。
        while (undoStack.size > MAX_DEPTH) undoStack.removeFirst()
        redoStack.clear()
        _canUndo.value = true
        _canRedo.value = false
    }

    /**
     * R2：頁面增刪移是結構操作、超出復原範圍——呼叫方在動頁前清棧，
     * 否則舊命令的 pageIndex 已錯位，復原會寫出幽靈資料。
     */
    fun clear() {
        generation++
        undoStack.clear()
        redoStack.clear()
        _canUndo.value = false
        _canRedo.value = false
    }

    /** 當下的世代號。 */
    fun currentGeneration(): Int = generation

    /** 這個世代號還算數嗎（清棧前的舊手勢就不算了）。 */
    fun isCurrentGeneration(gen: Int): Boolean = gen == generation

    /** 取出下一個要撤銷的命令；沒有就 null。 */
    fun takeUndo(): DrawCommand? = undoStack.removeLastOrNull()

    /**
     * 撤銷套用完畢：命令進 redo 棧。
     * [redoCommand] 可能是原命令的變體（提取守衛留頁時改記 pageKept 版）。
     */
    fun completeUndo(redoCommand: DrawCommand) {
        redoStack.addLast(redoCommand)
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = true
    }

    /** 取出下一個要重做的命令；沒有就 null。 */
    fun takeRedo(): DrawCommand? = redoStack.removeLastOrNull()

    /** 重做套用完畢：命令放回 undo 棧（redo 回填也要裁頭，與 push 同規）。 */
    fun completeRedo(command: DrawCommand) {
        undoStack.addLast(command)
        while (undoStack.size > MAX_DEPTH) undoStack.removeFirst()
        _canUndo.value = true
        _canRedo.value = redoStack.isNotEmpty()
    }

    /** redo 失敗回滾時用：把「還能不能重做」重新算一次。 */
    fun refreshCanRedo() {
        _canRedo.value = redoStack.isNotEmpty()
    }
}
