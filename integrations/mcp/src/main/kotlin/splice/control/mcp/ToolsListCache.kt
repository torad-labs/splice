// The shared child's `tools/list` answer, kept so N sessions cost the child one listing (spec 8: the host caches
// tools/list). A listing with a cursor is a page of something else and is never kept. The answer is dropped when
// the child says its tools changed or goes away, and an answer already on its way back from before that is not kept.
package splice.control.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicLong

private const val TOOLS_LIST = "tools/list"

/** The listing the cache asks the child for when it has none. */
internal fun interface ListingAsk {
    suspend fun ask(): JsonObject
}

internal class ToolsListCache(private val codec: JsonRpcCodec) {
    @Volatile private var kept: JsonObject? = null
    private val generation = AtomicLong()

    /** [request] answered from the cache when it is a plain listing the child already gave, else by [ask]. */
    suspend fun answer(request: JsonObject, clientId: JsonElement, ask: ListingAsk): JsonObject {
        val listing = codec.method(request) == TOOLS_LIST && (request["params"] as? JsonObject)?.get("cursor") == null
        val cached = kept
        if (!listing) return ask.ask()
        if (cached != null) return codec.withId(cached, clientId)
        val before = generation.get()
        val answer = ask.ask()
        if (answer.containsKey("result") && before == generation.get()) kept = answer
        return answer
    }

    /** The child changed its tool list or went away: the next listing asks it again. */
    fun drop() {
        generation.incrementAndGet()
        kept = null
    }
}
