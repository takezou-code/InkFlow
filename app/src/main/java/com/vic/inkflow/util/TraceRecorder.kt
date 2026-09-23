package com.vic.inkflow.util

/**
 * Frame-level gesture trace: answers "where is the hand, where is the paper".
 *
 * Each applied frame records finger centroid+span vs the list position AFTER
 * the write, so offline analysis can compute engagement lag, tracking
 * fidelity (finger px vs content px), jitter, and post-release motion.
 * Pure Kotlin; flushed as logcat lines on gesture end (tag InkFlowTrace).
 */
class TraceRecorder(private val capacity: Int = 3000) {
    private val buf = ArrayDeque<String>()
    var dropped = 0
        private set

    fun frame(
        now: Long,
        fingers: List<Pt>,
        state: String,
        out: String,
        listIndex: Int,
        listOffset: Int,
        panX: Float,
        zoom: Float,
    ) {
        var cx = 0f
        var cy = 0f
        for (p in fingers) {
            cx += p.x
            cy += p.y
        }
        val n = fingers.size
        if (n > 0) {
            cx /= n
            cy /= n
        }
        var span = 0f
        if (n == 2) {
            val dx = fingers[0].x - fingers[1].x
            val dy = fingers[0].y - fingers[1].y
            span = kotlin.math.hypot(dx, dy)
        }
        if (buf.size >= capacity) {
            buf.removeFirst()
            dropped++
        }
        buf.addLast(
            "F t=$now n=$n cx=${cx.toInt()} cy=${cy.toInt()} span=${span.toInt()}" +
                " st=$state o=$out idx=$listIndex off=$listOffset" +
                " px=${panX.toInt()} z=$zoom"
        )
    }

    fun flush(kind: String): List<String> {
        val lines = ArrayList<String>(buf.size + 1)
        lines.add("S kind=$kind nframes=${buf.size} dropped=$dropped")
        lines.addAll(buf)
        buf.clear()
        dropped = 0
        return lines
    }

    fun pending(): Int = buf.size
}
