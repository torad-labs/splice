// NEW 2026-11-19 (reply-stream): what a follower of a session's live reply is sent, read off the frames a turn writes
// to its client: the events, their order, the snapshot a joiner gets, and the follower that falls too far behind.
package splice.head.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.memory.HeapBudget
import splice.core.util.WallClock

class LiveRepliesTest {
    private val heap = HeapBudget(heapLimitBytes = Long.MAX_VALUE, budgetBytes = Long.MAX_VALUE / 4)
    private val hub = LiveReplies(heap, WallClock { 1_000L })

    private fun facts(session: String = "s1", agent: String? = null) =
        LiveTurnFacts(session = session, agent = agent, model = "claude-x--model", head = "x", compact = false)

    private fun frame(type: String, data: String) = "event: $type\ndata: $data\n\n"

    private fun start(index: Int, block: String) =
        frame("content_block_start", """{"type":"content_block_start","index":$index,"content_block":$block}""")

    private fun delta(index: Int, delta: String) =
        frame("content_block_delta", """{"type":"content_block_delta","index":$index,"delta":$delta}""")

    private val opening = frame("message_start", """{"type":"message_start","message":{"id":"msg_1","content":[]}}""")
    private val thinkingStart = start(0, """{"type":"thinking","thinking":""}""")

    private fun thought(text: String) = delta(0, """{"type":"thinking_delta","thinking":"$text"}""")

    private val signature = delta(0, """{"type":"signature_delta","signature":"SECRET-SIG"}""")
    private val textStart = start(1, """{"type":"text","text":""}""")

    private fun word(text: String) = delta(1, """{"type":"text_delta","text":"$text"}""")

    private val toolStart = start(2, """{"type":"tool_use","id":"toolu_1","name":"Read","input":{}}""")
    private val toolInput = delta(2, """{"type":"input_json_delta","partial_json":"SECRET-INPUT"}""")
    private val stop = frame("message_delta", """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""") +
        frame("message_stop", """{"type":"message_stop"}""")

    private fun drain(follow: LiveFollow): List<String> =
        generateSequence { follow.events.tryReceive().getOrNull() }.toList()

    private fun data(event: String): JsonObject =
        Json.parseToJsonElement(event.lines()[1].removePrefix("data: ")).jsonObject

    private fun name(event: String): String = event.lines()[0].removePrefix("event: ")

    @Test
    fun `a session never seen says hello with no turn in flight`() {
        val follow = hub.follow("nobody") {}
        val expected = buildJsonObject {
            put("session", "nobody")
            put("live", buildJsonArray {})
        }
        assertEquals(expected, data(follow.hello))
        assertTrue(drain(follow).isEmpty())
    }

    @Test
    fun `a follower gets the turn, its thinking, text and tool start in order, and its end, and no secret`() {
        val follow = hub.follow("s1") {}
        val reply = hub.begin(facts())
        listOf(opening, thinkingStart, thought("a"), thought("b"), signature, textStart, word("hi"), word(" there"))
            .forEach(reply::frame)
        listOf(toolStart, toolInput, stop).forEach(reply::frame)
        val events = drain(follow)
        assertEquals(listOf("turn", "thinking", "thinking", "text", "text", "tool", "end"), events.map(::name))
        val turn = data(events[0])
        assertEquals("msg_1", turn["message"]!!.toString().trim('"'))
        val end = buildJsonObject {
            put("turn", reply.id)
            put("stop_reason", "tool_use")
            put("error", null as String?)
        }
        assertEquals(end, data(events.last()))
        val all = events.joinToString("")
        assertFalse("SECRET" in all)
        // a turn's end does not end the follower: the next turn comes on the same stream
        val next = hub.begin(facts())
        next.frame(opening)
        assertEquals(listOf("turn"), drain(follow).map(::name))
    }

    @Test
    fun `a follower that joins mid-reply gets each block so far at once, then the live deltas`() {
        val reply = hub.begin(facts())
        listOf(opening, thinkingStart, thought("a"), thought("b"), textStart, word("one"), word(" two"))
            .forEach(reply::frame)
        val follow = hub.follow("s1") {}
        assertEquals("[\"${reply.id}\"]", data(follow.hello)["live"].toString())
        reply.frame(word(" three"))
        reply.frame(stop)
        val events = drain(follow)
        assertEquals(listOf("turn", "thinking", "text", "text", "end"), events.map(::name))
        assertEquals("\"ab\"", data(events[1])["delta"].toString())
        assertEquals("\"one two\"", data(events[2])["delta"].toString())
        assertEquals("\" three\"", data(events[3])["delta"].toString())
        // ended: a new follower's hello names no turn in flight
        assertEquals("[]", data(hub.follow("s1") {}.hello)["live"].toString())
    }

    @Test
    fun `another session's turns never show, and a subagent's turn names its agent`() {
        val follow = hub.follow("s1") {}
        hub.begin(facts(session = "s2")).frame(opening)
        val sub = hub.begin(facts(agent = "agent-7"))
        sub.frame(opening)
        val events = drain(follow)
        assertEquals(1, events.size)
        assertEquals("\"agent-7\"", data(events[0])["agent"].toString())
    }

    @Test
    fun `a turn that ends with neither a stop nor an error ends as cut, once`() {
        val follow = hub.follow("s1") {}
        val reply = hub.begin(facts())
        listOf(opening, textStart, word("half")).forEach(reply::frame)
        reply.close(errorFollows = false)
        reply.settle()
        val ends = drain(follow).filter { name(it) == "end" }
        assertEquals(1, ends.size)
        assertEquals("\"cut\"", data(ends[0])["error"].toString())
    }

    @Test
    fun `a follower that falls too far behind is dropped, and can follow again`() {
        var overflowed = false
        val stalled = hub.follow("s1") { overflowed = true }
        val reply = hub.begin(facts())
        listOf(opening, textStart).forEach(reply::frame)
        repeat(FOLLOWER_QUEUE_EVENTS + 10) { reply.frame(word("w$it")) }
        assertTrue(overflowed)
        assertTrue(stalled.events.tryReceive().isClosed)
        val again = hub.follow("s1") {}
        assertEquals("[\"${reply.id}\"]", data(again.hello)["live"].toString())
    }

    @Test
    fun `a non-stream turn shows its whole answer when it settles, never in flight`() {
        val follow = hub.follow("s1") {}
        val reply = hub.begin(facts())
        val answer = Json.parseToJsonElement(
            """{"id":"msg_9","type":"message","stop_reason":"end_turn","content":[
              {"type":"thinking","thinking":"hmm","signature":"SECRET-SIG"},{"type":"text","text":"done"},
              {"type":"tool_use","id":"toolu_9","name":"Read","input":{"p":"SECRET-INPUT"}}]}""",
        ).jsonObject
        reply.answeredBy { answer }
        reply.close(errorFollows = false)
        assertTrue(drain(follow).isEmpty())
        reply.settle()
        val events = drain(follow)
        assertEquals(listOf("turn", "thinking", "text", "tool", "end"), events.map(::name))
        assertEquals("\"msg_9\"", data(events[0])["message"].toString())
        assertEquals("\"end_turn\"", data(events.last())["stop_reason"].toString())
        assertFalse("SECRET" in events.joinToString(""))
        assertEquals("[]", data(hub.follow("s1") {}.hello)["live"].toString())
    }
}
