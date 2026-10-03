package com.vic.inkflow.ui

import androidx.compose.ui.geometry.Offset
import com.vic.inkflow.util.StrokePoint

// â”€â”€ é€£è²«ç•«å¸ƒ helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// å…¨æ–‡ä»¶ model ç©ºé–“çµ±ä¸€ï¼ˆè¦‹ EditorViewModel.MODEL_W/Hï¼‰ï¼Œå„é åŒå°ºå¯¸ï¼Œ
// è·¨é  = åŒä¸€åº§æ¨™ç³»å¾€ä¸Šä¸‹é å¹³ç§» modelHeightï¼ˆç­†ï¼‰æˆ–æ•´é¡†æ›é ï¼ˆåœ–/å­—/å¥—ç´¢ï¼‰ã€‚

/** é‚Šç·£è‡ªå‹•æ²ï¼ˆæ‹–æ›³å°ˆç”¨æ¥µæ…¢é€Ÿï¼‰ï¼šæ‰‹æŒ‡æ‹–å‡ºç´™ä¸Šä¸‹ç•Œæ‰æ²ï¼Œæ­»å€å…§ä¸å‹•ï¼Œæ¯”ä¾‹æ¥µå°å¥½å¾®æŽ§ã€‚ */
fun edgeAutoScrollDy(y: Float, canvasH: Float): Float {
    if (canvasH <= 0f) return 0f
    val overshoot = when {
        y > canvasH -> y - canvasH
        y < 0f -> y // è² å€¼=å¾€ä¸Šæ²
        else -> return 0f
    }
    // æ­»å€ï¼šç´™ç•Œå¤– 8px å…§ä¸æ²ï¼Œæ‰‹æŒ‡æ­é‚Šä¸é£„ç§»
    val dead = 8f
    val eff = when {
        overshoot > dead -> overshoot - dead
        overshoot < -dead -> overshoot + dead
        else -> return 0f
    }
    return (eff * 0.05f).coerceIn(-8f, 8f)
}

/**
 * model-space Y è¶Šç•Œæ›é ï¼šé€é ç¹žå›žï¼Œå›žå‚³ (ç›®æ¨™é , ç¹žå›žå¾ŒY)ã€‚æœªè¶Šç•Œå›žå‚³ nullã€‚
 * é¦–/æœ«é æ’žç‰†ï¼ˆç„¡è™•å¯åŽ»ï¼‰ä¹Ÿå›žå‚³ nullï¼Œå‘¼å«æ–¹èµ°èˆŠå–®é æäº¤ã€‚
 */
fun wrapCrossPageY(
    modelY: Float,
    modelH: Float,
    srcPage: Int,
    pageCount: Int
): Pair<Int, Float>? {
    if (modelH <= 0f || pageCount <= 0) return null
    var tp = srcPage
    var y = modelY
    while (y < 0f && tp > 0) { y += modelH; tp-- }
    while (y > modelH && tp < pageCount - 1) { y -= modelH; tp++ }
    if (tp == srcPage) return null
    return tp to y
}

/**
 * é€£è²«å¯«ç­†åˆ†æ®µï¼šcanvas-space æ•´ç­†æŒ‰ã€Œç´™é«˜+é é–“éš™ã€æ­¥é•·åˆ‡åˆ†åˆ°å„é ã€‚
 * ç®—æ³•å”¯ä¸€çœŸç›¸åœ¨ [DocLayout.splitByPage]ï¼ˆæ‰‹å‹¢/å¢¨æ°´å…±ç”¨ï¼Œå–®æ¸¬è¦†è“‹ï¼‰ï¼›é€™è£¡åªåšé»žåž‹åˆ¥è½‰æŽ¥ã€‚
 * å›žå‚³ (page, è©²é  canvas åº§æ¨™é»žåˆ—)ï¼›é¦–/æœ«é å¤–æº¢å‡ºä½µå…¥é‚Šç•Œé ä¸¦é‰—åˆ¶åˆ°çº¸é‚Šã€‚
 */
fun splitStrokeByPage(
    pts: List<StrokePoint>,
    canvasH: Float,
    gapPx: Float,
    srcPage: Int,
    pageCount: Int
): List<Pair<Int, List<StrokePoint>>> =
    com.vic.inkflow.util.DocLayout.splitByPage(
        items = pts,
        yOf = { it.y },
        canvasH = canvasH,
        gapPx = gapPx,
        srcPage = srcPage,
        pageCount = pageCount,
        local = { p, ly -> p.copy(y = ly) }
    )
