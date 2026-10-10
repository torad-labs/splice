// NEW: V4-133/V4-387 — GET/PUT /api/heads/{head}/capture: full body capture per head,
// on by default, local disk only, size capped, keys redacted; feeds the Logs request drawer
// and the transcript view for clients with no local transcript".
//
// THE CAPTURE SWITCH IS THE TRACE KNOB, NEVER A SECOND STORE. V4-174 already built exactly this
// store (splice.head.wire.TraceStore/TurnTrace over ActivityDays, owner-only day files under
// <state>/trace/, headers redacted at write time, size-capped per record) behind
// `Knob.TRACE`/`TRACE_RETENTION_DAYS`/`TRACE_MAX_BODY_CHARS`, opt-out per head via
// `[heads.<key>.overrides] trace = false`. This route is the console's read/write surface onto those three keys
// for ONE head — it neither reads nor writes a trace file itself.
//
// THE SWITCH APPLIES TO THE NEXT REQUEST, NO RESTART (V4-444). Each head's TraceStore always exists and carries a
// TraceSwitch, seeded from the config at boot. A successful write moves that switch, the turn path reads it as each
// request arrives, and so turning capture on saves the very next request and turning it off stops the next one. A turn
// already in flight finishes the way it began. The retention and body-cap keys are still read when the store is built,
// so changing THEM reports `restart_required: true`; `enabled` alone never does.
//
// THE WRITE GOES THROUGH THE SAME TopologyWriter GET/PUT /api/topology ALREADY USES — the ONE seam
// that edits splice.toml (structure-preserving, backed up first, verified by re-parsing). This
// route only computes the three `[heads.<key>.overrides]` keys and hands the whole edited Topology
// to that writer; it holds no file handle of its own.
package splice.head.wire

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.topology.Topology
import splice.core.topology.TopologyWriteResult
import splice.core.topology.TopologyWriter
import splice.core.topology.TopologyWriterSource
import splice.core.util.Cancellables
import splice.core.util.JsonWire
import splice.core.util.SafeFailureText
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.http.JsonReply

internal const val CAPTURE_UNWIRED =
    "the topology writer is not wired into this control plane; /api/heads/{head}/capture cannot write it"

private const val BAD_CAPTURE_BODY =
    "the body must be {\"enabled\": bool, \"retention_days\"?: int, \"max_body_chars\"?: int}"

@Serializable
private data class CaptureWrite(
    val enabled: Boolean,
    @SerialName("retention_days") val retentionDays: Int? = null,
    @SerialName("max_body_chars") val maxBodyChars: Long? = null,
)

