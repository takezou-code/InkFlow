package com.vic.inkflow.agent

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The local provider, tested against a stub server.
 *
 * The point of these tests is that the desktop's AI can be developed and
 * verified **without a model running**: the stub speaks the same OpenAI
 * protocol the local engines speak, so the request shape, the schema
 * envelope, the error mapping and the model list can all be pinned here.
 * Only the last mile — a real llama.cpp answering — needs the real thing.
 */
class LocalOpenAiProviderTest {

    private val servers = mutableListOf<HttpServer>()

    @AfterTest
    fun cleanup() {
        servers.forEach { it.stop(0) }
    }

    /** A stand-in for llama.cpp / LM Studio. */
    private inner class Stub(
        val status: Int,
        val response: String,
        val modelsResponse: String = """{"data":[{"id":"local-model","architecture":{"input_modalities":["text","image"]}}]}"""
    ) {
        val requests = mutableListOf<String>()
        lateinit var server: HttpServer

        fun start(): Stub {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v1/chat/completions") { exchange ->
                requests += exchange.requestBody.readBytes().decodeToString()
                respond(exchange, status, response)
            }
            server.createContext("/v1/models") { exchange ->
                respond(exchange, 200, modelsResponse)
            }
            server.start()
            servers.add(server)
            return this
        }

        private fun respond(exchange: HttpExchange, code: Int, body: String) {
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        fun baseUrl(): String = "http://127.0.0.1:${server.address.port}"
    }

    private fun provider(baseUrl: String, model: String = "local-model") = LocalOpenAiProvider(
        LocalModelConfig(baseUrl = baseUrl, model = model, timeoutSeconds = 10)
    )

    private val request = LlmRequest(
        systemPrompt = "system",
        userPrompt = "user",
        responseSchema = LlmSchema("agent_step", """{"type":"object"}""")
    )

    @Test
    fun aSuccessfulAnswerIsReturnedWithItsUsage() = runBlocking {
        val stub = Stub(
            status = 200,
            response = """
                {"choices":[{"message":{"content":"{\"ok\":true}"}}],
                 "usage":{"prompt_tokens":11,"completion_tokens":22}}
            """.trimIndent()
        ).start()

        val response = provider(stub.baseUrl()).complete(request)

        assertNull(response.error)
        assertEquals("{\"ok\":true}", response.text)
        assertEquals(11, response.usage?.promptTokens)
        assertEquals(22, response.usage?.completionTokens)
    }

    @Test
    fun theRequestCarriesTheModelTheSchemaAndTheMessages() = runBlocking {
        val stub = Stub(
            status = 200,
            response = """{"choices":[{"message":{"content":"{}"}}]}"""
        ).start()

        provider(stub.baseUrl()).complete(request)

        val body = stub.requests.single()
        assertTrue(body.contains("\"model\":\"local-model\""), body)
        assertTrue(body.contains("\"role\":\"system\""), body)
        assertTrue(body.contains("\"role\":\"user\""), body)
        // The schema has to reach the engine, or the answer is not constrained
        // and the whole "small local model" premise collapses.
        assertTrue(body.contains("\"response_format\""), body)
        assertTrue(body.contains("\"json_schema\""), body)
        assertTrue(body.contains("\"agent_step\""), body)
        assertTrue(body.contains("\"strict\":true"), body)
    }

    @Test
    fun imagesAreSentAsDataUris() = runBlocking {
        val stub = Stub(
            status = 200,
            response = """{"choices":[{"message":{"content":"{}"}}]}"""
        ).start()

        provider(stub.baseUrl()).complete(
            request.copy(images = listOf(LlmImage(byteArrayOf(1, 2, 3), "image/png")))
        )

        val body = stub.requests.single()
        assertTrue(body.contains("\"image_url\""), body)
        assertTrue(body.contains("data:image/png;base64,"), body)
        assertTrue(body.contains("\"type\":\"text\""), body)
    }

    @Test
    fun aServerThatIsNotRunningSaysSo() = runBlocking {
        // Take a port, give it back, then talk to it: the connection is
        // refused, which is what "the user did not start llama-server" looks
        // like from here.
        val stub = Stub(status = 200, response = "{}").start()
        val baseUrl = stub.baseUrl()
        stub.server.stop(0)

        val response = provider(baseUrl).complete(request)

        val error = response.error
        assertNotNull(error)
        assertEquals(LlmErrorKind.NotRunning, error.kind)
        assertTrue(error.message.contains("llama-server"), error.message)
    }

    @Test
    fun aBusyServerIsReportedAsBusy() = runBlocking {
        val stub = Stub(status = 429, response = """{"error":"busy"}""").start()
        val response = provider(stub.baseUrl()).complete(request)
        assertEquals(LlmErrorKind.RateLimited, response.error?.kind)
    }

    @Test
    fun aServerFailureIsReportedAsSuch() = runBlocking {
        val stub = Stub(status = 503, response = """{"error":"boom"}""").start()
        val response = provider(stub.baseUrl()).complete(request)
        assertEquals(LlmErrorKind.Network, response.error?.kind)
    }

    @Test
    fun anAnswerWithoutContentIsReportedAsMalformed() = runBlocking {
        val stub = Stub(status = 200, response = """{"choices":[]}""").start()
        val response = provider(stub.baseUrl()).complete(request)
        assertEquals(LlmErrorKind.MalformedResponse, response.error?.kind)
    }

    @Test
    fun aNonJsonAnswerIsReportedAsMalformed() = runBlocking {
        val stub = Stub(status = 200, response = """not json at all""").start()
        val response = provider(stub.baseUrl()).complete(request)
        assertEquals(LlmErrorKind.MalformedResponse, response.error?.kind)
    }

    @Test
    fun theModelListIsRead() = runBlocking {
        val stub = Stub(status = 200, response = "{}").start()
        val models = provider(stub.baseUrl()).listModels()
        assertEquals(1, models.size)
        assertEquals("local-model", models.first().id)
        assertTrue(models.first().supportsVision)
    }

    @Test
    fun aServerThatIsNotRunningHasNoModelList() = runBlocking {
        val stub = Stub(status = 200, response = "{}").start()
        val baseUrl = stub.baseUrl()
        stub.server.stop(0)
        assertEquals(emptyList(), provider(baseUrl).listModels())
    }
}
