// Goal (fix 1 of the Oct 27 Responses fixes, artifacts/responses-gaps-2026-10-27/goals.md): every request splice sends
// to a Responses backend answers each call, sends no result without its call, and carries each reasoning id at most
// once. Where a result was sent, that result goes out, never a stand-in.
//
// The property, over generated inputs: any mix of messages, reasoning items (ids repeated, with and without the
// stream's empty content), calls of the four kinds Codex pairs (function, custom tool, local shell, tool search),
// written once or several times, with ids or without, and results that come after their call, before it, twice, of
// the wrong kind, or for a call that is not there. After pairing:
// - each call goes out once, and a result of its kind and id goes out with it;
// - no result goes out whose call the input does not hold (a server-side tool search result aside, which Codex also
//   keeps);
// - each reasoning id goes out once;
// - a call that had results keeps all of them, in their order; one that had none is answered the way Codex answers
//   it, right after the call;
// - everything else goes out as it came, in its order, and pairing twice changes nothing more.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.JsonScalars
import kotlin.random.Random

private const val RUNS = 2_000
private const val MAX_ITEMS = 40
private const val POOL = 3

/** A call kind Codex pairs, the result kind that answers it, and the prefix of Codex's stand-in id. */
private enum class Kind(val call: String, val output: String, val prefix: String) {
    FUNCTION("function_call", "function_call_output", "fco"),
    CUSTOM("custom_tool_call", "custom_tool_call_output", "ctco"),
    SHELL("local_shell_call", "function_call_output", "fco"),
    SEARCH("tool_search_call", "tool_search_output", "tso"),
}

class ResponsesCallPairingPropertyTest {
    private val pairing = ResponsesCallPairing()

    private fun type(item: JsonElement): String? = JsonScalars.str(item as? JsonObject, "type")

    private fun callId(item: JsonElement): String? = JsonScalars.str(item as? JsonObject, "call_id")

    private fun kindOfCall(item: JsonElement): Kind? = Kind.entries.firstOrNull { it.call == type(item) }

    private fun isServerSearch(item: JsonElement): Boolean =
        type(item) == "tool_search_output" && JsonScalars.str(item as? JsonObject, "execution") == "server"

    private fun isOutput(item: JsonElement): Boolean =
        type(item) in Kind.entries.map { it.output } && !isServerSearch(item)

    /** Whether [output] answers [call]: its own result kind and its call id. A shell call is answered by a function
     *  call output, as Codex reads it. */
    private fun answers(output: JsonElement, call: JsonElement): Boolean {
        val kind = kindOfCall(call) ?: return false
        return isOutput(output) && type(output) == kind.output && callId(output) == callId(call)
    }

    /** The calls a result of this kind and id could answer. */
    private fun answered(output: JsonElement, items: List<JsonElement>): List<JsonElement> =
        items.filter { kindOfCall(it) != null && answers(output, it) }

    private fun generate(random: Random, run: Int): JsonArray {
        var serial = 0
        return JsonArray(List(random.nextInt(MAX_ITEMS + 1)) { item(random, "$run-${serial++}") })
    }

    /** One generated item: a message, a reasoning item, a call of any kind, a result of any kind, or a server-side
     *  tool search result, which answers no client call. */
    private fun item(random: Random, marker: String): JsonObject = when (random.nextInt(9)) {
        0 -> buildJsonObject {
            put("role", if (random.nextBoolean()) "user" else "assistant")
            put("content", "text $marker")
        }
        1, 2 -> reasoning(random)
        3, 4 -> call(Kind.entries.random(random), random)
        5, 6, 7 -> output(Kind.entries.random(random), random, marker)
        else -> buildJsonObject {
            put("type", "tool_search_output")
            if (random.nextBoolean()) put("call_id", "srv_${random.nextInt(POOL)}")
            put("status", "completed")
            put("execution", "server")
            putJsonArray("tools") {}
        }
    }

    /** A reasoning item of a small pool of ids, sometimes without an id, and sometimes with the stream's empty
     *  content, which a record keeps and the reasoning cache's copy does not. */
    private fun reasoning(random: Random): JsonObject = buildJsonObject {
        val id = "rs_${random.nextInt(POOL + 1)}"
        put("type", "reasoning")
        if (random.nextInt(8) > 0) put("id", id)
        put("encrypted_content", "enc-$id")
        putJsonArray("summary") {}
        if (random.nextBoolean()) putJsonArray("content") {}
    }

