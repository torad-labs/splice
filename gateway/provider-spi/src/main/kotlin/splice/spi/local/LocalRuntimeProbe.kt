// NEW: v0.4.0 FEATURES.md §10 — the questions splice asks a user-managed local runtime, and
// nothing else — which runtime it is, which models it lists, how much context each carries, and
// (only when the operator asks, doctor --live) whether one tiny request streams and calls a tool.
// splice never downloads a model or manages the runtime's lifecycle; it validates what the
// operator's row declares against what the runtime reports and refuses a row that claims more.
//
// Endpoints (all user-managed runtimes speak the openai-chat dialect for turns):
//   Ollama    GET /api/version, GET /v1/models, POST /api/show {model} -> model_info.<arch>.context_length
//             (the model card: a CEILING, not the served window), the `num_ctx` parameter when the
//             modelfile sets it, and GET /api/ps -> models[].context_length for a LOADED model (the
//             window the server actually allocated: its own default when num_ctx is unset — measured
//             2026-09-13 on Ollama 0.30.5: qwen3:4b card 262144, served 32768). A loaded window beats
//             num_ctx (the server may cap or override it); until the model is loaded num_ctx stands in
//             and, absent both, only the ceiling can refuse a row.
//   LM Studio GET /api/v0/models -> data[].max_context_length (+ loaded_context_length when loaded)
//   vLLM      GET /v1/models -> data[].max_model_len
//   other     GET /v1/models only; context unknown, so a declared window is trusted but reported as such
package splice.spi.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.spi.LocalHttp

private const val HTTP_OK = 200

// The wording and the schema are what a small model answers with a CALL rather than a think-out-loud
// (measured 2026-09-13 on qwen3:4b: this pair -> tool_calls in ~400 tokens; "call ping once, then say
// pong" with a bare {"type":"object"} schema -> 1024 tokens of reasoning and no call).
private const val LIVE_PROMPT = "You must call the ping tool now."

public data class LocalRuntime(val kind: LocalRuntimeKind, val version: String?)

/** A model the runtime lists; [contextLength] is the window the runtime SERVES (null when it does not
 *  say), [ceiling] the most it could serve (the model card) when that is all it reports. */
public data class LocalModel(
    val id: String,
    val contextLength: Long?,
    val detail: String? = null,
    val ceiling: Long? = null,
)

/** One declared row against the runtime's answer. */
public data class LocalRowVerdict(val id: String, val ok: Boolean, val reason: String)

/** doctor --live: one tiny streamed request with one tool. */
public data class LocalLiveProbe(val streams: Boolean, val toolCalls: Boolean, val detail: String)

public class LocalRuntimeProbe(baseUrl: String, private val http: LocalHttp) {
    private val json = Json { ignoreUnknownKeys = true }
    private val v1 = baseUrl.trimEnd('/')
    private val root = v1.removeSuffix("/v1")

    /** Null when nothing answers at the base URL. Detection reads the BODY, not the status: LM Studio
     *  answers 200 with an error object on every unknown path (measured 2026-09-13, llmster 0.0.24),
     *  so a 200 on /api/version proves Ollama only when it carries a version — and a list on
     *  /api/v0/models proves LM Studio only when its rows carry LM Studio's max_context_length (a
     *  server that answers every ...-/models path with the OpenAI shape is a generic one). */
    public fun detect(): LocalRuntime? {
        val ollama = get("$root/api/version")?.takeIf { it["version"] != null }
        val lmStudio = if (ollama == null) get("$root/api/v0/models")?.takeIf(::lmStudioShaped) else null
        val generic = if (ollama == null && lmStudio == null) get("$v1/models") else null
        return when {
            ollama != null -> LocalRuntime(LocalRuntimeKind.OLLAMA, JsonScalars.str(ollama, "version"))
            lmStudio != null -> LocalRuntime(LocalRuntimeKind.LM_STUDIO, null)
            generic != null -> {
                val vllm = data(generic).any { it["max_model_len"] != null }
                LocalRuntime(if (vllm) LocalRuntimeKind.VLLM else LocalRuntimeKind.OPENAI_COMPATIBLE, null)
            }
            else -> null
        }
    }

    private fun lmStudioShaped(body: JsonObject): Boolean =
        body["data"] != null && data(body).any { it["max_context_length"] != null }

    /** The runtime's model list, or null when the list call itself did not answer — a runtime still
     *  starting is not one that "lists nothing", and callers must not refuse every row for it
     *  (review 2026-09-14). [only] bounds Ollama's per-model /api/show reads to the ids the caller
     *  validates; the other listed ids come back without a window. */
    public fun models(runtime: LocalRuntime, only: Set<String>? = null): List<LocalModel>? = when (runtime.kind) {
        LocalRuntimeKind.LM_STUDIO -> get("$root/api/v0/models")?.let { body ->
            data(body).map { m ->
                val loaded = JsonScalars.long(m, "loaded_context_length")
                val max = JsonScalars.long(m, "max_context_length")
                LocalModel(JsonScalars.strOrEmpty(m["id"]), loaded ?: max, JsonScalars.str(m, "state"))
            }
        }
        LocalRuntimeKind.OLLAMA -> get("$v1/models")?.let { body ->
            val running = ollamaRunning()
            data(body).map { m -> ollamaModel(JsonScalars.strOrEmpty(m["id"]), running, only) }
        }
        else -> get("$v1/models")?.let { body ->
            data(body).map { m -> LocalModel(JsonScalars.strOrEmpty(m["id"]), JsonScalars.long(m, "max_model_len")) }
        }
    }

