// NEW: exact payload tokens drive native order and contiguous matching independently of logical anchors.
package splice.provider.codex.state.native

import kotlinx.serialization.json.JsonElement
import splice.core.util.JsonElementInterner
import splice.core.util.JsonElementInterner.Token

internal class CodeModeNativeMatching(private val payloads: JsonElementInterner) {
    fun ordered(expected: List<Token>, replay: List<Token>, known: Set<Token>): Boolean {
        val actual = replay.filter { it in known }
        val present = actual.toSet()
        return expected.filter { it in present } == actual
    }

    fun contains(items: List<JsonElement>, expected: List<JsonElement>): Boolean {
        val tokens = expected.map(payloads::token)
        return tokens.isNotEmpty() && (0..(items.size - tokens.size)).any { at ->
            tokens.indices.all { payloads.token(items[at + it]) == tokens[it] }
        }
    }
}
