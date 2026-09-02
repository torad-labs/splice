// NEW: field bag split out of SseEmitter.kt (concentration campaign, HD-24) — a pure data carrier
// with no relationship to frame internals; its production consumer is CollectingTerminal.kt.
package splice.gateway.wire

import kotlinx.serialization.json.JsonObject

/** The fields of the non-stream terminal message envelope, grouped so its builder
 *  ([SseEmitter.TerminalEnvelope.terminalMessageJson]) keeps a single cohesive argument (L3 wire mirror). */
public data class TerminalMessage(
    val id: String,
    val model: String,
    val content: List<JsonObject>,
    val hasToolUse: Boolean,
    val incomplete: Boolean,
    val usagePayload: JsonObject,
)