    private fun call(kind: Kind, random: Random): JsonObject = buildJsonObject {
        val callId = "${kind.name.lowercase()}_${random.nextInt(POOL)}"
        put("type", kind.call)
        if (random.nextBoolean()) put("id", "item_$callId")
        put("call_id", callId)
        when (kind) {
            Kind.FUNCTION -> {
                put("name", "Read")
                put("arguments", "{}")
            }
            Kind.CUSTOM -> {
                put("name", "exec")
                put("input", "text('x')")
            }
            Kind.SHELL -> {
                put("status", "completed")
                put("action", buildJsonObject { put("type", "exec") })
            }
            Kind.SEARCH -> {
                put("execution", "client")
                put("arguments", buildJsonObject { put("query", "q") })
            }
        }
    }

    private fun output(kind: Kind, random: Random, marker: String): JsonObject = buildJsonObject {
        // A function call output may answer a function call or a shell call; the generator spells both ids.
        val shellOwned = kind == Kind.SHELL || kind == Kind.FUNCTION && random.nextBoolean()
        val owner = if (shellOwned) Kind.SHELL else kind
        put("type", kind.output)
        put("call_id", "${owner.name.lowercase()}_${random.nextInt(POOL)}")
        if (kind == Kind.SEARCH) {
            put("status", "completed")
            put("execution", "client")
            putJsonArray("tools") { add(JsonPrimitive("tool $marker")) }
        } else {
            put("output", "result $marker")
        }
    }

    /** Codex's stand-in for a call that never had a result (normalize.rs ensure_call_outputs_present). */
    private fun assertStandIn(call: JsonElement, output: JsonElement, context: String) {
        val kind = checkNotNull(kindOfCall(call))
        val item = output as JsonObject
        assertEquals(kind.output, type(item), context)
        assertEquals(callId(call), callId(item), context)
        if (kind == Kind.SEARCH) {
            assertEquals("completed", JsonScalars.str(item, "status"), context)
            assertEquals("client", JsonScalars.str(item, "execution"), context)
            assertEquals(JsonArray(emptyList()), item["tools"], context)
        } else {
            assertEquals(JsonPrimitive("aborted"), item["output"], context)
        }
        val callItemId = JsonScalars.str(call as JsonObject, "id")
        val standInId = JsonScalars.str(item, "id")
        if (callItemId.isNullOrEmpty()) {
            assertEquals(null, standInId, "a call without an id gets a stand-in without one: $context")
        } else {
            assertNotNull(standInId, context)
            assertTrue(checkNotNull(standInId).startsWith("${kind.prefix}_"), context)
        }
    }

    @Test
    fun `every call is answered, no result goes out without its call, and each reasoning id goes out once`() {
        repeat(RUNS) { run ->
            val input = generate(Random(run), run)
            val paired = pairing.pair(input)
            val context = "run $run\ninput:  $input\npaired: $paired"

            // Each call once, and answered: by the results it was sent with, or by Codex's stand-in right after it.
            val calls = paired.withIndex().filter { kindOfCall(it.value) != null }
            assertEquals(
                calls.size,
                calls.map { type(it.value) to callId(it.value) }.toSet().size,
                "a call goes out twice: $context",
            )
            for ((at, call) in calls) {
                val own = input.filter { answers(it, call) }
                val sent = paired.filter { answers(it, call) }
                if (own.isEmpty()) {
                    assertEquals(1, sent.size, "call ${callId(call)} is not answered once: $context")
                    assertEquals(sent.single(), paired[at + 1], "a stand-in goes right after its call: $context")
                    assertStandIn(call, sent.single(), context)
                } else {
                    assertEquals(own, sent, "the results a call was sent with changed: $context")
                }
            }

            // No result whose call the input does not hold.
            paired.filter { isOutput(it) }.forEach { output ->
                assertTrue(answered(output, paired).isNotEmpty(), "result without its call: $context")
            }

            // Each reasoning id once: the first occurrence of each, in order, and every reasoning item without an id.
            val reasoning = { items: List<JsonElement> -> items.filter { type(it) == "reasoning" } }
            val expectedReasoning = reasoning(input).filter { item ->
                val id = JsonScalars.str(item as JsonObject, "id") ?: return@filter true
                reasoning(input).first { JsonScalars.str(it as JsonObject, "id") == id } === item
            }
            assertEquals(expectedReasoning, reasoning(paired), "reasoning: $context")

            // The calls are the first of each, in order; everything that is neither call, result nor reasoning is
            // untouched and in order.
            val firstCalls = input.filterIndexed { at, item ->
                kindOfCall(item) != null &&
                    input.take(at).none { type(it) == type(item) && callId(it) == callId(item) }
            }
            assertEquals(firstCalls, calls.map { it.value }, "calls: $context")
            val rest = { items: List<JsonElement> ->
                items.filter { kindOfCall(it) == null && !isOutput(it) && type(it) != "reasoning" }
            }
            assertEquals(rest(input), rest(paired), "other items: $context")

            assertEquals(paired, pairing.pair(paired), "pairing twice changes more: $context")
        }
    }
}
