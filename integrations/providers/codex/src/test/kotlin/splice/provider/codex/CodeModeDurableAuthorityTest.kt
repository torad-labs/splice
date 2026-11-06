package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.ClientWire
import splice.core.failure.CodeModeStorageFault
import splice.core.failure.FailureCause
import splice.core.failure.HeadVoice
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.RequestFacts
import splice.core.perf.InputDigest
import splice.core.turn.ErrorType
import splice.provider.codex.history.CodeModeCallbackProof
import splice.provider.codex.history.CodeModeCallbackReceipt
import splice.provider.codex.history.CodeModeClientDelta
import splice.provider.codex.history.CodeModeLedgerEntry
import splice.provider.codex.history.CodeModeUpstreamLedger
import splice.provider.codex.history.durable.CodeModeHistoryStorage
import java.nio.file.Files

class CodeModeDurableAuthorityTest : CodeModeBridgeTestSupport() {
    private val json = Json { encodeDefaults = true }
    private val codec = CodexCodeModeHistoryCodec(json)

    @Test
    fun `cache eviction and restart preserve the exact model items of every conversation and side branch`() {
        val ledger = ledger()
        val native = item("""{"type":"reasoning","id":"rs-hidden","encrypted_content":"private","summary":[]}""")
        for (conversation in 0..40) {
            for (branch in 0..7) {
                val covered = listOf(user("conversation-$conversation"), user("branch-$branch"))
                ledger.record(
                    "conversation-$conversation",
                    CodeModeLedgerEntry(covered, listOf(native) + covered, emptyList()),
                )
            }
        }
        val restored = ledger()
        for (conversation in 0..40) {
            val posts = restored.entries("conversation-$conversation")
            assertEquals(
                (7 downTo 0).map { branch ->
                    listOf(
                        native,
                        user("conversation-$conversation"),
                        user("branch-$branch"),
                    )
                },
                posts.map {
                    it.sent
                },
            )
            assertEquals(8L, restored.newest("conversation-$conversation"))
        }
    }

    @Test
    fun `a response keeps native boundaries while acknowledging merged display blocks and hidden reasoning`() {
        val ledger = ledger()
        val covered = listOf(user("begin"))
        val native = item("""{"type":"reasoning","id":"rs-output","summary":[],"encrypted_content":"private"}""")
        val first = assistant("Same words. ")
        val second = assistant("Same words.")
        ledger.record("key", CodeModeLedgerEntry(covered, covered, emptyList()))
        ledger.output("key", 1L, listOf(native, first, second), listOf(first, second))
        val entry = ledger().entries("key").single()
        val client = covered + assistant("Same words. Same words.") + user("next")
        assertEquals(listOf(user("next")), CodeModeClientDelta(codec).after(entry, client))
        assertEquals(covered + listOf(native, first, second), entry.sent)
    }

    @Test
    fun `a corrupted authority fails closed instead of rebuilding from client prose`() {
        ledger().record("key", CodeModeLedgerEntry(listOf(user("begin")), listOf(user("begin")), emptyList()))
        val file = Files.list(stateLocation().dir.resolve("history")).use {
            it.filter { file -> file.fileName.toString().endsWith(".jsonl") }.findFirst().orElseThrow()
        }
        Files.writeString(file, "{broken")
        val failure = assertThrows(CodeModePersistenceException::class.java) { ledger().entries("key") }
        assertStorageRefusal(failure, CodeModeStorageFault.INVALID, "invalid")
        assertEquals("{broken", Files.readString(file))
    }

    @Test
    fun `a failed bounded save leaves the previously committed authority recoverable`() {
        val committed = CodeModeLedgerEntry(listOf(user("begin")), listOf(user("begin")), emptyList())
        ledger().record("key", committed)
        val file = stateLocation().dir.resolve("history").resolve(InputDigest.hex("key") + ".jsonl")
        val before = Files.readAllBytes(file)
        val bounded = CodeModeUpstreamLedger(storage = CodeModeHistoryStorage(stateLocation(), json, 1024))
        repeat(2) {
            val failure = assertThrows(CodeModePersistenceException::class.java) {
                bounded.record(
                    "key",
                    CodeModeLedgerEntry(listOf(user("more")), listOf(user("x".repeat(4000))), emptyList()),
                )
            }
            assertStorageRefusal(failure, CodeModeStorageFault.LIMIT, "storage limit")
            assertTrue("limit=1024 bytes" in failure.message)
            assertTrue("source was not rerun" in failure.message)
            assertArrayEquals(before, Files.readAllBytes(file))
        }
        assertEquals(committed.sent, ledger().entries("key").single().sent)
        assertEquals(1L, ledger().newest("key"))
    }

