package com.vic.inkflow.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.Base64

/**
 * How the desktop talks to a local model.
 *
 * @property baseUrl the OpenAI-compatible endpoint. `http://localhost:8080` for
 *   llama.cpp's `llama-server`, `http://localhost:1234` for LM Studio.
 * @property model the model id as the server reports it.
 * @property apiKey optional; local servers usually ignore it.
 * @property temperature provider default when `null`.
 * @property maxTokens provider default when `null`.
 * @property timeoutSeconds how long a request may take. Local models on a
 *   laptop are slow — a long note can take minutes — so this is generous.
 */
data class LocalModelConfig(
    val baseUrl: String = "http://localhost:8080",
    val model: String = "",
    val apiKey: String = "",
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val timeoutSeconds: Long = 300
)

/** A model the local server offers. */
data class LocalModelInfo(
    val id: String,
    /** True when the model says it accepts images. */
    val supportsVision: Boolean = false
)

/**
 * How many times a transient failure is retried before giving up.
 */
data class RetryPolicy(
    /** Max retries after the first attempt. */
    val maxRetries: Int = 2,
    /** Base delay in milliseconds; each retry doubles it (exponential backoff). */
    val baseDelayMillis: Long = 1000
)

/**
 * The result of a readiness probe against the model server.
 */
sealed class ModelHealth {
    data class Reachable(val latencyMs: Long) : ModelHealth()
    data class Unreachable(val reason: String) : ModelHealth()
}

/**
 * An [AgentProvider] backed by a local, OpenAI-compatible model server.
 *
 * Why a local server
 * ------------------
 * The material this agent reads is a student's notes and a textbook page.
 * Sending that to a cloud service to make a study note is a decision the
 * user should not have to make by accident, and a local server makes the
 * decision for them: llama.cpp and LM Studio both speak the OpenAI protocol,
 * so one driver covers both, no key is needed, and nothing leaves the machine.
 *
 * The wire format is the OpenAI chat-completions API, with the structured
 * output the agent core needs:
 *
 * ```json
 * {"model":"...","messages":[...],
 *  "response_format":{"type":"json_schema",
 *                     "json_schema":{"name":"...","strict":true,"schema":{...}}}}
 * ```
 *
 * Both engines support `response_format` with a JSON Schema, which is what
 * makes a small local model usable here at all.
 *
 * Resilience
 * ----------
 * Transient failures (a dropped connection, a 5xx from the server, a request
 * that times out) are retried with exponential backoff. A server that is not
 * running at all is reported immediately as [LlmErrorKind.NotRunning] so the
 * user gets actionable instructions instead of a silent hang.
 */
