// NEW: preserves exact completed callback evidence when interrupted execution cannot be resumed.
package splice.provider.codex

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.JsonWire
import splice.upstream.codemode.CodeModeLimits
import java.math.BigDecimal
import java.math.RoundingMode

internal object CodeModeInterruption {
    /** The evidence rides upstream as the outer call's own output, so only [budgetChars] (the
     *  upstream output ceiling) bounds it — never the worker's text frame, which it never crosses.
     *  Grading it against the frame limit poisoned every record whose accumulated results passed
     *  64 KiB (five Reads on 2026-09-07: 47 identical failures over 80 minutes, since the same
     *  request kept resolving to the same poisoned record). When the results outgrow the budget,
     *  each output is cut to an equal share behind a marker: the model still learns which calls
     *  ran and what they returned first. */
    fun output(record: CodeModeRecord, detail: String, budgetChars: Int): String {
        var rendered = render(record, detail, cap = null)
        if (rendered.length <= budgetChars || record.results.isEmpty()) return rendered
        val overhead = render(record, detail, cap = 0).length
        var share = ((budgetChars - overhead) / record.results.size - MARKER_RESERVE).coerceAtLeast(MIN_SHARE)
        rendered = render(record, detail, share)
        // JSON escaping can inflate a share past its budget; halve until it fits or the floor holds.
        while (rendered.length > budgetChars && share > MIN_SHARE) {
            share = (share / 2).coerceAtLeast(MIN_SHARE)
            rendered = render(record, detail, share)
        }
        return rendered
    }

    private fun render(record: CodeModeRecord, detail: String, cap: Int?): String = buildJsonObject {
        put("status", "interrupted")
        put("sourceRerun", false)
        put("detail", detail)
        putJsonArray("results") {
            record.results.forEach { (id, result) ->
                add(
                    buildJsonObject {
                        put("id", id)
                        put("output", bounded(result.output, cap))
                        put("isError", result.isError)
                    },
                )
            }
        }
        putJsonArray("unresolved") {
            record.pending.filter { it.clientId !in record.results }.forEach { pending ->
                add(
                    buildJsonObject {
                        put("id", pending.clientId)
                        put("name", pending.name)
                        put("status", if (pending.exposed) "result unavailable" else "not scheduled")
                    },
                )
            }
        }
    }.let(JsonWire::string)

    private fun bounded(output: String, cap: Int?): String = when {
        cap == null || output.length <= cap -> output
        else -> output.take(cap) + " [truncated ${output.length - cap} chars]"
    }

    private const val MIN_SHARE = 256
    private const val MARKER_RESERVE = 40
}

/** V4-388: codex-rs core/src/tools/code_mode/output.rs and mod.rs handle_runtime_response, ported: the
 *  exec output a code_mode_only model is trained to read. codex sends a header item
 *  "{status}\nWall time {s:.1} seconds\nOutput:\n", the script's text items, and on a failure a last item
 *  "Script error:\n{error}"; splice's output is one string, so the items are joined in that order. */
internal object CodeModeExecOutput {
    /** format_script_status: a script that returned or called exit(). */
    fun completed(output: String, wallMillis: Long, budgetChars: Int): String =
        frame("Script completed", wallMillis, output, null, budgetChars)

    /** A script that threw: what it logged first, then codex's "Script error:" item. */
    fun failed(output: String, error: String, wallMillis: Long, budgetChars: Int): String =
        frame("Script failed", wallMillis, output, error, budgetChars)

    /** A script splice ended without rerunning it (new client content, no worker free). Its evidence is
     *  JSON budgeted per result against the upstream ceiling ([CodeModeInterruption]), never cut here, so
     *  the header's length comes out of that budget instead. */
    fun terminated(record: CodeModeRecord, detail: String, wallMillis: Long, budgetChars: Int): String {
        val header = header("Script terminated", wallMillis)
        return header + CodeModeInterruption.output(record, detail, budgetChars - header.length)
    }

    private fun header(status: String, wallMillis: Long): String {
        // codex rounds to tenths half-up ((s * 10).round() / 10); a decimal at millisecond scale does it exactly.
        val seconds = BigDecimal.valueOf(wallMillis.coerceAtLeast(0), MILLI_SCALE).setScale(1, RoundingMode.HALF_UP)
        return "$status\nWall time ${seconds.toPlainString()} seconds\nOutput:\n"
    }

    /** The raw output already passed the worker's text limit; the framed text rides upstream as the outer
     *  call's output, so only [budgetChars] (the upstream ceiling) bounds it, as for an interruption. The
     *  error keeps a bounded share and the output takes what is left. */
    private fun frame(status: String, wallMillis: Long, output: String, error: String?, budgetChars: Int): String {
        val header = header(status, wallMillis)
        val tail = error?.let {
            val separator = if (output.isEmpty()) "" else "\n"
            "${separator}Script error:\n${CodeModeLimits.boundedText(it, ERROR_SHARE_CHARS)}"
        }.orEmpty()
        val room = (budgetChars - header.length - tail.length).coerceAtLeast(0)
        return header + CodeModeLimits.boundedText(output, room) + tail
    }
}

// why: a millisecond count read as seconds is that count at three decimal places.
private const val MILLI_SCALE: Int = 3

// why: an error is a reason, not evidence; a quarter of the worker's 64 KiB text frame keeps a long one.
private const val ERROR_SHARE_CHARS: Int = 16_384
