package com.vic.inkflow.ui

/** 墨水工具（由 EditorViewModel 拆出；同包純搬移，行為零變化）。 */
enum class Tool {
    PEN,
    HIGHLIGHTER,
    ERASER,
    LASSO,
    SHAPE,
    TEXT,
    IMAGE
}

/** 圖形子類型。 */
enum class ShapeSubType { RECT, CIRCLE, LINE, ARROW }

/** 套索子類型。 */
enum class LassoSubType { FREEFORM, RECT }

/** 輸入模式：全開放 / 手掌過濾 / 僅觸控筆。 */
enum class InputMode {
    FREE,             // 全開放，所有觸控都可畫
    PALM_REJECTION,   // 演算法過濾手掌，保留細筆跡
    STYLUS_ONLY       // 僅硬體觸控筆（PointerType.Stylus）可畫
}
