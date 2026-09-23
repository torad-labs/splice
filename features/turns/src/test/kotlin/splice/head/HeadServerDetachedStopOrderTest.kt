// NEW (V4-108): the two BEHAVIOURAL halves of the shutdown-order fix that the source-order
// observer (DaemonStopOrderTest, in :app) can only read, not run. Both drive the REAL production
// path, not a mirror of the policy:
//
//   (b) a detached compaction is DRAINED WITHIN THE BUDGET, not ended before it. A compaction whose
//       client hung up outlives its client (5f3df5cb): its handed-off slot travels with the drive,
//       and the drain budget belongs to that feature — a detached compaction that finishes inside
//       the budget releases its slot and keeps its recording for the retry. stopDetached ends only
//       what is STILL running once the budget is spent. An earlier audit premise claimed a detached
//       compaction BURNS the budget and asserted the opposite order; that premise was wrong and is
//       corrected here. The assertion drives a REAL detached compaction, releases its upstream
//       INSIDE the drain, and requires the recording to be kept — and the drain not to time out.
//
//   (d) stopDetached ends the compactions (cancelChildren) and LEAVES THE SCOPE ALIVE for a restart,
//       so a compaction after a head stop+start can still detach (the compaction-outlives-its-client
//       feature). The scope is injectable on TurnStreamer; after stopDetached the injected scope must
//       STILL be active. An earlier fix cancelled the scope instead, which disabled detach across a
//       restart and broke HeadServerCompactionReplayTest.
package splice.head

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.head.compaction.CompactionReplay
import splice.head.turn.TurnDriveFactory
import splice.head.turn.TurnDriver
import splice.head.turn.TurnStreamer
import splice.upstream.LifecycleScope
import splice.upstream.ProviderTuning
import splice.upstream.Waiter
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

// why 50ms: the precondition polls (a slot held, a detach logged) flip at human speed; a 50ms
// interval keeps a 20s deadline from busy-spinning while still catching a quick flip promptly.
private const val POLL_INTERVAL_MS = 50L

private class DetachedStopOrderAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-dso", "acct-dso")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

/** A waiter that counts every drain poll and yields a beat so a background finally (the detached
 *  drive's slot release) has a chance to run between polls. No wall-clock assertion is made on it. */
private class CountingWaiter : Waiter {
    val polls = AtomicInteger(0)
    override suspend fun wait(ms: Long) {
        polls.incrementAndGet()
        yield()
    }
}

class HeadServerDetachedStopOrderTest {

    // Claude Code's verbatim summarizer marker makes this a compaction; SCENARIO:hold parks the
    // upstream after its first delta until the test releases it.
    private val body = """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,""" +
        """"system":"SCENARIO:hold You are tasked with summarizing conversations for another agent.",""" +
        """"messages":[{"role":"user","content":"compact"}]}"""

