// NEW: PATCH /api/config?head=<key> — the per-command half of the config write. The global PATCH changes a
// value for every command; this one sets, changes or clears a command's OWN value for a knob, the
// `[heads.<key>.overrides]` table of splice.toml, which until now the console could only show.
//
// IT IS NOT A SECOND WRITE PATH. The edit goes through the one TopologyWriter that PUT /api/topology and the
// capture switch use: structure-preserving (the operator's comments and ordering stay), backed up first,
// verified by re-parsing, and refused with findings when the daemon would ignore a value at boot (the same
// coercion ConfigService applies to the global layers). A refusal writes nothing.
//
// A WRITE THAT LANDED ACTS NOW WHERE THE KNOB IS LIVE. The daemon's held per-command layer follows the file
// ([ConfigService.headLayer]), so a knob read through the head's config on each request takes the new
// value on the next request. A knob the daemon reads once at start is named in `restart_required`, and the
// response says so instead of claiming it is live. Clearing an override (null) removes the line and the
// command falls back to the global value, which `effective` shows.
//
// A KNOB ONLY THE DAEMON-WIDE VALUE REACHES IS REFUSED (Oct 12, §9 audit): every reader of a daemonWideKnobs entry asks
// the global view, so a command's own value would be written, answered live and never read. Clearing one is allowed.
package splice.configuration.knobs

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.config.daemonWideKnobs
import splice.core.config.knobsByKey
import splice.core.config.restartRequiredKnobKeys
import splice.core.topology.HeadConfig
import splice.core.topology.Topology
import splice.core.topology.TopologyWriteResult
import splice.core.topology.TopologyWriter
import splice.core.topology.TopologyWriterSource
import splice.core.util.Cancellables
import splice.core.util.JsonWire
import splice.core.util.SafeFailureText
import splice.http.JsonReply

private const val NOT_SCALAR = "invalid value (must be a scalar or null)"

// why: the words a per-command write of a daemon-wide knob is refused with, naming where the value is set instead.
private const val DAEMON_WIDE =
    "splice reads this setting once for every command, never per command; set it for every command (PATCH /api/config)"

// why: mirrorReasoning is read per command, but ConfigCoercion pins it false on every layer, so a value is ignored.
private const val PINNED = "pinned false by project rule on every command; ask Marcos to change"

// Each knob a command's own value never reaches, with the words it is refused in.
private val PER_COMMAND_REFUSALS: Map<Knob, String> =
    daemonWideKnobs.associateWith { DAEMON_WIDE } + (Knob.MIRROR_REASONING to PINNED)
private const val HEAD_WRITE_UNWIRED =
    "the topology writer is not wired into this control plane; a command's value cannot be written"

internal class HeadOverrideWrite(
    private val config: ConfigService,
    private val topology: TopologyWriterSource,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** What the request asks of one command: the values to set (null clears) and the keys refused before any write. */
    private data class Ask(val wanted: Map<String, String?>, val rejected: Map<String, String>)

    /** [partial] is knob key to the value to set, or JSON null to clear the command's own value. */
    fun write(head: String, partial: JsonObject): JsonReply {
        val writer = topology() ?: return refuse(HttpStatusCode.ServiceUnavailable, HEAD_WRITE_UNWIRED)
        return writeWith(writer, head, partial)
    }

    private fun writeWith(writer: TopologyWriter, head: String, partial: JsonObject): JsonReply {
        val current = Cancellables.runCatchingCancellable { decode(writer) }.getOrElse { failure ->
            val why = SafeFailureText.render(failure)
            return refuse(HttpStatusCode.InternalServerError, "splice.toml does not parse: $why")
        }
        val held = current.heads[head] ?: return refuse(HttpStatusCode.BadRequest, "unknown head: $head")
        return land(writer, current, head, held, ask(partial, head, LegacyKnobScope(current)))
    }

    private fun ask(partial: JsonObject, head: String, legacy: LegacyKnobScope): Ask {
        val rejected = LinkedHashMap<String, String>()
        val wanted = LinkedHashMap<String, String?>()
        for ((key, value) in partial) {
            val knob = knobsByKey[key]
            val refusal = knob?.let { PER_COMMAND_REFUSALS[it] ?: legacy.refusal(head, it) }
            when {
                knob == null -> rejected[key] = "unknown key"
                refusal != null && value !is JsonNull -> rejected[key] = refusal
                value !is JsonPrimitive -> rejected[key] = NOT_SCALAR
                else -> wanted[key] = value.takeUnless { it is JsonNull }?.content
            }
        }
        return Ask(wanted, rejected)
    }

    private fun land(writer: TopologyWriter, current: Topology, head: String, held: HeadConfig, ask: Ask): JsonReply {
        val overrides = held.overrides.toMutableMap()
        ask.wanted.forEach { (key, value) -> if (value == null) overrides.remove(key) else overrides[key] = value }
        val requested = current.copy(heads = current.heads + (head to held.copy(overrides = overrides)))
        return when (val result = writer.write(requested)) {
            is TopologyWriteResult.Refused -> refused(head, ask.rejected, result)
            is TopologyWriteResult.Written -> {
                config.headLayer.set(head, overrides)
                JsonReply(HttpStatusCode.OK, writtenJson(head, ask))
            }
        }
    }

    private fun writtenJson(head: String, ask: Ask): String {
        val effective = config.getConfig(head).asMap()
        val restart = ask.wanted.keys.filter { it in restartRequiredKnobKeys }
        return JsonWire.string(
            buildJsonObject {
                put("head", head)
                putJsonObject("applied") { ask.wanted.forEach { (key, value) -> put(key, value) } }
                putJsonObject("effective") { ask.wanted.keys.forEach { key -> put(key, effective[key]?.toString()) } }
                putJsonObject("rejected") { ask.rejected.forEach { (key, why) -> put(key, why) } }
                putJsonArray("restart_required") { restart.forEach { add(it) } }
                putJsonArray("live") { ask.wanted.keys.filter { it !in restart }.forEach { add(it) } }
                put("persisted", "splice.toml")
            },
        )
    }

    /** A finding names `heads.<head>.overrides.<key>`; that key is the one rejected, with the daemon's own reason. */
    private fun refused(head: String, rejected: Map<String, String>, result: TopologyWriteResult.Refused): JsonReply {
        val prefix = "heads.$head.overrides."
        val byKey = result.findings.filter { it.path.startsWith(prefix) }
            .associate { it.path.removePrefix(prefix) to it.message }
        val message = result.findings.joinToString("; ") { it.text() }.ifEmpty { "splice.toml write refused" }
        val body = buildJsonObject {
            put("error", message)
            put("head", head)
            putJsonObject("rejected") { (rejected + byKey).forEach { (key, why) -> put(key, why) } }
            putJsonObject("applied") {}
            put("persisted", null as String?)
        }
        return JsonReply(HttpStatusCode.BadRequest, JsonWire.string(body))
    }

    private fun decode(writer: TopologyWriter): Topology =
        json.decodeFromJsonElement(Topology.serializer(), writer.current())

    private fun refuse(status: HttpStatusCode, message: String) =
        JsonReply(status, JsonWire.string(buildJsonObject { put("error", message) }))
}