    @Test
    fun `cleared image results retain durable model authority after execution records are absent`() {
        val call = item("""{"type":"function_call","call_id":"callback","name":"Read","arguments":"{}"}""")
        val original = item("""{"type":"function_call_output","call_id":"callback","output":"accepted"}""")
        val image =
            item("""{"role":"user","content":[{"type":"input_image","image_url":"data:image/png;base64,AA=="}]}""")
        val covered = listOf(user("begin"), call, original, image)
        val model =
            listOf(
                user("begin"),
                item("""{"type":"custom_tool_call_output","call_id":"exec","output":"done"}"""),
                image,
            )
        val receipt = CodeModeCallbackReceipt(InputDigest.hex(call), InputDigest.hex(original), listOf(image), false)
        ledger().record(
            "image",
            CodeModeLedgerEntry(covered, model, emptyList(), callbacks = mapOf("callback" to receipt)),
        )
        val restored = ledger().entries("image").single()
        val marker =
            item(
                """{"type":"function_call_output","call_id":"callback","output":"[Old tool result content cleared]"}""",
            )
        val received = listOf(user("begin"), call, marker, user("next"))
        val proof = CodeModeCallbackProof(candidates = setOf("callback"), errors = mapOf("callback" to false))
        assertEquals(listOf(user("next")), CodeModeClientDelta(codec).after(restored, received, proof))
        assertEquals(model, restored.sent)
        assertNull(CodeModeClientDelta(codec).after(restored, received, proof.copy(errors = mapOf("callback" to true))))
        val altered = received.map { value ->
            if (value == call) {
                item(
                    """{"type":"function_call","call_id":"callback","name":"Edit","arguments":"{}"}""",
                )
            } else {
                value
            }
        }
        assertNull(CodeModeClientDelta(codec).after(restored, altered, proof))
    }

    @Test
    fun `a lost unacknowledged display reply never erases accepted native model output`() {
        val covered = listOf(user("begin"))
        val native = item("""{"type":"reasoning","id":"rs-lost","encrypted_content":"private","summary":[]}""")
        val answer = assistant("the reply the client never kept")
        val entry = CodeModeLedgerEntry(covered, covered + native + answer, listOf(answer), outputRecorded = true)
        assertEquals(listOf(user("continue")), CodeModeClientDelta(codec).after(entry, covered + user("continue")))
        assertNull(CodeModeClientDelta(codec).after(entry, covered + assistant("edited reply")))
    }

    private fun ledger() = CodeModeUpstreamLedger(
        storage = CodeModeHistoryStorage(stateLocation(), json, 4L * 1024 * 1024),
    )

    private fun assertStorageRefusal(
        failure: CodeModePersistenceException,
        storage: CodeModeStorageFault,
        text: String,
    ) {
        val outcome = failure.outcome()
        assertEquals(storage, (outcome.cause as FailureCause.Splice.Protocol).storage)
        val positions = listOf(outcome.at, Position(Moment.BEFORE_COMMIT, streamBegan = false))
        for (at in positions) {
            for (repeated in listOf(false, true)) {
                val voice = HeadVoice("claudex", null).turn(RequestFacts(false, false, repeated))
                val answer = ClientWire.answer(outcome.cause, at, repeated)
                val line = voice.ending(outcome.cause, at, answer).text
                assertEquals(ErrorType.INVALID_REQUEST, answer.type)
                assertFalse(answer.clientResends)
                assertTrue(text in line, line)
                assertFalse("Send the message again" in line, line)
                assertFalse("did not start" in line, line)
                assertFalse("lost its state" in line, line)
            }
        }
    }
    private fun user(text: String): JsonElement = item("""{"role":"user","content":"$text"}""")
    private fun assistant(text: String): JsonElement = item("""{"role":"assistant","content":"$text"}""")
    private fun item(text: String): JsonElement = json.parseToJsonElement(text)
}
