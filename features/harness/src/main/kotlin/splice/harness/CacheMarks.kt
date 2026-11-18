// NEW: the agent's call (docs/specs/harness.md, decision 12, Kept). Claude Code's cache marks on a message's blocks:
// the mark a client put on its copy of an answer, moved to the last message put back in its place, and the key a
// message's content is known by, read past every mark and the order a client writes keys in.
package splice.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonWire
import java.security.MessageDigest
import java.util.HexFormat

internal class CacheMarks {
    private val wire = AgentWire()

    /** The first cache mark among [blocks], or null. */
    fun mark(blocks: List<JsonObject>): JsonElement? = blocks.firstNotNullOfOrNull { it[MARK] }

    /** [message] with [mark] on its last block, where the API takes one: never on a thinking block. */
    fun marked(message: JsonObject, mark: JsonElement): JsonObject {
        val blocks = wire.content(message)
        val last = blocks.lastOrNull()?.takeUnless { wire.type(it) in UNMARKABLE } ?: return message
        return wire.message(wire.role(message).orEmpty(), blocks.dropLast(1) + JsonObject(last + (MARK to mark)))
    }

    /** The key a message's content is known by: its blocks with every cache mark left out and every object's keys in
     *  one order, so the same content keys the same however a client marks it and writes it. */
    fun key(blocks: List<JsonObject>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(JsonWire.string(canonical(JsonArray(blocks))).toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.filterKeys { it != MARK }.toSortedMap().mapValues { (_, v) -> canonical(v) },
        )
        is JsonArray -> JsonArray(element.map(::canonical))
        is JsonPrimitive -> element
    }
}

private const val MARK = "cache_control"
private val UNMARKABLE = setOf("thinking", "redacted_thinking")
