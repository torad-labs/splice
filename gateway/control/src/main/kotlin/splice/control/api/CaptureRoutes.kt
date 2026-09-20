// NEW: V4-133, FEATURES.md §5/§6 — GET/PUT /api/heads/{head}/capture: "opt-in body capture per
// head; off by default, local disk only, size capped, keys redacted; feeds the Logs request drawer
// and the transcript view for clients with no local transcript".
//
// THE CAPTURE SWITCH IS THE TRACE KNOB, NEVER A SECOND STORE. V4-174 already built exactly this
// store (splice.gateway.wire.TraceStore/TurnTrace over ActivityDays, owner-only day files under
// <state>/trace/, headers redacted at write time, size-capped per record) behind
// `Knob.TRACE`/`TRACE_RETENTION_DAYS`/`TRACE_MAX_BODY_CHARS`, opt-in per head via
// `[heads.<key>.overrides]`. This route is the console's read/write surface onto those three keys
// for ONE head — it neither reads nor writes a trace file itself.
//
// RESTART REQUIRED, REPORTED HONESTLY. `HeadDeps.HeadStores.trace` (the TraceStore a head actually
// writes through) is built once, at head assembly (ManagedHeadFactory.assembleHead ->
// HeadTraceStores.forHead), from the config the daemon booted with — every trace knob is
// `restartRequired = true` (Knob.kt). Making it hot would mean constructing the store lazily
// behind a real seam on the turn path, which touches ManagedHeadFactory/HeadServer and is outside
// this row's fence; the honest, decoupled answer today is `restart_required: true` on every write,
// always, so the console never tells an operator a flip took effect when it has not.
//
// THE WRITE GOES THROUGH THE SAME TopologyWriter GET/PUT /api/topology ALREADY USES — the ONE seam
// that edits splice.toml (structure-preserving, backed up first, verified by re-parsing). This
// route only computes the three `[heads.<key>.overrides]` keys and hands the whole edited Topology
// to that writer; it holds no file handle of its own.
package splice.control.api

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
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText

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

internal class CaptureRoutes(
    private val resolver: HeadResolver,
    private val config: ConfigService,
    private val topology: TopologySource,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** GET: the head's EFFECTIVE trace settings (env/state/PATCH included, the same layering
     *  GET /api/config already reports) — what the daemon is actually doing right now, not
     *  necessarily what splice.toml alone would produce. */
    public fun read(head: String): JsonReply {
        val key = resolveKey(head) ?: return unknownHead(head)
        val cfg = config.getConfig(key)
        val body = captureJson(key, cfg.trace, cfg.traceRetentionDays, cfg.traceMaxBodyChars.toLong())
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
        val key = resolveKey(head) ?: return unknownHead(head)
        // ast-grep-ignore: kt-no-silent-result-collapse -- a body that is not JSON and a body of the wrong shape get the same answer, one 400 naming the shape expected, so the failure has nothing more to say
        val parsed = Cancellables.runCatchingCancellable { json.decodeFromString(CaptureWrite.serializer(), body) }
            .getOrNull() ?: return refuse(HttpStatusCode.BadRequest, BAD_CAPTURE_BODY)
        return applyWrite(writer, key, parsed)
    }

    private fun applyWrite(writer: TopologyWriter, key: String, parsed: CaptureWrite): JsonReply {
        val current = Cancellables.runCatchingCancellable { decode(writer) }.getOrElse { failure ->
            val why = SafeFailureText.render(failure)
            return refuse(HttpStatusCode.InternalServerError, "splice.toml does not parse: $why")
        }
        val head = current.heads[key] ?: return unknownHead(key)
        val overrides = head.overrides.toMutableMap()
        overrides[Knob.TRACE.key] = parsed.enabled.toString()
        parsed.retentionDays?.let { overrides[Knob.TRACE_RETENTION_DAYS.key] = it.toString() }
        parsed.maxBodyChars?.let { overrides[Knob.TRACE_MAX_BODY_CHARS.key] = it.toString() }
        val requested = current.copy(heads = current.heads + (key to head.copy(overrides = overrides)))
        return when (val result = writer.write(requested)) {
            is TopologyWriteResult.Written -> JsonReply(HttpStatusCode.OK, writtenJson(key, parsed))
            is TopologyWriteResult.Refused -> refuse(HttpStatusCode.BadRequest, refusalMessage(result))
        }
    }

    private fun writtenJson(key: String, parsed: CaptureWrite): String {
        val effective = config.getConfig(key)
        val retentionDays = parsed.retentionDays ?: effective.traceRetentionDays
        val maxBodyChars = parsed.maxBodyChars ?: effective.traceMaxBodyChars.toLong()
        return captureJson(key, parsed.enabled, retentionDays, maxBodyChars)
    }

    // SAFE-RENDER-EXEMPT[2026-09-20]: TopologyFinding.message is TopologyChecks' own curated
    // validation text (a path and a reason: missing provider, bad port), never a stored secret or
    // a throwable's raw text — the same data TopologyRoutes.kt's GET/PUT /api/topology already
    // renders via plain `put("message", finding.message)`.
    private fun refusalMessage(result: TopologyWriteResult.Refused): String =
        result.findings.joinToString("; ") { "${it.path}: ${it.message}" }.ifEmpty { "splice.toml write refused" }

    private fun decode(writer: TopologyWriter): Topology =
        json.decodeFromJsonElement(Topology.serializer(), writer.current())

    private fun resolveKey(name: String): String? = resolver.headByName(name).firstOrNull()?.head?.key

    private fun unknownHead(name: String) = refuse(HttpStatusCode.BadRequest, "unknown head: $name")

    private fun captureJson(head: String, enabled: Boolean, retentionDays: Int, maxBodyChars: Long): String =
        buildJsonObject {
            put("head", head)
            put("enabled", enabled)
            put("retention_days", retentionDays)
            put("max_body_chars", maxBodyChars)
            // Always true — see the file header. A future hot-construction seam flips this to a
            // real computation instead of a literal; today's fence does not reach it.
            put("restart_required", true)
        }.toString()

    private fun refuse(status: HttpStatusCode, message: String) =
        JsonReply(status, buildJsonObject { put("error", message) }.toString())
}
