package splice.codemode

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeNotification
import splice.upstream.codemode.NativeCodeModeNotificationDelivery
import splice.upstream.codemode.NativeCodeModeNotifications
import splice.upstream.codemode.NativeCodeModeOptions

class NativeCodeModeNotificationsTest {
    @Test
    fun `a yielded cell notifies its owner while tool calls wait for permission`() =
        nativeProcessTest {
            NativeCodeModeRuntime().use { runtime ->
                val notices = Channel<NativeCodeModeNotification>(Channel.UNLIMITED)
                val cell = runtime.startNativeSession(
                    "exact-owner",
                    "text('prefix'); yield_control(); await new Promise(r=>setTimeout(r,25)); " +
                        "notify('after-yield'); const value=await tools.Read({key:'permission'}); " +
                        "store('receipt',value); text(value);",
                    setOf("Read"),
                    emptyMap(),
                    options(notices),
                )
                assertEquals("prefix", (cell.advance() as CodeModeStep.Yielded).output)
                val notice = withTimeout(NOTIFICATION_WAIT_MS) { notices.receive() }
                assertEquals(
                    NativeCodeModeNotification("exact-owner", cell.cellId, "notify-exec", "after-yield"),
                    notice,
                )
                val permission = (cell.observe(NOTIFICATION_WAIT_MS) as CodeModeStep.Calls).calls.single()
                assertEquals("Read", permission.name)
                assertEquals("{\"key\":\"permission\"}", permission.arguments.toString())
                val done = cell.advance(listOf(CodeModeResult(permission.id, "approved"))) as CodeModeStep.Completed
                assertEquals("approved", done.output)
                val stored = runtime.startSession("exact-owner", "text(load('receipt'));", emptySet()).advance()
                assertEquals("approved", (stored as CodeModeStep.Completed).output)
                assertTrue(notices.tryReceive().isFailure, "the subscribed notification was delivered twice")
            }
        }

    @Test
    fun `a subscribed notification settles before any foreground observation without a duplicate Notified step`() =
        nativeProcessTest {
            NativeCodeModeRuntime().use { runtime ->
                val notices = Channel<NativeCodeModeNotification>(Channel.UNLIMITED)
                val cell = runtime.startNativeSession(
                    "before-observation",
                    "notify('immediate'); text('finished');",
                    emptySet(),
                    emptyMap(),
                    options(notices),
                )
                assertEquals("immediate", withTimeout(NOTIFICATION_WAIT_MS) { notices.receive() }.text)
                assertEquals("finished", (cell.advance() as CodeModeStep.Completed).output)
            }
        }

    @Test
    fun `canceling an observation preserves the cell notification owner and completed state`() = nativeProcessTest {
        NativeCodeModeRuntime().use { runtime ->
            val notices = Channel<NativeCodeModeNotification>(Channel.UNLIMITED)
            val cell = runtime.startNativeSession(
                "cancelled-observer",
                "yield_control(); await new Promise(r=>setTimeout(r,100)); " +
                    "notify('after-cancel'); store('kept','committed'); text('finished');",
                emptySet(),
                emptyMap(),
                options(notices),
            )
            assertTrue(cell.advance() is CodeModeStep.Yielded)
            val observer = async { cell.observe(NOTIFICATION_WAIT_MS) }
            yield()
            observer.cancelAndJoin()
            assertEquals("after-cancel", withTimeout(NOTIFICATION_WAIT_MS) { notices.receive() }.text)
            assertEquals("finished", (cell.observe(NOTIFICATION_WAIT_MS) as CodeModeStep.Completed).output)
            val stored = runtime.startSession("cancelled-observer", "text(load('kept'));", emptySet()).advance()
            assertEquals("committed", (stored as CodeModeStep.Completed).output)
        }
    }

    @Test
    fun `a no-active-turn notification refusal settles the native execution without inventing a tool failure`() =
        nativeProcessTest {
            NativeCodeModeRuntime().use { runtime ->
                val notice = CompletableDeferred<NativeCodeModeNotification>()
                val cell = runtime.startNativeSession(
                    "unavailable-owner",
                    "notify('orphaned'); text('finished');",
                    emptySet(),
                    emptyMap(),
                    NativeCodeModeOptions(
                        notifications = NativeCodeModeNotifications {
                            notice.complete(it)
                            NativeCodeModeNotificationDelivery.Rejected(
                                "failed to inject exec notify message for cell ${it.cellId}: no active turn",
                            )
                        },
                    ),
                )
                assertEquals(cell.cellId, withTimeout(NOTIFICATION_WAIT_MS) { notice.await() }.cellId)
                val completed = cell.advance() as CodeModeStep.Completed
                assertEquals("finished", completed.output)
                assertEquals(null, completed.error)
            }
        }

    @Test
    fun `a suspended notification delivery leaves ordinary permission calls available`() = nativeProcessTest {
        NativeCodeModeRuntime().use { runtime ->
            val entered = CompletableDeferred<String>()
            val delivery = CompletableDeferred<NativeCodeModeNotificationDelivery>()
            val cell = runtime.startNativeSession(
                "held-notification",
                "notify('held'); text(await tools.Read({key:'permission'}));",
                setOf("Read"),
                emptyMap(),
                NativeCodeModeOptions(
                    notifications = NativeCodeModeNotifications {
                        entered.complete(it.text)
                        delivery.await()
                    },
                ),
            )
            assertEquals("held", withTimeout(NOTIFICATION_WAIT_MS) { entered.await() })
            val permission = withTimeout(NOTIFICATION_WAIT_MS) { cell.advance() as CodeModeStep.Calls }.calls.single()
            assertEquals("Read", permission.name)
            assertEquals("{\"key\":\"permission\"}", permission.arguments.toString())
            delivery.complete(NativeCodeModeNotificationDelivery.Delivered)
            val completed = cell.advance(listOf(CodeModeResult(permission.id, "approved"))) as CodeModeStep.Completed
            assertEquals("approved", completed.output)
        }
    }

    @Test
    fun `closing one native session cancels its pending notification delivery and keeps another session usable`() =
        nativeProcessTest {
            NativeCodeModeRuntime().use { runtime ->
                val entered = CompletableDeferred<String>()
                val released = CompletableDeferred<String>()
                val cell = runtime.startNativeSession(
                    "closing-owner",
                    "notify('held'); await new Promise(()=>{});",
                    emptySet(),
                    emptyMap(),
                    NativeCodeModeOptions(
                        notifications = NativeCodeModeNotifications {
                            entered.complete(it.cellId)
                            try {
                                awaitCancellation()
                            } finally {
                                released.complete(it.cellId)
                            }
                        },
                    ),
                )
                assertEquals(cell.cellId, withTimeout(NOTIFICATION_WAIT_MS) { entered.await() })
                runtime.closeSession("closing-owner")
                assertEquals(cell.cellId, withTimeout(NOTIFICATION_WAIT_MS) { released.await() })
                val sibling = runtime.startSession("still-owned", "text('other session');", emptySet()).advance()
                assertEquals("other session", (sibling as CodeModeStep.Completed).output)
            }
        }

    private fun options(notices: Channel<NativeCodeModeNotification>): NativeCodeModeOptions = NativeCodeModeOptions(
        toolCallId = "notify-exec",
        notifications = NativeCodeModeNotifications {
            notices.send(it)
            NativeCodeModeNotificationDelivery.Delivered
        },
    )
}

private const val NOTIFICATION_WAIT_MS = 5_000L
