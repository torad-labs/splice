// NEW: a code-mode round that arrives as a tree is read as that tree, and everything it persists and posts
// is what the same round gave when it arrived as the tree's rendered text.
package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome
import splice.core.util.JsonWire
import splice.provider.codex.state.CodeModeTurnIdentity
import splice.upstream.InterceptedRoundPost
import splice.upstream.RoundBody
import splice.upstream.RoundBodyInterceptor
import splice.upstream.RoundBodyPost
import splice.upstream.codemode.CodeModeStep
import splice.upstream.sse.WireSink
import java.security.MessageDigest
import java.util.HexFormat

/** Escapes, a slash, a control character, Latin-1, the euro sign, CJK and an astral emoji. */
private const val HARD_TEXT = """start café € 漢字 🐉 \"quoted\" back\\slash\n tab\t ctl\u0001 slash\/"""
private const val NUMBERS = """[1.0,1e5,-0,12345678901234567890,0.1e-7]"""

internal class CodeModeRoundTreeTest : CodeModeBridgeTestSupport() {
    private fun tree(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun request(items: String): JsonObject = tree(
        """{"model":"gpt-6-astra","n":$NUMBERS,"input":[{"role":"developer","content":"s"},$items]}""",
    )

    private val start = """{"role":"user","content":"$HARD_TEXT"}"""

    /** The persisted spelling, computed here without the production digest: SHA-256 of the UTF-8, lowercase hex. */
    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    /** Keeps each body a round posts in the form it was handed over, taking trees as the turn's post does. */
    private inner class CapturingPost(private val outcome: () -> TurnOutcome) : InterceptedRoundPost, RoundBodyPost {
        val posted = mutableListOf<RoundBody>()

        override suspend fun invoke(bodyJson: String): TurnOutcome = post(RoundBody.Text(bodyJson))

        override suspend fun post(body: RoundBody): TurnOutcome {
            posted += body
            return outcome()
        }

        override suspend fun postInto(body: RoundBody, sink: WireSink): TurnOutcome = post(body)
    }

    /** The bridge's interceptor through the entry the head uses for a round it holds as a tree. */
    private fun treeEntry(manager: CodexCodeModeBridge): RoundBodyInterceptor =
        manager.interceptor(turn(), disableParallel = false) as RoundBodyInterceptor

    @Test
    fun `a tree's request digest is the digest its rendered text had, on hard text`() {
        val identity = CodeModeTurnIdentity()
        val lone = JsonObject(mapOf("input" to JsonArray(listOf(JsonPrimitive("before \uD800 after é")))))
        listOf(request(start), tree("""{"input":[],"n":$NUMBERS}"""), lone).forEach { body ->
            val text = JsonWire.string(body)
            assertEquals(sha256(text), identity.digest(text), "the persisted spelling for $text")
            assertEquals(identity.digest(text), identity.digest(RoundBody.Tree(body)), "tree digest for $text")
            assertEquals(identity.digest(text), identity.digest(RoundBody.Text(text)), "text digest for $text")
        }
    }

    /** Today's build reached code mode with the tree's text, so that is what its records hold. A fresh
     *  bridge over the same state then serves the identical request, arriving as the tree, from the
     *  record's issued step, without posting it. */
    @Test
    fun `a record written from a round's text is found by the same round arriving as a tree`() = runTest {
        val script = ArrayDeque(
            listOf(CodeModeStep.Calls(listOf(call("first", "Read"))), CodeModeStep.Completed("first")),
        )
        val writer = bridge(QueuedRuntime(ArrayDeque(listOf(script))))
        val first = request(start)
        val firstText = JsonWire.string(first)
        val issued = RecordingSink()
        try {
            writer.interceptor(turn(), disableParallel = false).intercept(firstText, issued) { outerOutcome("outer-1") }
        } finally {
            writer.onHeadStop()
        }
        val id = issued.tools.single().id
        val record = stateFiles.records().single()
        val step = record.getValue("issued").jsonArray.first().jsonObject
        assertEquals(sha256(firstText), step.getValue("requestDigest").jsonPrimitive.content)

        val reader = bridge(ScriptedRuntime(ArrayDeque()))
        val replayed = RecordingSink()
        try {
            treeEntry(reader).intercept(RoundBody.Tree(first), replayed) {
                error("an identical request is served from its record and never posted")
            }
        } finally {
            reader.onHeadStop()
        }
        assertEquals(listOf(id), replayed.tools.map { it.id })
    }

    /** No record touches it, so the body goes upstream as it arrived: the tree itself, and text in its
     *  own spelling even where that is not JsonWire's. */
    @Test
    fun `an unchanged history posts the body it arrived as, byte for byte`() = runTest {
        val manager = bridge(ScriptedRuntime(ArrayDeque()))
        try {
            val body = request(start)
            val treePost = CapturingPost { completedOutcome() }
            treeEntry(manager)
                .intercept(RoundBody.Tree(body), RecordingSink(), treePost)
            val tree = treePost.posted.single()
            assertSame(body, (tree as? RoundBody.Tree)?.element, "an unchanged tree goes out as the same tree")
            assertArrayEquals(JsonWire.string(body).toByteArray(Charsets.UTF_8), tree.bytes())

            val spaced = """{ "input" : [ {"role":"developer","content":"s"} , $start ] , "n" : $NUMBERS }"""
            val textPost = CapturingPost { completedOutcome() }
            manager.interceptor(turn(), disableParallel = false).intercept(spaced, RecordingSink(), textPost)
            assertArrayEquals(spaced.toByteArray(Charsets.UTF_8), textPost.posted.single().bytes())
        } finally {
            manager.onHeadStop()
        }
    }

    /** A completed record rewrites the next request; the tree and its text post the same bytes. */
    @Test
    fun `a rewritten history posts the bytes its text spelling posted`() = runTest {
        val script = ArrayDeque(
            listOf(CodeModeStep.Calls(listOf(call("first", "Read"))), CodeModeStep.Completed("first")),
        )
        val manager = bridge(QueuedRuntime(ArrayDeque(listOf(script))))
        try {
            val issued = RecordingSink()
            manager.interceptor(turn(), disableParallel = false)
                .intercept(JsonWire.string(request(start)), issued) { outerOutcome("outer-1") }
            val id = issued.tools.single().id
            val done = "$start,${callback(id)}"
            manager.interceptor(turn(id, "A"), disableParallel = false)
                .intercept(JsonWire.string(request(done)), RecordingSink()) { completedOutcome() }
            val next = request("$done," + """{"role":"user","content":"next"}""")

            val textPost = CapturingPost { completedOutcome() }
            manager.interceptor(turn(), disableParallel = false)
                .intercept(JsonWire.string(next), RecordingSink(), textPost)
            val treePost = CapturingPost { completedOutcome() }
            treeEntry(manager)
                .intercept(RoundBody.Tree(next), RecordingSink(), treePost)

            val expected = textPost.posted.single().bytes()
            assertNotEquals(JsonWire.string(next), String(expected, Charsets.UTF_8), "the fixture must rewrite")
            assertArrayEquals(expected, treePost.posted.single().bytes())
        } finally {
            manager.onHeadStop()
        }
    }

    /** tool_search pairs leave the posted body the same way whichever form the round arrived in. */
    @Test
    fun `a history with tool_search pairs posts the same bytes from a tree as from its text`() = runTest {
        val searched = request(
            """{"type":"tool_search_call","call_id":"ts-1","execution":"client","arguments":{"query":"LSP"}},""" +
                """{"type":"tool_search_output","call_id":"ts-1","tools":[]},$start""",
        )
        val manager = bridge(ScriptedRuntime(ArrayDeque()))
        try {
            val textPost = CapturingPost { completedOutcome() }
            manager.interceptor(turn(), disableParallel = false)
                .intercept(JsonWire.string(searched), RecordingSink(), textPost)
            val treePost = CapturingPost { completedOutcome() }
            treeEntry(manager)
                .intercept(RoundBody.Tree(searched), RecordingSink(), treePost)

            val expected = textPost.posted.single().bytes()
            assertEquals(listOf("developer", "user"), roles(expected))
            assertArrayEquals(expected, treePost.posted.single().bytes())
        } finally {
            manager.onHeadStop()
        }
    }

    private fun roles(bytes: ByteArray): List<String> =
        Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject.getValue("input").jsonArray
            .map { it.jsonObject["role"]?.jsonPrimitive?.content.orEmpty() }

    private fun callback(id: String): String =
        """{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"},""" +
            """{"type":"function_call_output","call_id":"$id","output":"A"}"""
}