    /** Refuse a row the runtime does not list, or that declares more context than the runtime reports.
     *  An untagged id names its `:latest` tag (Ollama lists `qwen3:latest` and serves `qwen3`). A
     *  generic OpenAI-compatible server's list is not authoritative (a proxy lists aliases, llama-server
     *  lists a file path, listing may be off), so there an unlisted row is trusted, never refused
     *  (review 2026-09-14: heads that served on 0.3.x refused to boot). */
    public fun validate(
        rows: Map<String, Long>,
        listed: List<LocalModel>,
        kind: LocalRuntimeKind = LocalRuntimeKind.OLLAMA,
    ): List<LocalRowVerdict> = rows.map { (id, window) -> verdict(id, window, listed, kind) }

    private fun verdict(id: String, window: Long, listed: List<LocalModel>, kind: LocalRuntimeKind): LocalRowVerdict {
        val model = listed.firstOrNull { it.id == id } ?: listed.firstOrNull { it.id == "$id:latest" }
        if (model == null) {
            val ids = listed.joinToString { it.id }
            return if (kind == LocalRuntimeKind.OPENAI_COMPATIBLE) {
                LocalRowVerdict(
                    id,
                    true,
                    "not listed by the ${kind.label} server (list not authoritative; listed: $ids), $window trusted",
                )
            } else {
                LocalRowVerdict(id, false, "not listed by the runtime (listed: $ids)")
            }
        }
        val refusal = refusal(window, model)
        return if (refusal != null) {
            LocalRowVerdict(id, false, refusal)
        } else {
            LocalRowVerdict(id, true, acceptance(window, model))
        }
    }

    private fun refusal(window: Long, model: LocalModel): String? {
        val served = model.contextLength
        val ceiling = model.ceiling
        return when {
            served != null && window > served -> "declares context_window $window, runtime serves $served"
            served == null && ceiling != null && window > ceiling ->
                "declares context_window $window, the model allows at most $ceiling"
            else -> null
        }
    }

    private fun acceptance(window: Long, model: LocalModel): String = when {
        model.contextLength != null -> "listed; serves ${model.contextLength} >= declared $window"
        model.ceiling != null ->
            "listed; served window unknown until loaded (at most ${model.ceiling}), $window trusted"
        else -> "listed; the runtime reports no context length, $window trusted"
    }

    /** One tiny streamed request with one tool, only when the operator asked (doctor --live). */
    public fun live(model: String): LocalLiveProbe {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("max_tokens", LIVE_MAX_TOKENS)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", LIVE_PROMPT)
                        },
                    )
                },
            )
            put("tools", buildJsonArray { add(pingTool()) })
        }
        val reply = http("POST", "$v1/chat/completions", body.toString())
            ?: return LocalLiveProbe(false, false, "no answer from $v1/chat/completions")
        return LocalLiveReading().read(reply)
    }

    /** `/api/ps`: the window each LOADED model was given, by name. */
    private fun ollamaRunning(): Map<String, Long> =
        (get("$root/api/ps")?.get("models") as? JsonArray)
            ?.map { it.jsonObject }
            ?.mapNotNull { m ->
                JsonScalars.long(m, "context_length")?.let { JsonScalars.strOrEmpty(m["name"]) to it }
            }
            ?.toMap()
            .orEmpty()

    private fun ollamaModel(id: String, running: Map<String, Long>, only: Set<String>?): LocalModel {
        // Unvalidated ids are listed without a window: no /api/show for models nobody configured.
        if (only != null && id !in only) return LocalModel(id, null)
        val show = http("POST", "$root/api/show", buildJsonObject { put("model", id) }.toString())
            ?.takeIf { it.status == HTTP_OK }
            ?.let { parse(it.body) }
        val info = show?.get("model_info") as? JsonObject
        val architecture = info?.entries?.firstOrNull { it.key.endsWith(".context_length") }
        val max = (architecture?.value as? JsonPrimitive)?.longOrNull
        val numCtx = JsonScalars.str(show, "parameters")
            ?.lineSequence()
            ?.firstOrNull { it.trim().startsWith("num_ctx") }
            ?.substringAfter("num_ctx")?.trim()?.toLongOrNull()
        val served = running[id]
        val detail = listOfNotNull(
            max?.let { "model card context_length $it" },
            numCtx?.let { "num_ctx $it" },
            served?.let { "loaded with context $it" },
        ).joinToString(", ")
        // The loaded window is the one the server allocated; num_ctx is only what the modelfile asks
        // for, and OLLAMA_CONTEXT_LENGTH or a request can override it. Exact beats declared.
        return LocalModel(id, served ?: numCtx, detail.ifEmpty { null }, ceiling = max)
    }

    private fun pingTool(): JsonObject = buildJsonObject {
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", PING_TOOL)
                put("description", "Answers pong.")
                put(
                    "parameters",
                    buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {})
                        put("required", buildJsonArray {})
                    },
                )
            },
        )
    }

    private fun get(url: String): JsonObject? =
        http("GET", url, null)?.takeIf { it.status == HTTP_OK }?.let { parse(it.body) }

    private fun data(obj: JsonObject?): List<JsonObject> =
        (obj?.get("data") as? JsonArray)?.map { it.jsonObject }.orEmpty()

    private fun parse(text: String): JsonObject? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): kind discrimination by shape: a runtime that is not the kind being probed answers with a body that is not a JSON object, which is the normal negative the ?.takeIf guards above consume.
        Cancellables.runCatchingCancellable { json.parseToJsonElement(text).jsonObject }.getOrNull()
}

// Room for a thinking model to reason before it calls the tool: at 32 tokens qwen3:4b spent the
// whole budget inside <think> and the probe reported tool_calls=false for a model that calls tools
// fine through the head (live, 2026-09-13).
private const val LIVE_MAX_TOKENS = 1024
internal const val PING_TOOL = "ping"
