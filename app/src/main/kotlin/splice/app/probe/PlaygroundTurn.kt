// NEW: the one-message turn the playground sends, kept apart from the probe that posts it: the body is a fact about
// WHAT is asked (the prompt, one system line for every model), the probe is HOW it is posted (credentials, headers,
// the echo). The probe file is a concentration-band neighbour, so the turn's shape lives here.
package splice.app.probe

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// why: a minimal probe still needs an answer longer than a one-line ack to be useful to read.
private const val PLAYGROUND_MAX_TOKENS = 1024

// why: one system line for every model the page compares, so no head answers under an instruction another did not get.
// It is the line splice already gives a Responses head when a body names none, so GPT's request reads as it did.
private const val PLAYGROUND_SYSTEM = "You are a helpful assistant."

/** The one-message turn Claude Code sends with a prompt: what the head's provider builds the upstream request from. */
internal object PlaygroundTurn {
    /** The turn with [prompt] on [model], as the JSON text Claude Code posts. */
    fun of(model: String, prompt: String): String = buildJsonObject {
        put("model", model)
        put("max_tokens", PLAYGROUND_MAX_TOKENS)
        put("system", PLAYGROUND_SYSTEM)
        put("stream", true)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", prompt)
                    }
                }
            }
        }
    }.toString()
}
