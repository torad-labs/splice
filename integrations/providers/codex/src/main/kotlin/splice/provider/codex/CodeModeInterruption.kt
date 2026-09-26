// NEW: preserves exact completed callback evidence when interrupted execution cannot be resumed.
package splice.provider.codex

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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
    }.toString()

    private fun bounded(output: String, cap: Int?): String = when {
        cap == null || output.length <= cap -> output
        else -> output.take(cap) + " [truncated ${output.length - cap} chars]"
    }

    private const val MIN_SHARE = 256
    private const val MARKER_RESERVE = 40
}