class LocalOpenAiProvider(
    private val config: LocalModelConfig,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val retry: RetryPolicy = RetryPolicy()
) : AgentProvider {

    override suspend fun complete(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val body = buildBody(request)
        val httpRequest = buildRequest("/v1/chat/completions", body)

        var lastError: LlmError? = null
        var attempt = 0

        while (true) {
            attempt++
            val response = try {
                httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            } catch (e: ConnectException) {
                // The server is not listening. Retrying once or twice is cheap
                // and helps if the server is still starting up; after that it is
                // definitely NotRunning and the user needs to start it.
                lastError = LlmError(
                    LlmErrorKind.NotRunning,
                    "no model server at ${config.baseUrl}. Start llama-server (llama-server -m <model>) " +
                        "or LM Studio's local server, then check the address in Settings."
                )
                if (attempt <= retry.maxRetries) {
                    delay(retry.baseDelayMillis * 2 * attempt)
                    continue
                }
                return@withContext LlmResponse(text = "", error = lastError)
            } catch (e: HttpTimeoutException) {
                lastError = LlmError(
                    LlmErrorKind.Network,
                    "the model did not answer within ${config.timeoutSeconds}s"
                )
                if (attempt <= retry.maxRetries) {
                    delay(retry.baseDelayMillis * attempt)
                    continue
                }
                return@withContext LlmResponse(text = "", error = lastError)
            } catch (e: IOException) {
                lastError = LlmError(
                    LlmErrorKind.Network,
                    "the model server could not be reached: ${e.message}"
                )
                if (attempt <= retry.maxRetries) {
                    delay(retry.baseDelayMillis * attempt)
                    continue
                }
                return@withContext LlmResponse(text = "", error = lastError)
            }

            when {
                response.statusCode() == 429 -> {
                    lastError = LlmError(
                        LlmErrorKind.RateLimited,
                        "the model server is busy; try again in a moment"
                    )
                    if (attempt <= retry.maxRetries) {
                        delay(retry.baseDelayMillis * 3 * attempt)
                        continue
                    }
                    return@withContext LlmResponse(text = "", error = lastError)
                }

                response.statusCode() in 500..599 -> {
                    lastError = LlmError(
                        LlmErrorKind.Network,
                        "the model server failed (HTTP ${response.statusCode()})"
                    )
                    if (attempt <= retry.maxRetries) {
                        delay(retry.baseDelayMillis * attempt)
                        continue
                    }
                    return@withContext LlmResponse(text = "", error = lastError)
                }

                response.statusCode() !in 200..299 -> {
                    return@withContext LlmResponse(
                        text = "",
                        error = LlmError(
                            LlmErrorKind.Unknown,
                            "the model server refused the request (HTTP ${response.statusCode()}): " +
                                response.body().take(400)
                        )
                    )
                }

                else -> return@withContext parseAnswer(response.body())
            }
        }

        return@withContext LlmResponse(text = "", error = lastError ?: LlmError(LlmErrorKind.Unknown, "unreachable"))
    }

    /**
     * Lists the models the server offers.
     *
     * The settings screen uses this to fill in the model name and to tell the
     * user whether the model they picked can see images at all.
     */
    suspend fun listModels(): List<LocalModelInfo> = withContext(Dispatchers.IO) {
        val request = buildRequest("/v1/models", null)
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: ConnectException) {
            return@withContext emptyList()
        } catch (e: IOException) {
            return@withContext emptyList()
        }
        if (response.statusCode() !in 200..299) return@withContext emptyList()

        try {
            val root = JsonParser.parseString(response.body()).asJsonObject
            val data = root.getAsJsonArray("data") ?: return@withContext emptyList()
            data.mapNotNull { element ->
                val obj = element.asJsonObject
                val id = obj.get("id")?.asString ?: return@mapNotNull null
                val modalities = obj.getAsJsonObject("architecture")?.getAsJsonArray("input_modalities")
                val supportsVision = modalities?.any { it.asString.equals("image", ignoreCase = true) } == true
                LocalModelInfo(id = id, supportsVision = supportsVision)
            }
        } catch (e: JsonSyntaxException) {
            emptyList()
        } catch (e: IllegalStateException) {
            emptyList()
        }
    }

    /**
     * Probes the server with a lightweight `GET /v1/models`. Used before a
     * generation so the user can be told the server is down instead of waiting
     * through a full request/response cycle that will fail.
     */
    suspend fun healthCheck(): ModelHealth = withContext(Dispatchers.IO) {
        val request = buildRequest("/v1/models", null)
        return@withContext try {
            val start = System.nanoTime()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            if (response.statusCode() in 200..299) {
                ModelHealth.Reachable(latencyMs)
            } else {
                ModelHealth.Unreachable("HTTP ${response.statusCode()}")
            }
        } catch (e: Exception) {
            ModelHealth.Unreachable(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun buildRequest(path: String, body: String?): HttpRequest {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl.trimEnd('/') + path))
            .timeout(Duration.ofSeconds(config.timeoutSeconds))
            .header("Content-Type", "application/json")
        if (config.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${config.apiKey}")
        }
        if (body != null) builder.POST(HttpRequest.BodyPublishers.ofString(body))
        else builder.GET()
        return builder.build()
    }

    private fun buildBody(request: LlmRequest): String {
        val root = JsonObject()
        if (config.model.isNotBlank()) root.addProperty("model", config.model)

        val messages = JsonArray()
        messages.add(message("system", request.systemPrompt, emptyList()))
        messages.add(message("user", request.userPrompt, request.images))
        root.add("messages", messages)

        config.temperature?.let { root.addProperty("temperature", it) }
        request.temperature?.let { root.addProperty("temperature", it) }
        config.maxTokens?.let { root.addProperty("max_tokens", it) }
        request.maxTokens?.let { root.addProperty("max_tokens", it) }

        request.responseSchema?.let { schema ->
            val envelope = JsonObject()
            envelope.addProperty("type", "json_schema")
            val inner = JsonObject()
            inner.addProperty("name", schema.name)
            inner.addProperty("strict", true)
            // The schema document is produced by the shared module as text;
            // it is embedded as an object, which is what the engines expect.
            inner.add("schema", parseSchemaDocument(schema.document))
            envelope.add("json_schema", inner)
            root.add("response_format", envelope)
        }

        return root.toString()
    }

    private fun parseSchemaDocument(document: String): com.google.gson.JsonElement = try {
        JsonParser.parseString(document)
    } catch (e: JsonSyntaxException) {
        JsonObject()
    }

    private fun message(role: String, text: String, images: List<LlmImage>): JsonObject {
        val message = JsonObject()
        message.addProperty("role", role)
        if (images.isEmpty()) {
            message.addProperty("content", text)
            return message
        }
        // With images the content becomes a list of parts, which is the only
        // form both engines accept for a vision request.
        val parts = JsonArray()
        val textPart = JsonObject()
        textPart.addProperty("type", "text")
        textPart.addProperty("text", text)
        parts.add(textPart)
        for (image in images) {
            val imagePart = JsonObject()
            imagePart.addProperty("type", "image_url")
            val url = JsonObject()
            url.addProperty("url", "data:${image.mimeType};base64," + Base64.getEncoder().encodeToString(image.bytes))
            imagePart.add("image_url", url)
            parts.add(imagePart)
        }
        message.add("content", parts)
        return message
    }

    private fun parseAnswer(body: String): LlmResponse = try {
        val root = JsonParser.parseString(body).asJsonObject
        val choices = root.getAsJsonArray("choices")
        val first = choices?.firstOrNull()?.asJsonObject
        val content = first?.getAsJsonObject("message")?.get("content")?.asString
        if (content == null) {
            LlmResponse(
                text = "",
                error = LlmError(LlmErrorKind.MalformedResponse, "the model server's answer had no content")
            )
        } else {
            val usage = root.getAsJsonObject("usage")
            LlmResponse(
                text = content,
                usage = usage?.let {
                    LlmUsage(
                        promptTokens = it.get("prompt_tokens")?.asInt,
                        completionTokens = it.get("completion_tokens")?.asInt
                    )
                }
            )
        }
    } catch (e: JsonSyntaxException) {
        LlmResponse(
            text = "",
            error = LlmError(LlmErrorKind.MalformedResponse, "the model server's answer was not JSON")
        )
    } catch (e: IllegalStateException) {
        LlmResponse(
            text = "",
            error = LlmError(LlmErrorKind.MalformedResponse, "the model server's answer was not JSON")
        )
    }

    companion object {
        fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }
}