    private fun provider(mock: MockChatGptUpstream): TestResponsesProvider =
        TestResponsesProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-codex--",
                    models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "gpt-5.6-sol",
                auth = DetachedStopOrderAuth(),
                baseUrl = mock.baseUrl,
                // Enormous on purpose: nothing in these tests may end the turn but the hold release.
                watchdog = WatchdogBudget(600.seconds, 600.seconds, 900.seconds),
                loginCommand = "claudex login",
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
        )

    private fun deps(
        tmp: Path,
        gate: InflightGate,
        lines: CopyOnWriteArrayList<String>,
        waiter: Waiter,
    ): HeadDeps = headDeps(
        tmp = tmp,
        upstream = UpstreamClient(firstByteTimeoutMs = 600_000, totalTimeoutMs = 900_000, maxRetries = 1),
        gate = gate,
        log = { lines += it },
        seams = HeadDeps.HeadSeams(waiter = waiter),
    )

    /** A real client whose close() is a real FIN — no HTTP-client pool or cancellation semantics. */
    private fun openCompaction(port: Int): Socket {
        val socket = Socket("127.0.0.1", port)
        val request = "POST /v1/messages HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Authorization: Bearer test-inference-token\r\n" +
            "Content-Type: application/json\r\n" +
            "x-claude-code-session-id: sess-compaction\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n" +
            "Connection: close\r\n\r\n" + body
        socket.getOutputStream().write(request.toByteArray())
        socket.getOutputStream().flush()
        return socket
    }

    private suspend fun waitFor(capMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + capMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(POLL_INTERVAL_MS)
        }
        return cond()
    }

    /** Opens a compaction, holds its slot, then hangs up the client so the drive detaches. */
    private suspend fun openDetachedCompaction(
        mock: MockChatGptUpstream,
        gate: InflightGate,
        lines: CopyOnWriteArrayList<String>,
        port: Int,
    ) {
        val upstreamBefore = mock.upstreamBodies.size
        val socket = openCompaction(port)
        assertTrue(
            waitFor(20_000) { mock.upstreamBodies.size > upstreamBefore },
            "precondition: the compaction must reach upstream before the client hangs up",
        )
        assertTrue(waitFor(20_000) { gate.snapshot().inflight == 1 }, "precondition: the compaction holds a slot")
        socket.close() // the client hangs up mid-lull; the drive detaches
        assertTrue(
            waitFor(20_000) { lines.any { it.contains("compaction continues detached") } },
            "precondition: the compaction must detach: ${lines.joinToString("")}",
        )
    }

    @Test
    fun `a detached compaction finishes inside the drain budget and keeps its recording`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val mock = MockChatGptUpstream()
        val waiter = CountingWaiter()
        val gate = InflightGate({ 4 }, { 4 })
        val lines = CopyOnWriteArrayList<String>()
        val head = HeadServer(
            provider = provider(mock),
            listenPort = 0,
            deps = deps(tmp, gate, lines, waiter),
        )
        try {
            mock.resetHold()
            head.start()
            val port = head.port
            awaitListening(port)
            openDetachedCompaction(mock, gate, lines, port)
            assertTrue(gate.snapshot().inflight == 1, "the slot travels with the detached drive")

            // Stop on its own lane, then release the upstream once the drain is RUNNING: the detached
            // compaction must be allowed to finish and keep its recording, not be cut off first.
            val stopping = async { head.stop() }
            assertTrue(
                waitFor(5_000) { waiter.polls.get() > 0 },
                "precondition: the drain must start before the upstream is released",
            )
            mock.releaseHold()
            stopping.await()

            assertTrue(
                waitFor(5_000) { lines.any { it.contains("held for a byte-identical retry") } },
                "the detached compaction must finish inside the drain and keep its recording, or a " +
                    "retry re-runs upstream: ${lines.joinToString("")}",
            )
            assertFalse(
                lines.any { it.contains("draining timed out") },
                "the drain must converge once the detached compaction finishes: ${lines.joinToString("")}",
            )
        } finally {
            mock.releaseHold() // never leave a parked upstream thread behind
            head.stop()
            mock.stop()
        }
    }

    @Test
    fun `stopDetached ends the compactions but leaves the scope alive for a restart`(@TempDir tmp: Path) {
        val mock = MockChatGptUpstream()
        val gate = InflightGate({ 4 })
        val lines = CopyOnWriteArrayList<String>()
        val builtProvider = provider(mock)
        val builtDeps = deps(tmp, gate, lines, CountingWaiter())
        val scope: CoroutineScope = LifecycleScope(ProcessDispatchers().background())
        val streamer = TurnStreamer(
            provider = builtProvider,
            deps = builtDeps,
            driveFactory = TurnDriveFactory(builtProvider, builtDeps, HeadHealthCounters()),
            // V4-99 item 5: the entry takes the SEAL CONTRACT, not the whole driver — the rig
            // reaches it through the driver it already builds rather than re-assembling the four
            // collaborators the contract is composed from.
            sealedDrive = TurnDriver(builtProvider, builtDeps, CompactionReplay()).sealedDrive,
            replay = CompactionReplay(),
            detachedScope = scope,
        )
        try {
            assertTrue(scope.isActive, "precondition: the injected scope starts active")
            streamer.stopDetached()
            assertTrue(
                scope.isActive,
                "stopDetached must NOT cancel the scope itself, or a compaction after a head restart " +
                    "cannot detach and the compaction-outlives-its-client feature dies (the law of " +
                    "record is HeadServerCompactionReplayTest)",
            )
        } finally {
            mock.stop()
        }
    }
}
