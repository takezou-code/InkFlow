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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.imageio.ImageIO

/**
 * B2.0 local-model probe — TEMPORARY, delete after the measurement.
 *
 * Why this exists
 * ---------------
 * Every default in the AI feature (which model to suggest, whether a note can
 * be based on a page image, how hard the agent loop has to retry) rests on two
 * facts about the user's own machine that the code cannot know:
 *
 *  - which models a local llama.cpp / LM Studio server actually offers, and
 *    which of them accept images;
 *  - whether that server really honours `response_format: json_schema` — the
 *    structured-output path the whole agent loop is built on. LM Studio's own
 *    documentation says models under 7B usually do not.
 *
 * That is a guess until measured. This probe measures it.
 *
 * What it does
 * ------------
 *   1. `GET /v1/models` on each candidate address (llama.cpp 8080, LM Studio
 *      1234) and records the model ids plus `architecture.input_modalities`.
 *   2. `POST /v1/chat/completions` with a small `json_schema`, N times
 *      (default 5). A run counts as a pass only when the answer parses as
 *      JSON **and** has the required keys — a server that accepts the parameter
 *      but ignores it is exactly the failure that would otherwise reach the
 *      user as a broken note.
 *   3. One vision request: a small generated PNG with four digits (or a real
 *      page image passed with `--llm-image=<path>`). The answer is compared to
 *      the digits, so "the model said something" does not count as "it can
 *      read the page".
 *
 * How to use it
 * -------------
 *   run-desktop.cmd --probe=llm
 *   run-desktop.cmd --probe=llm --llm-url=http://localhost:1234
 *   run-desktop.cmd --probe=llm --llm-model=qwen2.5-7b-instruct --llm-runs=10
 *   InkFlow.exe --probe=llm            (the packaged runtime is what ships)
 *
 * The screen shows the running result; the same lines land in a JSONL file
 * whose path is printed at the top. The file is the artefact: it is what
 * `docs/specs/pitfalls.md` and the plan's §8 will quote.
 */

/** Where to look, and how hard. */
data class LlmProbeOptions(
    val urls: List<String> = listOf("http://localhost:8080", "http://localhost:1234"),
    /** Force one model id; otherwise the first model the server lists. */
    val model: String? = null,
    /** Use this image instead of the generated digit card. */
    val imagePath: String? = null,
    /** How many structured-output attempts count as the sample. */
    val runs: Int = 5
)

/** Appends one JSON object per line; flushes every line so a crash keeps the evidence. */
private class LlmProbeRecorder {

    companion object {
        private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        fun defaultLogFile(): File = File(
            System.getProperty("java.io.tmpdir") ?: ".",
            "inkflow-llm-probe-${LocalDateTime.now().format(stamp)}.jsonl"
        )
    }

    val logFile: File = defaultLogFile()

    private val gson = Gson()
    private val writer = logFile.bufferedWriter()

    fun record(kind: String, block: JsonObject.() -> Unit = {}) {
        val root = JsonObject()
        root.addProperty("kind", kind)
        root.block()
        runCatching {
            writer.write(gson.toJson(root))
            writer.newLine()
            writer.flush()
        }
    }

    fun close() {
        runCatching { writer.flush(); writer.close() }
    }
}

/** One structured-output attempt. */
private data class ShapeCheck(val ok: Boolean, val why: String, val preview: String)

