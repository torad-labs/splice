// NEW: exact payload identity rejects collisions and preserves JSON equality, copies, and occurrence counts.
package splice.core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.IdentityHashMap

class JsonElementInternerTest {
    @Test
    fun `Aa and BB structural hash collisions have distinct tokens`() {
        val first = Json.parseToJsonElement("""{"content":"Aa"}""")
        val collision = Json.parseToJsonElement("""{"content":"BB"}""")
        val tokens = JsonElementInterner()
        assertEquals(first.hashCode(), collision.hashCode(), "fixture must exercise an actual hash collision")
        assertNotEquals(first, collision)
        assertNotEquals(tokens.token(first), tokens.token(collision))
    }

    @Test
    fun `reordered fields have one token while their wire spellings stay distinct`() {
        val first = Json.parseToJsonElement("""{"type":"reasoning","content":{"a":1,"b":2}}""")
        val reordered = Json.parseToJsonElement("""{"content":{"b":2,"a":1},"type":"reasoning"}""")
        val tokens = JsonElementInterner()
        val firstWire = first.toString()
        val reorderedWire = reordered.toString()
        assertEquals(first, reordered)
        assertNotEquals(firstWire, reorderedWire)
        assertSame(tokens.token(first), tokens.token(reordered))
        assertEquals(firstWire, first.toString())
        assertEquals(reorderedWire, reordered.toString())
    }

    @Test
    fun `each raw instance is structurally hashed once including equal copies and collisions`() {
        val calls = IdentityHashMap<JsonElement, Int>()
        val tokens = JsonElementInterner { element ->
            calls[element] = (calls[element] ?: 0) + 1
            element.hashCode()
        }
        val first = Json.parseToJsonElement("""{"content":"Aa"}""")
        val copied = Json.parseToJsonElement("""{"content":"Aa"}""")
        val collision = Json.parseToJsonElement("""{"content":"BB"}""")
        val expected = listOf(first, copied, collision).map(tokens::token)
        repeat(100) {
            assertEquals(expected, listOf(first, copied, collision).map(tokens::token))
        }
        assertEquals(3, calls.size)
        listOf(first, copied, collision).forEach { assertEquals(1, calls[it]) }
        assertSame(expected[0], expected[1])
        assertNotEquals(expected[0], expected[2])
    }

    @Test
    fun `duplicates preserve sequence order and counted multiplicity`() {
        val first = Json.parseToJsonElement("""{"content":"Aa"}""")
        val copied = Json.parseToJsonElement("""{"content":"Aa"}""")
        val other = Json.parseToJsonElement("""{"content":"BB"}""")
        val tokens = JsonElementInterner()
        val items = listOf(first, other, copied, first)
        val indexed = items.map(tokens::token)
        assertEquals(items.size, indexed.size)
        assertEquals(
            listOf(tokens.token(first), tokens.token(other), tokens.token(first), tokens.token(first)),
            indexed,
        )
        assertEquals(3, indexed.count { it == tokens.token(first) })
        assertEquals(1, indexed.count { it == tokens.token(other) })
        assertNotEquals(indexed, listOf(other, first, copied, first).map(tokens::token))
    }

    @Test
    fun `token equivalence agrees with JSON equality for nested arrays and scalar spellings`() {
        val values = listOf(
            JsonNull,
            JsonPrimitive("null"),
            JsonPrimitive("Aa"),
            JsonPrimitive("BB"),
            JsonPrimitive(1),
            JsonPrimitive("1"),
            Json.parseToJsonElement("1.0"),
            Json.parseToJsonElement("1e0"),
            JsonPrimitive(true),
            JsonPrimitive("true"),
            JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))),
            JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(1))),
            Json.parseToJsonElement("""{"a":[1,2],"b":null}"""),
            Json.parseToJsonElement("""{"b":null,"a":[1,2]}"""),
        )
        val tokens = JsonElementInterner()
        for (first in values) {
            for (second in values) {
                assertEquals(first == second, tokens.token(first) == tokens.token(second))
            }
        }
    }
}
