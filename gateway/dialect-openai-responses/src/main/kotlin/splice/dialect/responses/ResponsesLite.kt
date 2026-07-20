// NEW: codex-rs responses-lite parity for the gpt-5.6 family (source read 2026-07-19; accepted by
// the live backend the same day): lite turns move instructions into a developer input item and
// tools into an additional_tools input item, omitting both top-level fields. The gate and the
// input reshaping live here as ONE seam so the builder file stays under its function budget.
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Lite gate: non-compact turns on a responses-lite model (compact keeps the normal shape —
 *  a text-only summarizer turn has no tools and its forced instructions stay top-level). */
internal fun ResponsesQuirks.isLite(opts: BuildOptions): Boolean =
    !opts.compact && responsesLiteModelRegex?.containsMatchIn(opts.upstreamModel) == true

/** The one lite seam: how input/instructions/tools ride on the wire for this turn. */
internal data class WireShape(val input: JsonArray, val instructions: String?, val tools: JsonArray?)

internal fun wireShape(lite: Boolean, input: JsonArray, instructions: String, tools: JsonArray?): WireShape =
    if (lite) {
        WireShape(liteInput(input, tools, instructions), instructions = null, tools = null)
    } else {
        WireShape(input, instructions, tools)
    }

/** codex-rs responses-lite input: [additional_tools (developer), developer base-instructions,
 *  ...history]. Shape read from codex-rs core/src/client.rs and accepted by the live backend. */
private fun liteInput(input: JsonArray, tools: JsonArray?, instructions: String): JsonArray =
    buildJsonArray {
        if (tools != null) {
            add(
                buildJsonObject {
                    put("type", "additional_tools")
                    put("role", "developer")
                    put("tools", tools)
                },
            )
        }
        add(
            buildJsonObject {
                put("role", "developer")
                put(LITE_CONTENT_FIELD, instructions)
            },
        )
        input.forEach { add(it) }
    }

private const val LITE_CONTENT_FIELD = "content"
