// The shared child's tools/list answer is kept for N sessions and must never outlive a change of the child's tools,
// including a change notification that lands after the listing came back and before the cache kept it.
package splice.control.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ToolsListCacheTest {
    private val request = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 1)
        put("method", "tools/list")
    }
    private val client: JsonElement = JsonPrimitive(1)

    /** The child, whose tool list is whatever [tools] says when it is asked; [asked] counts the asks. */
    private class Child {
        var tools = "old"
        var asked = 0
        fun listing(): JsonObject = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("result", buildJsonObject { put("tools", tools) })
        }
    }

    private fun listed(cache: ToolsListCache, child: Child): String = runBlocking {
        val answer = cache.answer(request, client) { child.asked += 1; child.listing() }
        ((answer["result"] as JsonObject)["tools"] as JsonPrimitive).content
    }

    @Test
    fun `a listing is kept for the next session and dropped when the child changes its tools`() {
        val child = Child()
        val cache = ToolsListCache(JsonRpcCodec())

        listed(cache, child)
        listed(cache, child)
        assertEquals(1, child.asked, "the second session was served from the cache")

        child.tools = "new"
        cache.drop()

        assertEquals("new", listed(cache, child))
    }

    @Test
    fun `a change that lands after the listing came back and before it was kept leaves nothing stale cached`() {
        val child = Child()
        lateinit var cache: ToolsListCache
        var armed = true
        val changeLandsHere = ListingReturned {
            if (armed) {
                armed = false
                child.tools = "new"
                cache.drop()
            }
        }
        cache = ToolsListCache(JsonRpcCodec(), changeLandsHere)

        assertEquals("old", listed(cache, child), "the in-flight answer is the old child's, and it is returned")

        assertEquals("new", listed(cache, child), "but it was not kept: the next session asks again")
        assertEquals(2, child.asked)
    }
}
