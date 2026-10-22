package com.vic.inkflow.probe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A0.1 pen/touch probe — TEMPORARY, delete after the measurement.
 *
 * Why this exists
 * ---------------
 * The plan to share the tablet's canvas with the Windows desktop hinges on one
 * fact we do not actually know: what does a stylus look like to Compose
 * Desktop on Windows?
 *
 * The published answer is discouraging: AWT ignores `WM_POINTER`, so Windows
 * turns pen and touch input into plain mouse input, and Tao reports at best a
 * normalised `pressure` on a `PointerType.Touch` contact — no `PointerType.Stylus`,
 * no tilt, and `pressure = 1f` may mean "the digitiser reported no pressure"
 * rather than "full pressure". If that is what this machine does, palm
 * rejection and pressure-driven stroke width are not available on the desktop,
 * and the shared canvas has to take a different input path (the
 * `compose-stylus` `PenEvent` model) rather than the Compose pointer model.
 *
 * That is a guess. This probe turns it into a measurement.
 *
 * What it measures
 * ----------------
 * For every pointer event, in both the `Initial` and `Main` passes:
 *  - the pointer type (as its name — `Stylus`/`Eraser` are not referenced as
 *    constants so this compiles regardless of which constants the current
 *    Compose version exposes),
 *  - `pressure`, the primary button, the position,
 *  - how many historical samples the event carried (Android delivers those for
 *    a stylus; desktop usually cannot),
 *  - how many pointers were in the event (multi-touch detection).
 *
 * How to use it
 * -------------
 *   run-desktop.cmd --probe=pen          (rebuilds if the sources changed)
 *
 * Then, on a machine with a stylus: write a few strokes, rest a palm on the
 * paper while writing, and alternate between finger and pen. The screen shows
 * the running summary; the raw log lands in a JSONL file whose path is shown
 * at the top of the window.
 *
 * The file is the artefact: it is what `docs/specs/pitfalls.md` will quote.
 */
class PenProbeRecorder {

    companion object {
        private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        fun defaultLogFile(): File =
            File(
                System.getProperty("java.io.tmpdir") ?: ".",
                "inkflow-pen-probe-${LocalDateTime.now().format(stamp)}.jsonl"
            )
    }

    val logFile: File = defaultLogFile()

    private val lock = Any()

    /** Pointer type name -> number of events seen. */
    private val counts = LinkedHashMap<String, Int>()

    /** Pointer type name -> highest pressure seen. */
    private val maxPressure = LinkedHashMap<String, Float>()

    /** The most recent event lines, newest last. */
    private val recent = ArrayDeque<String>()

    private var historicalSeen = false
    private var multiPointerSeen = false
    private var eventCount = 0

    private val writer = logFile.bufferedWriter()

    init {
        runCatching { logFile.parentFile?.mkdirs() }
        runCatching {
            writer.write(
                """{"kind":"header","note":"A0.1 pen/touch probe","logFile":"${logFile.absolutePath}"}"""
            )
            writer.newLine()
            writer.flush()
        }
    }

    fun onEvent(pass: PointerEventPass, event: PointerEvent) {
        val lines = ArrayList<String>()
        val types = LinkedHashSet<String>()
        var pressureMax = 0f
        var historical = 0

        event.changes.forEach { change ->
            // `PointerType` is a value class whose `toString()` is the readable
            // name ("Mouse" / "Touch" / "Stylus" / "Eraser"). Referencing the
            // constants directly would tie the probe to whichever ones the
            // current Compose version happens to expose.
            val name = change.type.toString()
            types += name
            pressureMax = maxOf(pressureMax, change.pressure)
            historical += change.historical.size
            lines += describe(change)
        }

        val buttons = event.buttons.toString()

        val joined = lines.joinToString("; ")
        val line = "[$pass] ${event.type.toString()} types=${types.joinToString("+")} " +
            "n=${event.changes.size} pMax=%.3f hist=%d btn=%s".format(
                pressureMax, historical, buttons.ifEmpty { "-" }
            ) + " | $joined"

        synchronized(lock) {
            eventCount++
            for (t in types) counts[t] = (counts[t] ?: 0) + 1
            for (t in types) {
                val prev = maxPressure[t] ?: 0f
                if (pressureMax > prev) maxPressure[t] = pressureMax
            }
            if (historical > 0) historicalSeen = true
            if (event.changes.size > 1) multiPointerSeen = true
            recent.addLast(line)
            while (recent.size > 14) recent.removeFirst()
            runCatching {
                writer.write(
                    """{"kind":"event","pass":"$pass","type":"${event.type.toString()}",""" +
                        """"pointers":${event.changes.size},""pressureMax":$pressureMax,""" +
                        """"historical":$historical,""changes":[$joined]}"""
                )
                writer.newLine()
                writer.flush()
            }
        }
    }

    private fun describe(change: PointerInputChange): String {
        val hist = change.historical.size
        return "${change.type.toString()}@(${change.position.x.toInt()},${change.position.y.toInt()})" +
            " p=${"%.3f".format(change.pressure)}" +
            (if (hist > 0) " +hist$hist" else "")
    }

    /** The report shown on screen. */
    fun summary(): List<String> = synchronized(lock) {
        val countsText = if (counts.isEmpty()) "(none yet)" else counts.entries.joinToString(", ") {
            "${it.key}=${it.value} (max p=${"%.3f".format(maxPressure[it.key] ?: 0f)})"
        }
        listOf(
            "events: $eventCount",
            "pointer types: $countsText",
            "historical samples seen: $historicalSeen",
            "multi-pointer events seen: $multiPointerSeen",
            "",
            "log: ${logFile.absolutePath}",
            "",
        ) + recent
    }

    fun close() {
        runCatching { writer.flush(); writer.close() }
    }
}

/**
 * The probe window content: a black canvas that records every pointer event,
 * with the running report on top.
 */
@Composable
fun PenProbeScreen() {
    val recorder = remember { PenProbeRecorder() }
    var lines by remember { mutableStateOf(recorder.summary()) }

    DisposableEffect(recorder) {
        onDispose { recorder.close() }
    }

    fun report() {
        lines = recorder.summary()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101014))
            // Initial pass: everything, before any child had a chance to consume.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        recorder.onEvent(PointerEventPass.Initial, event)
                        report()
                    }
                }
            }
            // Main pass: what the canvas itself would see.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        recorder.onEvent(PointerEventPass.Main, event)
                        report()
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            lines.forEach { line ->
                BasicText(
                    text = line,
                    style = TextStyle(color = Color(0xFFE8E8EE), fontSize = 13.sp)
                )
            }
        }
    }
}