private class LlmProbe(
    private val options: LlmProbeOptions,
    private val report: (String) -> Unit
) {
    private val recorder = LlmProbeRecorder()
    private val gson = Gson()
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** The line the user copies into `docs/specs/pitfalls.md`. */
    private val summary = ArrayList<String>()

    val logFile: File get() = recorder.logFile

    suspend fun runAll() = withContext(Dispatchers.IO) {
        recorder.record("header") {
            addProperty("probe", "B2.0 local OpenAI-compatible model")
            addProperty("os", System.getProperty("os.name"))
            addProperty("java", System.getProperty("java.version"))
            addProperty("runs", options.runs)
            addProperty("logFile", logFile.absolutePath)
        }
        say("InkFlow LLM probe (B2.0)")
        say("java ${System.getProperty("java.version")} on ${System.getProperty("os.name")}")
        say("log: ${logFile.absolutePath}")
        say("")

        var anyServer = false
        for (url in options.urls) {
            if (measureServer(url.trimEnd('/'))) anyServer = true
        }
        if (!anyServer) {
            say("")
            say("沒有可用的模型服務。先啟動 llama-server 或 LM Studio 的 local server。")
            say("llama.cpp : llama-server -m <model.gguf> --port 8080 --jinja")
            say("LM Studio : Developer 分頁 → Start Server（預設 1234）")
        }
        say("")
        say("DONE — 以上數字即 §8 第 4 項的答案；JSONL 在 log 路徑。")
        summary.forEach { recorder.record("summary") { addProperty("line", it) } }
        recorder.close()
    }

    /** @return true when the server answered `/v1/models`. */
    private fun measureServer(baseUrl: String): Boolean {
        say("== $baseUrl")
        recorder.record("models-request") { addProperty("url", baseUrl) }

        val response = runCatching { get("$baseUrl/v1/models") }.getOrElse {
            say("   連不上：${it.message ?: it.javaClass.simpleName}")
            recorder.record("models-unreachable") {
                addProperty("url", baseUrl)
                addProperty("error", it.message ?: it.javaClass.simpleName)
            }
            return false
        }

        if (response.first !in 200..299) {
            say("   /v1/models 回 HTTP ${response.first}：${response.second.take(200)}")
            recorder.record("models-error") {
                addProperty("url", baseUrl)
                addProperty("status", response.first)
                addProperty("body", response.second.take(600))
            }
            return false
        }

        val models = parseModels(response.second)
        recorder.record("models") {
            addProperty("url", baseUrl)
            addProperty("body", response.second.take(4000))
            add("parsed", models)
        }
        if (models.size() == 0) {
            say("   /v1/models 成功但沒有模型")
            return true
        }
        models.forEach { m ->
            val obj = m.asJsonObject
            say(
                "   model ${obj.get("id").asString}" +
                    if (obj.get("vision").asBoolean) "  [vision]" else ""
            )
        }

        val model = options.model?.takeIf { it.isNotBlank() }
            ?: models.first().asJsonObject.get("id").asString
        val vision = models.any { it.asJsonObject.get("vision").asBoolean }
        say("   使用模型：$model")
        say("")

        val schemaPasses = measureSchema(baseUrl, model)
        summary += "json_schema：$schemaPasses/${options.runs} @ $baseUrl ($model)"
        if (vision) {
            measureVision(baseUrl, model)
        } else {
            say("   vision：伺服器未宣告任何讀圖模型 → v1 只送文字")
            summary += "vision：無（$baseUrl）"
        }
        say("")
        return true
    }

    /** Sends the same tiny schema [options.runs] times; returns how many answers were usable. */
    private fun measureSchema(baseUrl: String, model: String): Int {
        val schema = JsonParser.parseString(
            """
            {"type":"object","properties":{
              "title":{"type":"string"},
              "points":{"type":"array","items":{"type":"string"}},
              "confidence":{"type":"number"}},
             "required":["title","points","confidence"],
             "additionalProperties":false}
            """.trimIndent()
        )
        var passes = 0
        var unsupported = false

        repeat(options.runs) { index ->
            val body = JsonObject()
            body.addProperty("model", model)
            body.addProperty("temperature", 0)
            body.addProperty("max_tokens", 220)
            body.add("messages", messages(
                "You write study notes. Answer only with JSON matching the schema, no prose.",
                "Write one study note about why the sky is blue: a title, two bullet points, and a confidence from 0 to 1."
            ))
            val format = JsonObject()
            format.addProperty("type", "json_schema")
            val inner = JsonObject()
            inner.addProperty("name", "note")
            inner.addProperty("strict", true)
            inner.add("schema", schema)
            format.add("json_schema", inner)
            body.add("response_format", format)

            val started = System.nanoTime()
            val response = runCatching {
                post("$baseUrl/v1/chat/completions", gson.toJson(body))
            }.getOrNull()
            val ms = (System.nanoTime() - started) / 1_000_000

            if (response == null) {
                say("   run ${index + 1}: 連線中斷（${ms}ms）")
                recorder.record("schema-run") {
                    addProperty("index", index)
                    addProperty("ok", false)
                    addProperty("error", "connection failed")
                    addProperty("ms", ms)
                }
                return@repeat
            }

            if (response.first !in 200..299) {
                val looksLikeSchemaRefusal = response.second.contains("response_format", true) ||
                    response.second.contains("json_schema", true) ||
                    response.second.contains("grammar", true)
                if (looksLikeSchemaRefusal) unsupported = true
                say("   run ${index + 1}: HTTP ${response.first}：${response.second.take(200)}")
                recorder.record("schema-run") {
                    addProperty("index", index)
                    addProperty("ok", false)
                    addProperty("status", response.first)
                    addProperty("body", response.second.take(600))
                    addProperty("ms", ms)
                }
                return@repeat
            }

            val content = contentOf(response.second)
            val check = checkNoteShape(content)
            if (check.ok) passes++
            say(
                "   run ${index + 1}: " + (if (check.ok) "PASS" else "FAIL (${check.why})") +
                    "  ${ms}ms  ${check.preview}"
            )
            recorder.record("schema-run") {
                addProperty("index", index)
                addProperty("ok", check.ok)
                addProperty("why", check.why)
                addProperty("ms", ms)
                addProperty("content", content ?: "")
            }
        }

        if (unsupported) {
            say("   → 這個服務不接受 response_format: json_schema（v1 必須降級成純文字）")
        } else if (passes == 0) {
            say("   → schema 從未成功：換模型，或把 agent 的重試／降級門檻調高")
        } else if (passes < options.runs) {
            say("   → schema 不穩（$passes/${options.runs}）：保留重試 1 次 + 降級提示")
        } else {
            say("   → schema 穩定（$passes/${options.runs}）：agent 迴圈可依賴它")
        }
        return passes
    }

    private fun measureVision(baseUrl: String, model: String) {
        val (bytes, expected) = runCatching {
            val path = options.imagePath
            if (path != null) File(path).readBytes() to null
            else digitCard("4237") to "4237"
        }.getOrElse {
            say("   vision：讀不到圖（${it.message}）")
            return
        }

        val body = JsonObject()
        body.addProperty("model", model)
        body.addProperty("temperature", 0)
        body.addProperty("max_tokens", 120)
        val parts = JsonArray()
        val textPart = JsonObject()
        textPart.addProperty("type", "text")
        textPart.addProperty(
            "text",
            "What digits are written in this image? Answer as JSON {\"digits\":\"...\"}."
        )
        parts.add(textPart)
        val imagePart = JsonObject()
        imagePart.addProperty("type", "image_url")
        val imageUrl = JsonObject()
        imageUrl.addProperty(
            "url",
            "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
        )
        imagePart.add("image_url", imageUrl)
        parts.add(imagePart)
        val userMessage = JsonObject()
        userMessage.addProperty("role", "user")
        userMessage.add("content", parts)
        val messages = JsonArray()
        messages.add(userMessage)
        body.add("messages", messages)

        val started = System.nanoTime()
        val response = runCatching {
            post("$baseUrl/v1/chat/completions", gson.toJson(body), timeoutSeconds = 300)
        }.getOrNull()
        val ms = (System.nanoTime() - started) / 1_000_000

        if (response == null || response.first !in 200..299) {
            val detail = response?.let { "HTTP ${it.first}: ${it.second.take(200)}" } ?: "連線中斷"
            say("   vision：失敗（$detail, ${ms}ms）")
            recorder.record("vision") {
                addProperty("ok", false)
                addProperty("detail", detail)
                addProperty("ms", ms)
            }
            summary += "vision：失敗（$baseUrl）"
            return
        }

        val content = contentOf(response.second).orEmpty()
        val answer = runCatching {
            JsonParser.parseString(content).asJsonObject.get("digits")?.asString
        }.getOrNull() ?: content.take(60)
        val ok = expected != null && answer?.contains(expected) == true
        val verdict = when {
            expected == null -> "讀回「${content.take(60).replace("\n", " ")}」（自訂圖，無標準答案）"
            ok -> "讀對 $expected"
            else -> "讀錯（回「${content.take(60).replace("\n", " ")}」，應為 $expected）"
        }
        say("   vision：$verdict  ${ms}ms")
        recorder.record("vision") {
            addProperty("ok", ok)
            addProperty("expected", expected ?: "")
            addProperty("content", content)
            addProperty("ms", ms)
        }
        summary += "vision：$verdict（$baseUrl）"
    }

    private fun parseModels(body: String): JsonArray {
        val out = JsonArray()
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return out
        val data = root.getAsJsonArray("data") ?: return out
        for (element in data) {
            val obj = element.asJsonObject
            val id = obj.get("id")?.asString ?: continue
            val modalities = obj.getAsJsonObject("architecture")?.getAsJsonArray("input_modalities")
            val vision = modalities?.any { it.asString.equals("image", ignoreCase = true) } == true
            out.add(JsonObject().apply {
                addProperty("id", id)
                addProperty("vision", vision)
            })
        }
        return out
    }

    private fun messages(system: String, user: String): JsonArray {
        val array = JsonArray()
        array.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", system)
        })
        array.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", user)
        })
        return array
    }

    private fun contentOf(body: String): String? = runCatching {
        JsonParser.parseString(body)
            .asJsonObject.getAsJsonArray("choices")
            ?.firstOrNull()?.asJsonObject
            ?.getAsJsonObject("message")
            ?.get("content")?.asString
    }.getOrNull()

    /**
     * Pass = parses as JSON **and** carries every required key with the right
     * kind. A server that silently ignores `response_format` returns prose,
     * which fails here — that is the whole point of the check.
     */
    private fun checkNoteShape(content: String?): ShapeCheck {
        if (content.isNullOrBlank()) return ShapeCheck(false, "empty content", "")
        val preview = content.replace("\n", " ").take(140)
        val root = runCatching { JsonParser.parseString(content) }.getOrNull()
            ?: return ShapeCheck(false, "not JSON", preview)
        if (!root.isJsonObject) return ShapeCheck(false, "not an object", preview)
        val obj = root.asJsonObject
        if (obj.get("title")?.takeIf { it.isJsonPrimitive }?.asString.isNullOrBlank()) {
            return ShapeCheck(false, "missing title", preview)
        }
        val points = obj.get("points")
        if (points == null || !points.isJsonArray || points.asJsonArray.size() == 0) {
            return ShapeCheck(false, "missing points[]", preview)
        }
        val confidence = runCatching { obj.get("confidence")?.asDouble }.getOrNull()
            ?: return ShapeCheck(false, "missing numeric confidence", preview)
        if (confidence < 0 || confidence > 1) {
            return ShapeCheck(false, "confidence out of range", preview)
        }
        return ShapeCheck(true, "ok", preview)
    }

    private fun get(url: String): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    private fun post(url: String, body: String, timeoutSeconds: Long = 180): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    /** A white card with the digits in black — no font file, no external asset. */
    private fun digitCard(digits: String): ByteArray {
        val width = 360
        val height = 140
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            )
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, width, height)
            g.color = java.awt.Color.BLACK
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 96)
            val metrics = g.fontMetrics
            val textWidth = metrics.stringWidth(digits)
            g.drawString(digits, (width - textWidth) / 2, height / 2 + metrics.ascent / 2)
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /** Appends to the screen and the summary; both are read by a human. */
    private fun say(line: String) {
        report(line)
    }
}

/**
 * The probe window: a running transcript, written as it happens.
 *
 * Deliberately not the product's glass UI — this screen is thrown away after
 * the measurement, and a probe that looks like a feature is harder to delete.
 */
@Composable
fun LlmProbeScreen(options: LlmProbeOptions) {
    val lines = remember { mutableStateListOf<String>() }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val probe = LlmProbe(options) { line -> lines.add(line) }
        probe.runAll()
        lines.add("")
        lines.add("log: ${probe.logFile.absolutePath}")
        finished = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101014))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            lines.forEach { line ->
                BasicText(
                    text = line,
                    style = TextStyle(
                        color = if (finished) Color(0xFFE8E8EE) else Color(0xFFCFE3FF),
                        fontSize = 13.sp
                    )
                )
            }
        }
    }
}
