// NEW: whether a system-prompt layer's text is still in the finished request, asked of the TREE.
//
// TurnPrompts used to serialise the whole request body to a String and run `contains` over it, which
// cost one body-sized String per round on any head configured with a strip layer — about 2.0 MB on a
// 1.04 MB body (RequestParseAllocationTest's fixture). Nothing about that answer reaches the wire, so
// there is no byte contract to preserve here, only the question itself.
//
// Asking the tree is also what fixed the comparison. Against a serialised body the needle had to be
// escaped first, because a multi-line append's real newline never matches the `\n` the serialiser
// wrote, and every multi-line layer read as deleted until the needle was escaped too (v0.4.0
// prompt-review). A string primitive's own content is already unescaped, so raw matches raw and that
// class of mismatch cannot come back.
package splice.head.turn

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object PromptSurvival {
    /** True when some string value anywhere in [body] contains [text]. Walks the tree with bounded
     *  scratch and allocates no copy of any value: a primitive's content is the stored String. */
    fun present(body: JsonElement, text: String): Boolean = when (body) {
        is JsonPrimitive -> body.isString && body.content.contains(text)
        is JsonObject -> body.values.any { present(it, text) }
        is JsonArray -> body.any { present(it, text) }
    }
}
