package splice.app.mods.events

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.mods.run.ModEvent
import splice.app.mods.run.ModEventHub
import splice.core.turn.ObservedRequest
import splice.core.turn.ObservedResponse
import splice.core.turn.ObservedStreamFrame
import splice.core.turn.ObservedToolCall
import splice.core.turn.ObservedTurnEnd
import splice.core.turn.TurnObservation
import splice.head.observe.ObservedTurnFacts
import splice.launch.ModsOff

private const val MOD_EVENT_MODEL_FIELD = "model"

internal data class TurnFields(
    val turn: String,
    val session: String?,
    val labels: JsonObject,
    val facts: ObservedTurnFacts,
)

/** The host receives only the contract's scalars, never the request, frame, tool input or response body. */
internal class ModTurn(
    private val hub: ModEventHub,
    private val off: ModsOff?,
    private val fields: TurnFields,
    private val text: ModEventText,
) : TurnObservation {
    private val facts = fields.facts
    private val asked = clean(facts.model)

    override val wantsFrames: Boolean get() = hub.listening("stream.event", off)
    override val wantsRequests: Boolean get() = hub.listening("request.send", off)

    fun started() = emit("turn.start", facts.atEpochMs) {
        put("head", clean(facts.head))
        put(MOD_EVENT_MODEL_FIELD, asked)
        put("compact", facts.compact)
        put("agent", facts.agent?.let(::clean))
    }

    override fun requestSent(facts: ObservedRequest) = emit("request.send", facts.atEpochMs) {
        put(MOD_EVENT_MODEL_FIELD, clean(facts.model))
        put("round", facts.round)
    }

    override fun responseReceived(facts: ObservedResponse) = emit("response.receive", facts.atEpochMs) {
        put(MOD_EVENT_MODEL_FIELD, asked)
        put("status", facts.status)
        put("attempt", facts.attempt)
    }

    override fun frame(facts: ObservedStreamFrame) = emit("stream.event", facts.atEpochMs) {
        put("seq", facts.seq)
        put("type", clean(facts.type))
        put("bytes", facts.bytes)
    }

    override fun toolCall(facts: ObservedToolCall) = emit("tool.call", facts.atEpochMs) {
        val name = clean(facts.name)
        val mcp = name.removePrefix("mcp__")
        val server = mcp.substringBefore("__").takeIf { mcp != name && "__" in mcp }
        put("caller", "model")
        put("id", clean(facts.id))
        put("server", server)
        put("tool", if (server == null) name else mcp.substringAfter("__"))
    }

    override fun completed(facts: ObservedTurnEnd) = emit("turn.complete", facts.atEpochMs) {
        put(MOD_EVENT_MODEL_FIELD, asked)
        put("outcome", clean(facts.outcome))
        put("durationMs", facts.durationMs)
        put(
            "usage",
            buildJsonObject {
                put("input", facts.usage.input)
                put("output", facts.usage.output)
                put("cacheRead", facts.usage.cacheRead)
                put("cacheWrite", facts.usage.cacheCreation)
            },
        )
    }

    private fun clean(value: String): String = text.text(value)

    private inline fun emit(name: String, at: Long, payload: JsonObjectBuilder.() -> Unit) {
        if (!hub.listening(name, off)) return
        hub.offer(
            ModEvent(
                name,
                fields.session,
                buildJsonObject {
                    put("session", fields.session)
                    put("turn", fields.turn)
                    put("at", at)
                    put("labels", fields.labels)
                    payload()
                },
            ),
            off,
        )
    }
}
