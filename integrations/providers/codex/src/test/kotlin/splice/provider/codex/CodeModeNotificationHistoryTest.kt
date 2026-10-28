package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.provider.codex.history.CodeModeLedgerEntry
import splice.provider.codex.history.CodeModeUpstreamHistory
import splice.provider.codex.history.CodeModeUpstreamLedger
import splice.provider.codex.history.durable.CodeModeHistoryStorage
import splice.upstream.RoundBody
import splice.upstream.codemode.NativeCodeModeNotificationDelivery
import java.nio.file.Path

class CodeModeNotificationHistoryTest {
    @Test
    fun `late notifications extend only their captured branch without consuming the future model response`(
        @TempDir tmp: Path,
    ) {
        CodeModeNotificationFixture(tmp).use { fixture ->
            val ledger = ledger(fixture)
            val input = input(fixture)
            val first = ledger.record(fixture.record.key, CodeModeLedgerEntry(emptyList(), input, emptyList()))
            val sibling = ledger.record(fixture.record.key, CodeModeLedgerEntry(emptyList(), emptyList(), emptyList()))
            val notice = notice(fixture, "same notification")
            fixture.record.progress.nativeNotifications = listOf(notice, notice)
            ledger.inject(fixture.record.key, first, fixture.record)
            ledger.inject(fixture.record.key, first, fixture.record)
            val model = buildJsonObject { put("role", "assistant"); put("content", "actual later model output") }
            ledger.output(fixture.record.key, first, listOf(model), emptyList())
            val reloaded = ledger(fixture).entries(fixture.record.key)
            assertEquals(input + notice + notice + model, reloaded.single { it.id == first }.sent)
            assertEquals(emptyList<JsonElement>(), reloaded.single { it.id == sibling }.sent)
            assertEquals(true, reloaded.single { it.id == first }.outputRecorded)
        }
    }

    @Test
    fun `notification injection and later capture of a previously built body preserve every accepted occurrence`(
        @TempDir tmp: Path,
    ) = runTest {
        CodeModeNotificationFixture(tmp).use { fixture ->
            val history = CodeModeUpstreamHistory(Json, fixture.config.state)
            val context = fixture.first
            context.completed += fixture.record
            val input = input(fixture)
            val body = CodeModeBody(RoundBody.Tree(buildJsonObject { put("input", JsonArray(input)) }), Json)
            history.sent(context, body)
            val routing = fixture.registry.notifications
            val owner = routing.open(context, history.notifications)
            val receiver = routing.receiver(fixture.record, context, fixture.conversation)
            try {
                repeat(2) {
                    assertEquals(
                        NativeCodeModeNotificationDelivery.Delivered,
                        receiver.deliver(fixture.notification("same")),
                    )
                }
                // The model post may have been built before either callback arrived. Its captured continuation
                // must still retain accepted input, without changing the bytes already dispatched.
                history.sent(context, body)
                history.notifications.inject(context, fixture.record)
                val saved = ledger(fixture).entries(context.key).first()
                assertEquals(input + notice(fixture, "same") + notice(fixture, "same"), saved.sent)
                assertEquals(input, body.request?.second?.toList())
            } finally {
                routing.close(owner)
            }
        }
    }

    private fun input(fixture: CodeModeNotificationFixture): List<JsonElement> =
        checkNotNull(fixture.first.client?.request).second + listOf(
            buildJsonObject {
                put("type", "custom_tool_call"); put("name", "exec")
                put("call_id", fixture.record.origin.outerCallId); put("input", fixture.record.origin.source)
            },
            buildJsonObject {
                put("type", "custom_tool_call_output"); put("call_id", fixture.record.origin.outerCallId)
                put("output", "Script running with cell ID cell-1\nWall time 0.0 seconds\nOutput:\n")
            },
        )

    private fun notice(fixture: CodeModeNotificationFixture, text: String): JsonElement = buildJsonObject {
        put("type", "custom_tool_call_output"); put("call_id", fixture.record.origin.outerCallId)
        put("name", "exec"); put("output", text)
    }

    private fun ledger(fixture: CodeModeNotificationFixture): CodeModeUpstreamLedger = CodeModeUpstreamLedger(
        storage = CodeModeHistoryStorage(fixture.config.state, Json, NOTIFICATION_HISTORY_TEST_BYTES),
    )
}

// why: these journal branches are small; a fixed one-megabyte bound exposes accidental unbounded capture growth.
private const val NOTIFICATION_HISTORY_TEST_BYTES = 1_048_576L
