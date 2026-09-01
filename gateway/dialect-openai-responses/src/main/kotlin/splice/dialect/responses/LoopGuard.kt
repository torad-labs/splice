// NEW: the loop guard — a per-conversation circuit breaker for the identical-failed-call
// pathology. Measured on the live claudex head (operator report 2026-07-26): gpt-5.6 re-issued
// the SAME Edit with the SAME arguments 89-101x against Claude Code's staleness guard
// ("File has been modified since read") in a busy multi-agent workspace, burning ~860KB of
// upstream body per retry, while kimi in the same harness self-corrected on the first error.
// The gateway delivers those errors faithfully — the model just doesn't act on them. The guard
// walks the conversation the client already sends (stateless — no cache), counts failures of
// the same (tool, canonical input) since the last success, and from the 3rd identical failure
// rewrites that result's output with an escalating directive the model cannot miss. Success or
// a changed argument resets the count; non-error results (polling, timeouts) never trigger.
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import splice.core.wire.AnthropicMessage
import splice.core.wire.TextBlock
import splice.core.wire.ToolResultBlock
import splice.core.wire.ToolUseBlock

internal object LoopGuard {
    const val TRIGGER = 3
    const val REFUSAL = 5
    private const val ERROR_MARKER = "<tool_use_error>"

    // DR-95: tool input is untrusted and kotlinx exposes no max-depth knob, so the recursive
    // rebuild below (and canonical()'s toString of its result) was the one unbounded walk over
    // client-authored JSON — deep enough nesting StackOverflowError'd outside every sanctioned
    // catch. Same 200-depth invariant as PassthroughCacheControl's placement walk.
    private const val DEPTH_CAP = 200

    /** toolUseId -> directive text for every result whose (tool, input) has failed TRIGGER+
     *  times since its last success. The caller prepends the directive to that result's output. */
    fun analyze(messages: List<AnthropicMessage>): Map<String, String> {
        val state = GuardState()
        messages.forEach { msg -> msg.content.forEach(state::onBlock) }
        return state.directives
    }

    private class GuardState {
        val directives = HashMap<String, String>()
        private val calls = HashMap<String, Pair<String, String>>() // call id -> (name, canonical input)
        private val failures = HashMap<String, Int>() // "name|input" -> consecutive failures

        fun onBlock(block: splice.core.wire.ContentBlock) {
            when (block) {
                is ToolUseBlock -> calls[block.id] = block.name to canonical(block.input)
                is ToolResultBlock -> onResult(block)
                else -> Unit
            }
        }

        private fun onResult(block: ToolResultBlock) {
            val (name, input) = calls[block.toolUseId] ?: return
            val key = "$name|$input"
            val text = block.content.filterIsInstance<TextBlock>().joinToString("") { it.text }
            // CX-11: prefer Anthropic's structured verdict; ERROR_MARKER is a Claude Code
            // formatting detail with no canary, so it is the fallback for clients that omit
            // is_error — never an override of a client that sent is_error:false (a tool whose
            // output merely quotes the marker is not a failed call).
            // On drift: if Claude Code renames the marker, add the new spelling HERE, next to
            // the structured signal that keeps working regardless.
            if (block.isError ?: (ERROR_MARKER in text)) {
                val n = (failures[key] ?: 0) + 1
                failures[key] = n
                if (n >= TRIGGER) directives[block.toolUseId] = directive(name, n)
            } else {
                // any unmarked result means the call worked — the retry was rational,
                // so the streak resets (polling and flaky tools never arm the guard).
                failures[key] = 0
            }
        }
    }

    private fun directive(name: String, failures: Int): String =
        if (failures >= REFUSAL) {
            "[splice loop-guard] STOP. This exact call ($name with these arguments) has failed " +
                "$failures times with the same error. Do NOT issue it again with these arguments in " +
                "this conversation. Do something DIFFERENT: re-read the target fresh, change the " +
                "arguments, or explain to the user why the goal is unreachable."
        } else {
            "[splice loop-guard] This exact call ($name with these arguments) has now failed " +
                "$failures times with the same error. Retrying it unchanged cannot succeed. Read the " +
                "error and CHANGE APPROACH — for a stale-file error, re-read the file fresh, then " +
                "edit based on the new read."
        }

    // Key-order-independent encoding so {a:1,b:2} and {b:2,a:1} share a failure streak.
    private fun canonical(input: JsonObject): String = sortKeys(input).toString()

    // Subtrees at DEPTH_CAP collapse to a marker, not a pass-through: canonical()'s toString
    // would still recurse the original deep subtree. Inputs identical down to the cap share a
    // canonical, so identical deep spam still streaks (the guard FIRES); inputs differing only
    // below the cap coalesce, which a nudge-only feature may coarsen but a crash may not.
    private fun sortKeys(el: JsonElement, depth: Int = 0): JsonElement = when {
        depth >= DEPTH_CAP -> JsonPrimitive("[splice depth-capped]")
        el is JsonObject -> buildJsonObject {
            el.keys.sorted().forEach { k -> put(k, sortKeys(el.getValue(k), depth + 1)) }
        }
        el is JsonArray -> buildJsonArray { el.forEach { add(sortKeys(it, depth + 1)) } }
        else -> el
    }
}
