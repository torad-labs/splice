// The shared child's `tools/list` answer, kept so N sessions cost the child one listing (spec 8: the host caches
// tools/list). A listing with a cursor is a page of something else and is never kept. The answer is dropped when
// the child says its tools changed or goes away, and an answer already on its way back from before that is not kept.
package splice.control.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicReference

private const val TOOLS_LIST = "tools/list"

/** The listing the cache asks the child for when it has none. */
internal fun interface ListingAsk {
    suspend fun ask(): JsonObject
}

/** The moment between a listing coming back and the cache keeping it: the one place a change notification can
 *  land, so a test can put one there. */
internal fun interface ListingReturned {
    operator fun invoke()
}

internal class ToolsListCache(
    private val codec: JsonRpcCodec,
    private val beforePublish: ListingReturned = ListingReturned {},
) {
    /** What is kept, and which generation of the child's tool list it belongs to. One value, so keeping an answer
     *  is a single compare-and-set against the value its listing started from: a [drop] in between replaces the
     *  value and the set fails, with no window between checking the generation and storing the answer. */
    private data class State(val kept: JsonObject?)

    private val state = AtomicReference(State(null))

    /** [request] answered from the cache when it is a plain listing the child already gave, else by [ask]. */
    suspend fun answer(request: JsonObject, clientId: JsonElement, ask: ListingAsk): JsonObject {
        val listing = codec.method(request) == TOOLS_LIST && (request["params"] as? JsonObject)?.get("cursor") == null
        if (!listing) return ask.ask()
        val seen = state.get()
        seen.kept?.let { return codec.withId(it, clientId) }
        val answer = ask.ask()
        beforePublish()
        if (answer.containsKey("result")) state.compareAndSet(seen, State(answer))
        return answer
    }

    /** The child changed its tool list or went away: the next listing asks it again. */
    fun drop() {
        state.set(State(null))
    }
}
