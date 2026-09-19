// NEW: V4-130 — which SendMessage calls MessageEdges reports, and which requests ActivityLabel counts
// as a near miss. The through-a-real-head test is HeadEventsTest's last case; these are the shapes a
// live head sees too rarely to leave to it: an old call further back in the history, a call with no
// usable `to`, a request with no session header, and the near-miss boundary.
package console.v4130

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.wire.AnthropicRequest
import splice.gateway.head.ActivityLabel
import splice.gateway.head.HeadEvents
import splice.gateway.head.HeadLifecycle
import splice.gateway.head.MessageEdges

private class Edges : HeadEvents {
    val sent = mutableListOf<String>()

    override fun lifecycle(state: HeadLifecycle): Unit = Unit

    override fun turnStarted(session: String?): Unit = Unit

    override fun turnEnded(perfRowId: String, outcome: String): Unit = Unit

    override fun accountSwitched(from: String?, to: String): Unit = Unit

    override fun messageSent(session: String, to: String, toolUseId: String) {
        sent += "$session $to $toolUseId"
    }

    override fun activityLabel(session: String?, label: String): Unit = Unit

    override fun labelQueryUpstream(session: String?): Unit = Unit
}

class MessageEdgesTest {

    private fun request(vararg messages: String): AnthropicRequest =
        AnthropicParse.parseAnthropicBody("""{"model":"m","messages":[${messages.joinToString(",")}]}""").typed

    private fun send(id: String, input: String) =
        """{"type":"tool_use","id":"$id","name":"SendMessage","input":$input}"""

    private fun assistant(vararg blocks: String) = """{"role":"assistant","content":[${blocks.joinToString(",")}]}"""

    private val user = """{"role":"user","content":"go"}"""

    @Test
    fun `only the last assistant message is read, and only calls with a usable to`() {
        val events = Edges()
        val edges = MessageEdges(events)
        edges.observe(
            "s-1",
            request(
                user,
                assistant(send("toolu_old", """{"to":"old-peer"}""")),
                user,
                assistant(
                    send("toolu_1", """{"to":"uds:/run/a.sock","message":"hi"}"""),
                    send("toolu_2", """{"to":"  "}"""),
                    send("toolu_3", """{"to":7}"""),
                    """{"type":"tool_use","id":"toolu_4","name":"Read","input":{"to":"not-a-send"}}""",
                ),
                user,
            ),
        )
        assertEquals(listOf("s-1 uds:/run/a.sock toolu_1"), events.sent)
    }

    @Test
    fun `a request without a session header reports nothing, and a later one with it still can`() {
        val events = Edges()
        val edges = MessageEdges(events)
        val carrying = request(user, assistant(send("toolu_1", """{"to":"peer"}""")), user)
        edges.observe(null, carrying)
        edges.observe("s-1", carrying)
        edges.observe("s-1", carrying)
        assertEquals(listOf("s-1 peer toolu_1"), events.sent, "the first sight WITH a sender is the one reported")
    }

    @Test
    fun `a reworded side query is a near miss, and neither the exact query nor ordinary text is`() {
        val label = ActivityLabel()
        fun last(text: String) = request(user, """{"role":"user","content":"$text"}""")
        assertTrue(label.looksLikeSideQuery(last("Summarize your MOST RECENT ACTION in Present Tense.")))
        assertFalse(label.looksLikeSideQuery(last("what was your most recent action?")), "one phrase is not enough")
        assertFalse(label.looksLikeSideQuery(last("fix the bug")))
        val exact = last("Describe your most recent action in 3-5 words using present tense (-ing).")
        assertTrue(
            label.labelFor(exact) != null,
            "the exact opening is answered, so it never reaches the near-miss test",
        )
    }
}
