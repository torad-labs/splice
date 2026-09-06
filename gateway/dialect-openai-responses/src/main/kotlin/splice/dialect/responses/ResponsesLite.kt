// NEW: codex-rs responses-lite parity for the gpt-5.6 family (source read 2026-07-19; accepted by
// the live backend the same day): lite turns move instructions into a developer input item and
// tools into an additional_tools input item, omitting both top-level fields. The gate and the
// input reshaping live here as ONE seam so the builder file stays under its function budget.
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The one lite seam: how input/instructions/tools ride on the wire for this turn. */
internal data class WireShape(val input: JsonArray, val instructions: String?, val tools: JsonArray?)

/**
 * The lite gate + the lite input reshaping, bound to ONE provider's quirks.
 *
 * [isLite] and [wireShape] were file-level functions (the gate an extension on [ResponsesQuirks]);
 * Kotlin main sources carry no top-level functions, so the seam became a type. The quirks ride the
 * CONSTRUCTOR rather than a leading parameter deliberately: every member keeps its old argument
 * list exactly, so no call site can silently reorder arguments.
 */
internal class ResponsesLiteShape(private val quirks: ResponsesQuirks) {

    /** Lite gate: every turn on a responses-lite model, compaction included — lite is a property
     *  of the MODEL, and a compaction built in the non-lite shape shares no prefix with the
     *  session's lite turns (2026-09-05). */
    fun isLite(opts: BuildOptions): Boolean =
        quirks.responsesLiteModelRegex?.containsMatchIn(opts.upstreamModel) == true

    fun wireShape(lite: Boolean, input: JsonArray, instructions: String, tools: JsonArray?): WireShape =
        if (lite) {
            // The empty field is codex-only serde parity, never a property of responses-lite itself:
            // ResponsesApiRequest.instructions is non-optional (core/src/client.rs:874), while the
            // shared dialect historically omitted top-level instructions after moving them to input.
            val topLevelInstructions = if (quirks.emitEmptyLiteInstructions) "" else null
            WireShape(liteInput(input, tools, instructions), instructions = topLevelInstructions, tools = null)
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
}

private const val LITE_CONTENT_FIELD = "content"
