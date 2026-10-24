package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.perf.InputDigest
import splice.provider.codex.history.CodeModeClientPrefix
import splice.provider.codex.history.CodeModeOwnership
import splice.provider.codex.state.CodeModeExtraContent
import splice.provider.codex.state.CodeModeHistoryAnchor
import splice.provider.codex.state.CodeModeReplayAnchors
import splice.upstream.codemode.CodeModeResult

/** Sanitized structural replay of the Oct 24 burst: the client retains its own wider prefix and appends only
 * results. The canonical model baseline has a different logical width; its offsets cannot classify client edits. */
class CodeModeAcknowledgedReplayTest {
    private val codec = CodexCodeModeHistoryCodec(Json)
    private val extras = CodeModeExtraContent(codec, CodeModeOwnership(codec))
    private val user = item("""{"role":"user","content":"continue"}""")
    private val historical =
        item("""{"type":"reasoning","id":"rs-history","summary":[],"encrypted_content":"private"}""")
    private val baseline = listOf(user) + callback("old") + historical + user

    @Test
    fun `callback-only replay of the acknowledged client prefix is never steering`() {
        val record = record()
        val client = JsonArray(baseline + callback("one") + callback("two"))
        assertEquals(
            CodeModeExtra.NONE,
            extras.of(client, record, emptyMap()),
            extras.describe(client, record, emptyMap(), CodeModeExtra.NONE),
        )
    }

    @Test
    fun `acknowledged replay still distinguishes a new user message and an edited old result`() {
        val record = record()
        val callbacks = callback("one") + callback("two")
        assertEquals(CodeModeExtra.STEERING, extras.of(JsonArray(baseline + callbacks + user), record, emptyMap()))
        val edited = baseline.map {
            if (it == callback(
                    "old",
                )[1]
            ) {
                item("""{"type":"function_call_output","call_id":"old","output":"edited"}""")
            } else {
                it
            }
        }
        assertEquals(CodeModeExtra.STEERING, extras.of(JsonArray(edited + callbacks), record, emptyMap()))
    }

    @Test
    fun `an unknown native item after the acknowledged prefix remains steering`() {
        val unknown = item("""{"type":"reasoning","id":"rs-new","summary":[],"encrypted_content":"other"}""")
        assertEquals(
            CodeModeExtra.STEERING,
            extras.of(JsonArray(baseline + unknown + callback("one") + callback("two")), record(), emptyMap()),
        )
    }

    @Test
    fun `the client acknowledgement survives a saved record round trip`() {
        val saved = Json.decodeFromString<CodeModeRecordSnapshot>(
            Json.encodeToString(CodeModeRecordSnapshot.serializer(), record().snapshot()),
        )
        val restored = saved.restore()
        val client = JsonArray(baseline + callback("one") + callback("two"))
        assertEquals(CodeModeExtra.NONE, extras.of(client, restored, emptyMap()))
    }

    private fun record(): CodeModeRecord = CodeModeRecord(
        id = "record",
        key = "conversation",
        phase = CodeModePhase.ACTIVE,
        origin = CodeModeOrigin(
            JsonObject(emptyMap()),
            "exec",
            "source",
            CodeModeBaseline(1, "", 1, "", CODE_MODE_METADATA_VERSION),
        ),
        progress = CodeModeProgress(updatedAt = 1, lastDigest = "request"),
        carry = CodeModeNativeContinuity(
            listOf(CodeModeNativeSegment(1, listOf(historical))),
            emptyList(),
            emptyList(),
        ),
    ).also { record ->
        record.replayAnchors = CodeModeReplayAnchors(
            CodeModeHistoryAnchor(InputDigest.hex(user), 0),
            emptyMap(),
            client = CodeModeClientPrefix(baseline, splice.core.perf.InputItemDigests()).start(),
        )
        val calls = listOf("one", "two").map { id -> CodeModePending(id, id, "Read", JsonObject(emptyMap()), true) }
        record.issued += CodeModeIssuedStep("request", calls)
        record.progress.pending += calls
        record.accepted.accept(calls.associate { it.clientId to CodeModeResult(it.clientId, "result") }, emptyMap())
    }

    private fun callback(id: String): List<JsonElement> = listOf(
        item("""{"type":"function_call","call_id":"$id","name":"Read","arguments":"{}"}"""),
        item("""{"type":"function_call_output","call_id":"$id","output":"result"}"""),
    )

    private fun item(text: String): JsonElement = Json.parseToJsonElement(text)
}
