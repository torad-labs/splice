package splice.dialect.responses.websocket

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.reasoning.RequestEncryptedReasoning
import splice.dialect.responses.request.BuildOptions
import splice.dialect.responses.request.ModelIds
import splice.dialect.responses.request.ReasoningHandoff
import splice.dialect.responses.request.RequestedReasoning
import splice.dialect.responses.request.ResponsesRequestBuilder
import splice.dialect.responses.request.responsesRequestJson
import splice.dialect.responses.websocket.ResponsesWsIdentity.PendingCommit

private val CODEX = ResponsesQuirks(providerTag = "claudex", lite = ResponsesLiteQuirks())
private const val KEY = "conv-1"
private const val GEN = 7L
private val SERVER_CALL_2: JsonObject = responsesRequestJson.parseToJsonElement(
    """{"type":"function_call","call_id":"call_2","name":"read","arguments":"{\"p\":\"2\"}"}""",
).jsonObject
private val CALL_2_EVIDENCE = WsServerEvidence(
    calls = mapOf("call_2" to SERVER_CALL_2),
    reasoning = mapOf("rs_env-call_2" to "env-call_2"),
)

private fun opts() = BuildOptions(
    compact = false,
    models = ModelIds(original = "claude-codex--gpt-5.6-sol", upstream = "gpt-5.6-sol"),
    reasoning = RequestedReasoning(effort = null, summary = null, display = ReasoningDisplay.TEXT),
    handoff = ReasoningHandoff(
        replay = InjectPriorReasoning(false),
        includeEncrypted = RequestEncryptedReasoning(true),
        decode = { data ->
            buildJsonObject {
                put("type", JsonPrimitive("reasoning"))
                put("id", JsonPrimitive("rs_$data"))
                put("encrypted_content", JsonPrimitive(data))
            }
        },
        lookup = { id -> listOf("env-$id") },
    ),
)

private fun build(json: String): JsonObject = AnthropicParse.parseAnthropicBody(json).let {
    ResponsesRequestBuilder(CODEX).build(it.typed, it.raw, opts()).req
}

private fun convo(rounds: Int): String {
    val msgs = mutableListOf("""{"role":"user","content":"start"}""")
    for (i in 1..rounds) {
        msgs += """{"role":"assistant","content":[{"type":"tool_use","id":"call_$i","name":"read","input":{"p":""" +
            """"$i"}}]}"""
        msgs += """{"role":"user","content":[{"type":"tool_result","tool_use_id":"call_$i","content":"out$i"}]}"""
    }
    return """{"model":"m","messages":[${msgs.joinToString(",")}]}"""
}

class ResponsesWsSessionPrefillTest {
    /** Clearing during a round must fence off its late terminal commit. */
    @Test
    fun `a commit built before an invalidation is discarded, not resurrected`() {
        val session = ResponsesWsSession()
        val request = build(convo(1))
        val epochAtSend = session.epochOf(KEY)
        session.cleared(KEY)
        session.completed(
            KEY,
            PendingCommit(request, GEN, epochAtSend),
            "resp_1",
            evidence = CALL_2_EVIDENCE,
        )

        assertFalse(
            session.frameFor(KEY, build(convo(2)), GEN).chained,
            "a stale-epoch commit must not resurrect a chain",
        )
    }

    /** One conversation's invalidation cannot void another conversation's in-flight commit. */
    @Test
    fun `another conversation's clear does not void an in-flight commit`() {
        val session = ResponsesWsSession()
        val request = build(convo(1))
        val epochAtSend = session.epochOf(KEY)
        session.cleared("conv-unrelated")
        session.completed(
            KEY,
            PendingCommit(request, GEN, epochAtSend),
            "resp_1",
            evidence = CALL_2_EVIDENCE,
        )

        assertTrue(
            session.frameFor(KEY, build(convo(2)), GEN).chained,
            "the unrelated clear does not fence this conversation's commit",
        )
    }

    @Test
    fun `a commit under the current epoch still chains`() {
        val session = ResponsesWsSession()
        session.cleared(KEY)
        val request = build(convo(1))
        session.completed(
            KEY,
            PendingCommit(request, GEN, session.epochOf(KEY)),
            "resp_1",
            evidence = CALL_2_EVIDENCE,
        )

        assertTrue(
            session.frameFor(KEY, build(convo(2)), GEN).chained,
        )
    }

    @Test
    fun `a late stale terminal cannot remove a newer committed chain`() {
        val session = ResponsesWsSession()
        val oldRequest = build(convo(1))
        val newerRequest = build(convo(1))
        val sharedEpoch = session.epochOf(KEY)

        session.completed(
            KEY,
            PendingCommit(newerRequest, GEN, sharedEpoch),
            "resp_new",
            evidence = CALL_2_EVIDENCE,
        )
        session.completed(
            KEY,
            PendingCommit(oldRequest, GEN, sharedEpoch),
            "resp_old",
        )

        val next = session.frameFor(
            KEY,
            build(convo(2)),
            GEN,
        )
        assertTrue(next.chained)
        assertTrue(next.json.contains("resp_new"), "the stale terminal cannot replace the newer chain")
    }

    @Test
    fun `a busy prefill clear cannot erase a chain committed after its attempt began`() {
        val session = ResponsesWsSession()
        val request = build(convo(1))
        val attemptEpoch = session.epochOf(KEY)
        session.completed(
            KEY,
            PendingCommit(request, GEN, attemptEpoch),
            "resp_new",
            evidence = CALL_2_EVIDENCE,
        )

        assertFalse(session.clearedIfCurrent(KEY, attemptEpoch))
        assertTrue(
            session.frameFor(KEY, build(convo(2)), GEN).chained,
        )
    }
}