public class CaptureRoutes(
    private val heads: TurnsHeadLookup,
    private val config: ConfigService,
    private val topology: TopologyWriterSource,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** GET: the head's EFFECTIVE trace settings, in the layering GET /api/config reports: `trace` from
     *  that head's overrides only (a head-only knob), retention and the body cap from every layer
     *  (env/state/PATCH included) — what the daemon is actually doing right now. */
    public fun read(head: String): JsonReply {
        val found = heads.byName(head).firstOrNull() ?: return unknownHead(head)
        val cfg = config.getConfig(found.key)
        // The switch is what the head is doing right now; the config is only what it booted with.
        val enabled = found.trace?.on ?: cfg.trace
        val body = captureJson(found.key, enabled, cfg.traceKeptDays, cfg.traceMaxBodyChars.toLong(), false)
        return JsonReply(HttpStatusCode.OK, body)
    }

    /** PUT: writes `[heads.<key>.overrides]` on splice.toml through the ONE writer /api/topology
     *  uses. [retentionDays]/[maxBodyChars] left out keep whatever the head already had (env/state
     *  still win at the next boot exactly as they do for any other knob); only `enabled` is
     *  required, because a capture toggle with no state to change makes no sense. */
    public fun write(head: String, body: String): JsonReply {
        val writer = topology() ?: return refuse(HttpStatusCode.ServiceUnavailable, CAPTURE_UNWIRED)
        return writeWith(writer, head, body)
    }

    // Split out of write() (ReturnCount: max 3 per function) — the unwired-writer guard lives in
    // write() and everything past it lives here.
    private fun writeWith(writer: TopologyWriter, head: String, body: String): JsonReply {
        val found = heads.byName(head).firstOrNull() ?: return unknownHead(head)
        // A body that is not JSON and a body of the wrong shape get the same answer, one 400 naming the shape
        // expected, so the failure has nothing more to say.
        val parsed = decoded(body) ?: return refuse(HttpStatusCode.BadRequest, BAD_CAPTURE_BODY)
        return applyWrite(writer, found, parsed)
    }

    private fun decoded(body: String): CaptureWrite? = try {
        json.decodeFromString(CaptureWrite.serializer(), body)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun applyWrite(writer: TopologyWriter, found: TurnsHead, parsed: CaptureWrite): JsonReply {
        val key = found.key
        val current = Cancellables.runCatchingCancellable { decode(writer) }.getOrElse { failure ->
            val why = SafeFailureText.render(failure)
            return refuse(HttpStatusCode.InternalServerError, "splice.toml does not parse: $why")
        }
        val head = current.heads[key] ?: return unknownHead(key)
        val overrides = head.overrides.toMutableMap()
        // Absence now inherits the on-by-default policy; Off must persist an explicit false.
        overrides[Knob.TRACE.key] = parsed.enabled.toString()
        parsed.retentionDays?.let { overrides[Knob.TRACE_RETENTION_DAYS.key] = it.toString() }
        parsed.maxBodyChars?.let { overrides[Knob.TRACE_MAX_BODY_CHARS.key] = it.toString() }
        val requested = current.copy(heads = current.heads + (key to head.copy(overrides = overrides)))
        return when (val result = writer.write(requested)) {
            is TopologyWriteResult.Written -> {
                // The file is saved first, so a refused write moves nothing; then the head's next request follows it.
                found.trace?.set(parsed.enabled)
                JsonReply(HttpStatusCode.OK, writtenJson(key, parsed))
            }
            is TopologyWriteResult.Refused -> refuse(HttpStatusCode.BadRequest, refusalMessage(result))
        }
    }

    private fun writtenJson(key: String, parsed: CaptureWrite): String {
        val effective = config.getConfig(key)
        val retentionDays = parsed.retentionDays ?: effective.traceKeptDays
        val maxBodyChars = parsed.maxBodyChars ?: effective.traceMaxBodyChars.toLong()
        val sizing = parsed.retentionDays != null || parsed.maxBodyChars != null
        return captureJson(key, parsed.enabled, retentionDays, maxBodyChars, sizing)
    }

    private fun refusalMessage(result: TopologyWriteResult.Refused): String =
        result.findings
            .joinToString("; ") { (path, text) -> "$path: $text" }
            .ifEmpty { "splice.toml write refused" }

    private fun decode(writer: TopologyWriter): Topology =
        json.decodeFromJsonElement(Topology.serializer(), writer.current())

    private fun unknownHead(name: String) = refuse(HttpStatusCode.BadRequest, "unknown head: $name")

    private fun captureJson(
        head: String,
        enabled: Boolean,
        retentionDays: Int,
        maxBodyChars: Long,
        restartRequired: Boolean,
    ): String =
        JsonWire.string(
            buildJsonObject {
                put("head", head)
                put("enabled", enabled)
                put("retention_days", retentionDays)
                put("max_body_chars", maxBodyChars)
                // True only when this write changed a size the store reads at construction (the file header).
                put("restart_required", restartRequired)
            },
        )

    private fun refuse(status: HttpStatusCode, message: String) =
        JsonReply(status, JsonWire.string(buildJsonObject { put("error", message) }))
}
