package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.JsonScalars
import splice.provider.codex.diagnostics.CodeModeGatewayBridgeSink
import splice.provider.codex.stream.notifications.CodeModeNotificationOwner
import splice.upstream.codemode.NativeCodeModeNotificationDelivery
import splice.upstream.sse.WireSink
import java.nio.file.Path
import kotlin.time.Duration.Companion.hours

class CodeModeConversationNotificationsTest {
    @Test
    fun `notifications persist under original exec identity and move to the next active request`(
        @TempDir tmp: Path,
    ) = runTest {
        CodeModeNotificationFixture(tmp).use { fixture ->
            val routing = fixture.registry.notifications
            val owner = routing.open(fixture.first)
            val receiver = routing.receiver(fixture.record, fixture.first, fixture.conversation)
            assertEquals(NativeCodeModeNotificationDelivery.Delivered, receiver.deliver(fixture.notification("first")))
            routing.close(owner)
            val nextSink = CodeModeGatewayBridgeSink()
            val next = fixture.context(nextSink)
            val nextOwner = routing.open(next)
            try {
                assertEquals(
                    NativeCodeModeNotificationDelivery.Delivered,
                    receiver.deliver(fixture.notification("next")),
                )
                assertEquals("first", fixture.firstSink.text.toString())
                assertEquals("next", nextSink.text.toString())
                val saved = fixture.record.progress.nativeNotifications
                assertEquals(listOf("first", "next"), saved.map { JsonScalars.str(it.jsonObject, "output") })
                assertTrue(saved.all { JsonScalars.str(it.jsonObject, "call_id") == fixture.record.origin.outerCallId })
                val reloaded = CodexCodeModeRegistry(fixture.config, Json, 1.hours)
                try {
                    assertEquals(saved, reloaded.recordsFor(fixture.record.key).single().progress.nativeNotifications)
                } finally {
                    reloaded.timed.finish { reloaded.onHeadStop() }
                }
            } finally {
                routing.close(nextOwner)
            }
        }
    }

    @Test
    fun `inactive wrong-session wrong-cell and wrong-call notifications never reach a stale sink`(
        @TempDir tmp: Path,
    ) = runTest {
        CodeModeNotificationFixture(tmp).use { fixture ->
            val routing = fixture.registry.notifications
            val owner = routing.open(fixture.first)
            val receiver = routing.receiver(fixture.record, fixture.first, fixture.conversation)
            val notice = fixture.notification("private progress")
            for (forged in listOf(
                notice.copy(sessionKey = "another"),
                notice.copy(callId = "another"),
                notice.copy(cellId = "another"),
            )) {
                assertTrue(receiver.deliver(forged) is NativeCodeModeNotificationDelivery.Rejected)
            }
            routing.close(owner)
            assertEquals(
                NativeCodeModeNotificationDelivery.Rejected(
                    "failed to inject exec notify message for cell cell-1: no active turn",
                ),
                receiver.deliver(notice),
            )
            assertEquals(
                NativeCodeModeNotificationDelivery.Delivered,
                receiver.deliver(fixture.notification("\u2003\n")),
            )
            assertEquals("", fixture.firstSink.text.toString())
            assertTrue(fixture.record.progress.nativeNotifications.isEmpty())
        }
    }

    @Test
    fun `concurrent branches require a unique owner or an explicit wait binding`(@TempDir tmp: Path) = runTest {
        CodeModeNotificationFixture(tmp).use { fixture ->
            val routing = fixture.registry.notifications
            val original = routing.open(fixture.first)
            val receiver = routing.receiver(fixture.record, fixture.first, fixture.conversation)
            val secondSink = CodeModeGatewayBridgeSink()
            val second = fixture.context(secondSink)
            val secondOwner = routing.open(second)
            val thirdSink = CodeModeGatewayBridgeSink()
            val third = fixture.context(thirdSink)
            val thirdOwner = routing.open(third)
            try {
                assertEquals(
                    NativeCodeModeNotificationDelivery.Delivered,
                    receiver.deliver(fixture.notification("original")),
                )
                routing.close(original)
                assertTrue(
                    receiver.deliver(fixture.notification("ambiguous")) is NativeCodeModeNotificationDelivery.Rejected,
                )
                routing.bind(fixture.conversation, fixture.record.origin.outerCallId, second)
                assertEquals(
                    NativeCodeModeNotificationDelivery.Delivered,
                    receiver.deliver(fixture.notification("wait owner")),
                )
                assertEquals("original", fixture.firstSink.text.toString())
                assertEquals("wait owner", secondSink.text.toString())
                assertEquals("", thirdSink.text.toString())
            } finally {
                routing.close(original); routing.close(secondOwner); routing.close(thirdOwner)
            }
        }
    }

