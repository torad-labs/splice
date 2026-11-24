// NEW: server mode (docs/specs/server-mode.md, the readiness probe). The chat-completions dialect's readiness question
// for a pooled server, asked when `[server.probe] kind = "completion"`: one non-streamed completion of one token for
// the configured model, and what of the answer proves the model behind the server can complete.
//
// VALID IS THE ENVELOPE, NOT THE TEXT. One output token can vanish inside a reasoning model's thinking (the local
// runtime probe measured a thinking model spend a 32-token budget inside `<think>`), so an answer whose `content` is
// empty or absent is still a completion. A 2xx whose body is a JSON object with a `choices` array is the server
// completing; anything else is not.
//
// THE BODY IS BUILT HERE, NOT BY THE TURN PATH. The turn assembler always streams and adds stream-only fields, and a
// pool is made before any head's provider exists. This body is four fixed fields with no reasoning control, so it
// varies no prompt-cache key and holds nothing of a turn's to drift from. It takes the provider's token-cap field name
// (ChatQuirks.maxTokensField), the one quirk that changes it.
package splice.dialect.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.wire.HttpStatus
import splice.upstream.readiness.ReadinessProbe
import splice.upstream.readiness.ReadinessReading
import splice.upstream.readiness.ReadinessRequest

public class ChatReadinessProbe(
    /** The field the provider reads the token cap from ([ChatQuirks.maxTokensField]). */
    private val maxTokensField: String,
) : ReadinessProbe {
    override fun request(base: String, model: String): ReadinessRequest {
        val body = buildJsonObject {
            put("model", model)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", PROBE_PROMPT)
                        },
                    )
                },
            )
            put(maxTokensField, JsonPrimitive(1))
            put("stream", false)
        }
        return ReadinessRequest(base.trimEnd('/') + COMPLETIONS_PATH, JsonWire.string(body))
    }

    /** Ready on a 2xx that is a completion's envelope; the reading names the status and the shape, never the body. */
    override fun read(status: Int, body: String): ReadinessReading {
        if (status !in HttpStatus.OK..HttpStatus.MAX_SUCCESS) return ReadinessReading(false, "HTTP $status")
        val answer = JsonScalars.objectOrNull(Json, body)
        return when {
            answer == null -> ReadinessReading(false, "HTTP $status, not a JSON object")
            answer[CHOICES] !is JsonArray -> ReadinessReading(false, "HTTP $status, no choices")
            else -> ReadinessReading(true, "HTTP $status, a completion")
        }
    }
}

// Where an OpenAI chat-completions server takes a completion, under its API root.
private const val COMPLETIONS_PATH = "/chat/completions"

// The words asked. Short and plain, so any model answers with something and a tokenizer spends little on it.
private const val PROBE_PROMPT = "ping"

// The array every chat completion answers its choices in.
private const val CHOICES = "choices"
