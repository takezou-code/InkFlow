package com.vic.inkflow.ui

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot

// ── InkCanvas 輸入啟發式：快滑擦除 + 手掌觸控日誌 ──────────────────────────
// 純函數，與 Composable 狀態無關。閾值集中於此，方便調參。

internal const val ENABLE_PALM_DEBUG_LOGS = false
internal val QUICK_SWIPE_MIN_TRAVEL_DISTANCE = 96.dp
internal const val QUICK_SWIPE_MAX_DURATION_MS = 320L
internal const val QUICK_SWIPE_MIN_PATH_VELOCITY_DP_PER_MS = 0.42f
internal val QUICK_SWIPE_DIRECTION_DELTA = 10.dp
internal const val QUICK_SWIPE_MIN_DIRECTION_REVERSALS = 1
internal const val ERASER_LIVE_WINDOW_POINTS = 20
internal const val ERASER_LIVE_DISPATCH_INTERVAL_MS = 24L

internal fun palmDebugLog(message: String) {
    if (ENABLE_PALM_DEBUG_LOGS) {
        Log.d("PALM_LOG", message)
    }
}

internal fun countDirectionReversals(points: List<Offset>, minStepPx: Float): Int {
    if (points.size < 3) return 0

    var minX = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    points.forEach { p ->
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
    }

    val useX = (maxX - minX) >= (maxY - minY)
    var previousSign = 0
    var reversals = 0
    for (i in 1 until points.size) {
        val delta = if (useX) points[i].x - points[i - 1].x else points[i].y - points[i - 1].y
        if (abs(delta) < minStepPx) continue
        val sign = if (delta > 0f) 1 else -1
        if (previousSign != 0 && sign != previousSign) {
            reversals++
        }
        previousSign = sign
    }
    return reversals
}

internal fun shouldTriggerQuickSwipeEraser(
    points: List<Offset>,
    elapsedMs: Long,
    density: Density
): Boolean {
    if (points.size < 4) return false
    if (elapsedMs <= 0L || elapsedMs > QUICK_SWIPE_MAX_DURATION_MS) return false

    var pathLengthPx = 0f
    for (i in 1 until points.size) {
        pathLengthPx += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
    }

    val minTravelPx = with(density) { QUICK_SWIPE_MIN_TRAVEL_DISTANCE.toPx() }
    if (pathLengthPx < minTravelPx) return false

    val minVelocityPxPerMs = with(density) { QUICK_SWIPE_MIN_PATH_VELOCITY_DP_PER_MS.dp.toPx() }
    val pathVelocityPxPerMs = pathLengthPx / elapsedMs.toFloat()
    if (pathVelocityPxPerMs < minVelocityPxPerMs) return false

    val minDirectionStepPx = with(density) { QUICK_SWIPE_DIRECTION_DELTA.toPx() }
    val directionReversals = countDirectionReversals(points, minDirectionStepPx)
    return directionReversals >= QUICK_SWIPE_MIN_DIRECTION_REVERSALS
}
