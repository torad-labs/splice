// NEW: 2026-11-09 audit — the rule for a top-level field of the source request on every Responses head. Claude Code
// updates itself, and a request carrying its `diagnostics` field, then listed among the controls this path refuses,
// refused every claudex turn for 67 minutes (5:51-6:58 PM CT). The field is not new in 2.1.321: it is in 2.1.319
// requests and in the 2.1.296 binary. A field the client adds must not take a head down unless it
// could change what the model does. Every field is one of three kinds, and that is the line between them:
//  1. A control over the answer: what the Responses request is built from ([carriedFields]), or a control it has no
//     equivalent for (sourceOnlyControls and the policy checks in ResponsesRequestControls), which refuses the turn
//     rather than answer a different question than the one asked.
//  2. Something that never shapes the answer on this path ([droppedFields]): the client's record of itself, or an
//     Anthropic server feature the head's own history stands in for. It is dropped from the wire, as every field
//     ResponsesRequestControls.wire does not name always was, and the head says so once per field.
//  3. Any other field. A field no one here has read may be a control, so it is refused, naming the field in
//     daemon.log, unless its own name says it is a record ([recordWords]: `client_telemetry`, `request_metadata`,
//     `debug_trace`), which is dropped like (2). The cost each way: a harmless field with a neutral name refuses until
//     it gets its line here, and the head reads degraded within five requests naming it (RefusalStreak); a control
//     named like a record would be dropped, and vendors name controls for what they do, not as records.
// The 11 fields Claude Code 2.1.321 sends are all classified: model, messages, system, tools, metadata, max_tokens,
// stream, thinking, output_config, context_management and diagnostics (600 passthrough requests, Nov 9-10).
package splice.dialect.responses.admission

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import splice.core.util.LogSink
import java.util.concurrent.ConcurrentHashMap

/** Kind 1 that the Responses request carries, under the policy checks that refuse what it cannot. The effort and
 *  summary aliases are the head's own loose reads (ResponsesLooseFields). */
private val carriedFields = setOf(
    "model", "messages", "system", "tools", "tool_choice", "thinking", "max_tokens", "stream", "metadata",
    "output_config", "output_format", "temperature", "top_p", "service_tier", "speed", "cache_control", "reasoning",
    "effort", "reasoning_effort", "reasoning_summary",
)

/** Kind 2, each with why it never shapes the answer here: the words the head's once-per-field line says. */
private val droppedFields = mapOf(
    "diagnostics" to "Claude Code's record of its own previous message, which no model reads",
    // Claude Code sends {"edits":[{"type":"clear_thinking_20251015","keep":"all"}]} on every turn: Anthropic's server
    // edits its own copy of the history, and a Responses head's history is the vendor's and splice's.
    "context_management" to "Anthropic's server-side context editing, which this head's own history stands in for",
)

/** The words that make an unlisted field's name a record rather than a control, each a whole `_`-separated word. */
private val recordWords = setOf(
    "diagnostic",
    "diagnostics",
    "telemetry",
    "analytics",
    "metadata",
    "trace",
    "tracing",
    "debug",
)

/** Which kind each top-level field of a source request is. */
internal object ResponsesSourceFields {
    /** The first field of [raw] of kind 3 that its name does not drop: the field the turn is refused for. */
    fun unknown(raw: JsonObject): String? = present(raw).firstOrNull { !isControl(it) && !isDropped(it) }

    /** The fields of [raw] this path drops from the wire, listed or named as records. */
    fun dropped(raw: JsonObject): List<String> = present(raw).filter { !isControl(it) && isDropped(it) }

    /** Why [field] is dropped, in the words the head's once-per-field line says. */
    fun whyDropped(field: String): String =
        droppedFields[field] ?: "its name says it is the client's record of the request, not a control over the answer"

    /** A client's field name as a log line may carry it: its own characters, cut, never a line break of its own. */
    fun nameOf(field: String): String =
        field.take(MAX_FIELD_NAME_CHARS).map { if (it.isLetterOrDigit() || it in "_-.") it else '?' }.joinToString("")

    private fun isControl(field: String): Boolean = field in carriedFields || field in sourceOnlyControls

    private fun isDropped(field: String): Boolean =
        field in droppedFields || field.lowercase().split('_').any { it in recordWords }

    /** The fields [raw] sets: a null asks for nothing. */
    private fun present(raw: JsonObject): List<String> = raw.entries.filter { it.value !is JsonNull }.map { it.key }
}

/** Kind 2, said: each field a head drops from the wire is logged once, with why, so a client that starts sending one is
 *  seen at its first request. At most [MAX_SAID_FIELDS] names are remembered. */
internal class ResponsesDroppedFields(private val log: LogSink) {
    private val said = ConcurrentHashMap.newKeySet<String>()

    fun say(head: String, raw: JsonObject) {
        for (field in ResponsesSourceFields.dropped(raw)) {
            if (said.size >= MAX_SAID_FIELDS || !said.add(field)) continue
            log(
                "[$head] drops the request field `${ResponsesSourceFields.nameOf(field)}` from the wire: " +
                    "${ResponsesSourceFields.whyDropped(field)} (said once per field until the daemon restarts)\n",
            )
        }
    }
}

// why: a field's name goes into daemon.log; a client's names are a few dozen characters, and one past this is cut.
private const val MAX_FIELD_NAME_CHARS = 64

// why: a client sends a handful of record fields; past this many distinct names the rest are not worth a line each.
private const val MAX_SAID_FIELDS = 64