    @Test
    fun `cancelled observation owner expires while the native receiver stays usable for a later request`(
        @TempDir tmp: Path,
    ) = runTest {
        CodeModeNotificationFixture(tmp).use { fixture ->
            val routing = fixture.registry.notifications
            val opened = CompletableDeferred<CodeModeNotificationOwner>()
            val request = launch {
                val owner = routing.open(fixture.first)
                opened.complete(owner)
                try { awaitCancellation() } finally { routing.close(owner) }
            }
            opened.await()
            val receiver = routing.receiver(fixture.record, fixture.first, fixture.conversation)
            request.cancelAndJoin()
            assertTrue(receiver.deliver(fixture.notification("stale")) is NativeCodeModeNotificationDelivery.Rejected)
            val nextSink = CodeModeGatewayBridgeSink()
            val next = routing.open(fixture.context(nextSink))
            try {
                assertEquals(
                    NativeCodeModeNotificationDelivery.Delivered,
                    receiver.deliver(fixture.notification("retained")),
                )
                assertEquals("", fixture.firstSink.text.toString())
                assertEquals("retained", nextSink.text.toString())
            } finally {
                routing.close(next)
            }
        }
    }

    @Test
    fun `owner exit cancels an unfinished sink write after preserving its accepted native history`(
        @TempDir tmp: Path,
    ) = runTest {
        CodeModeNotificationFixture(tmp).use { fixture ->
            val routing = fixture.registry.notifications
            val sink = NotificationWriteBarrier()
            val context = fixture.context(sink)
            val owner = routing.open(context)
            val receiver = routing.receiver(fixture.record, context, fixture.conversation)
            val delivery = async { receiver.deliver(fixture.notification("accepted before owner exit")) }
            sink.entered.await()
            routing.close(owner)
            assertEquals(NativeCodeModeNotificationDelivery.Delivered, delivery.await())
            assertTrue(
                receiver.deliver(
                    fixture.notification("after owner exit"),
                ) is NativeCodeModeNotificationDelivery.Rejected,
            )
            assertEquals(
                listOf("accepted before owner exit"),
                fixture.record.progress.nativeNotifications.map { JsonScalars.str(it.jsonObject, "output") },
            )
        }
    }

    @Test
    fun `native delegate cancellation propagates without retiring the still active request owner`(
        @TempDir tmp: Path,
    ) = runTest {
        CodeModeNotificationFixture(tmp).use { fixture ->
            val routing = fixture.registry.notifications
            val sink = NotificationWriteBarrier()
            val context = fixture.context(sink)
            val owner = routing.open(context)
            val receiver = routing.receiver(fixture.record, context, fixture.conversation)
            try {
                val delivery = async { receiver.deliver(fixture.notification("before native cancellation")) }
                sink.entered.await()
                delivery.cancelAndJoin()
                assertTrue(delivery.isCancelled)
                sink.release.complete(Unit)
                assertEquals(
                    NativeCodeModeNotificationDelivery.Delivered,
                    receiver.deliver(fixture.notification("next")),
                )
            } finally {
                routing.close(owner)
            }
        }
    }
}

private class NotificationWriteBarrier : WireSink by CodeModeGatewayBridgeSink() {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun addTextBlock(text: String) {
        entered.complete(Unit)
        release.await()
    }
}
