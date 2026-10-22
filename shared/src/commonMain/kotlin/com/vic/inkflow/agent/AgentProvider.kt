package com.vic.inkflow.agent

/**
 * The provider seam: everything the agent core needs from a language model,
 * and nothing else.
 *
 * The shape of this interface is the reason the agent core can live in
 * `:shared`'s `commonMain` (and therefore run on Android, Windows and iOS
 * from one implementation) while the model itself runs somewhere else: the
 * desktop talks to a local llama.cpp or LM Studio server, the tablet talks to
 * a browser, and iOS will talk to whatever it talks to. The core only ever
 * sees requests and responses.
 *
 * It follows the same rule the rest of `:shared` already follows — the
 * interface lives in the shared module, the implementation lives in the
 * platform module (see `data/store/DocumentSource.kt` and `render/PdfEngine.kt`).
 */
interface AgentProvider {

    /**
     * Sends one request and returns the model's answer.
     *
     * Implementations must not throw for an ordinary failure (the server is
     * not running, the model refused the schema): those are reported as
     * [LlmResponse.error] so the loop can decide what to do. Throwing is
     * reserved for programming errors.
     */
    suspend fun complete(request: LlmRequest): LlmResponse
}

/**
 * One request to a model.
 *
 * @property systemPrompt instructions that always apply.
 * @property userPrompt the task for this turn.
 * @property images optional images (a rendered PDF page, a cropped region).
 * @property responseSchema when set, the provider is asked to constrain the
 *   answer to this JSON Schema. The agent loop always sets it — that is how a
 *   small local model is made to answer in a shape the app can check.
 * @property temperature provider default when `null`.
 * @property maxTokens provider default when `null`.
 */
data class LlmRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val images: List<LlmImage> = emptyList(),
    val responseSchema: LlmSchema? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null
)

/**
 * A JSON Schema the answer is constrained to.
 *
 * @property name the schema's name, used by the engines that want one.
 * @property document the JSON Schema document itself, as text.
 */
data class LlmSchema(val name: String, val document: String)

/**
 * An image handed to the model.
 *
 * Deliberately not a `data class`: the bytes are large and comparing them
 * field by field would be a waste.
 */
class LlmImage(val bytes: ByteArray, val mimeType: String = "image/png")

/** The model's answer. */
data class LlmResponse(
    /** The answer text. Empty when [error] is set. */
    val text: String,
    val usage: LlmUsage? = null,
    val error: LlmError? = null
)

/** Token counts, when the provider reports them. */
data class LlmUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null
)

/** Why a request failed. */
data class LlmError(val kind: LlmErrorKind, val message: String)

/**
 * The failures the loop knows how to talk about.
 *
 * [NotRunning] is the one users will actually hit: the desktop app talks to a
 * model server the user starts themselves, so the message has to say what to
 * do, not just that something failed.
 */
enum class LlmErrorKind {
    /** The model server is not reachable (not started, wrong port). */
    NotRunning,

    /** The model was reached but cannot do what was asked (no vision, no schema support). */
    ModelUnsupported,

    /** Too many requests; retrying later is reasonable. */
    RateLimited,

    /** The connection broke mid-request. */
    Network,

    /** The answer arrived but was not the shape we asked for, even after a retry. */
    MalformedResponse,

    /** Anything else. */
    Unknown
}
