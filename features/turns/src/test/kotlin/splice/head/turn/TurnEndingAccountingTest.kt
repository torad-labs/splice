// DR-128: all failure surfaces emitted the error frame BEFORE recording the perf row + health
// count, and a dead-client write makes emitError rethrow IOException after sealing — so the
// accounting never ran: the turn VANISHED from perf JSONL and the G20 counters. Exactly the hole
// CancellationSeal plugs for the cancellation path, open on every failure surface (2026-07-19
// storm shape: dead clients + failing upstream — health MUST still see the upstream failures).
// These walls drive TurnEnding.emitFailure with an emitter whose emitError throws: the IOException
// still propagates (status quo at the driver), but the instruments must have recorded first.
package splice.head.turn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.index.WireBlockIndex
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.ErrorType
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.core.wire.RateLimitReply
import splice.head.HeadHealthCounters
import splice.head.TestResponsesProvider
import splice.head.admission.TurnQuota
import splice.head.admission.admittedSlot
import splice.head.compact.CompactStats
import splice.head.perf.PerfStats
import splice.head.pipeline.TurnPipeline
import splice.head.round.RunnerSignals
import splice.head.transport.TurnAccountHandoff
import splice.head.usage.OutputClamp
import splice.head.usage.QuotaTracker
import splice.head.usage.USAGE_FLUSH_DELAY_MS
import splice.head.usage.UsageStore
import splice.head.wire.ClientChannel
import splice.head.wire.ImmediateSseWriter
import splice.head.wire.TurnTerminal
import splice.upstream.Provider
import splice.upstream.ProviderTuning
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.credentials.Selection
import splice.upstream.failure.SseFrameTooLargeException
import splice.upstream.retry.InflightGate
import splice.upstream.retry.LiveLimit
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.retry.TurnWatchdog
import splice.upstream.transport.UpstreamAuthMissing
import splice.upstream.transport.UpstreamFailed
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/** Holds a credential — the surfaces under test never contact the upstream; this only keeps
 *  provider construction honest. */
private class BranchlessFakeAuth : splice.core.auth.RefreshableAuthProvider {
    override suspend fun credentials() = splice.core.auth.Credentials.Bearer("tok", "acct")
    override suspend fun refresh() = credentials()
    override suspend fun describe() = splice.core.auth.AuthDescription(true, "fake")
}

/** The dead-client shape: every wire write already sealed, and the error frame write rethrows —
 *  exactly what SseEmitter does after ClientChannel flips clientGone on a failed write. */
private class DeadClientTerminal : TurnTerminal {
    override val hasEnded: Boolean = true
    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) = Unit
    override suspend fun emitError(type: ErrorType, message: String, permanent: Boolean): Unit =
        throw IOException("client hung up mid error frame")
    override fun abandon() = Unit
    override suspend fun openText() = WireBlockIndex(0)
    override suspend fun openThinking() = WireBlockIndex(0)
    override suspend fun openTool(id: String, name: String) = WireBlockIndex(0)
    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) = Unit
    override suspend fun addRedactedThinking(data: String) = Unit
}

/** DR-129's shape: the turn SUCCEEDED and its usage is in hand, but the terminal frame write
 *  dies on the hung-up client — everything before the terminal already reached the wire. */
private class DeadClientSuccessTerminal : TurnTerminal {
    override val hasEnded: Boolean = false
    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage): Unit =
        throw IOException("client hung up mid terminal frame")
    override suspend fun emitError(type: ErrorType, message: String, permanent: Boolean): Unit =
        throw IOException("client hung up mid error frame")
    override fun abandon() = Unit
    override suspend fun openText() = WireBlockIndex(0)
    override suspend fun openThinking() = WireBlockIndex(0)
    override suspend fun openTool(id: String, name: String) = WireBlockIndex(0)
    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) = Unit
    override suspend fun addRedactedThinking(data: String) = Unit
}

/** A terminal whose cancellation occurs while the cancellation seal tries to emit its error frame. */
private class CancellationDuringSealTerminal(private val emission: CancellationException) : TurnTerminal {
    override val hasEnded: Boolean = false
    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) = Unit
    override suspend fun emitError(type: ErrorType, message: String, permanent: Boolean): Unit = throw emission
    override fun abandon() = Unit
    override suspend fun openText() = WireBlockIndex(0)
    override suspend fun openThinking() = WireBlockIndex(0)
    override suspend fun openTool(id: String, name: String) = WireBlockIndex(0)
    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) = Unit
    override suspend fun addRedactedThinking(data: String) = Unit
}

/** A streamed turn whose client is still there: the seal's error frame reaches the wire. */
private class ConnectedTerminal : TurnTerminal {
    override val hasEnded: Boolean = false
    override suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage) = Unit
    override suspend fun emitError(type: ErrorType, message: String, permanent: Boolean) = Unit
    override fun abandon() = Unit
    override suspend fun openText() = WireBlockIndex(0)
    override suspend fun openThinking() = WireBlockIndex(0)
    override suspend fun openTool(id: String, name: String) = WireBlockIndex(0)
    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) = Unit
    override suspend fun addRedactedThinking(data: String) = Unit
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TurnEndingAccountingTest {

    private lateinit var tmp: Path
    private val usageStores = mutableListOf<UsageStore>()

    private fun flushUsage() {
        usageStores.forEach(UsageStore::flushNow)
    }

    @AfterAll
    fun tearDown() {
        flushUsage()
        assertTrue(AsyncFileIo.drain(), "accepted perf writes must finish before temporary paths are deleted")
    }

    @BeforeAll
    fun setUp(@TempDir tempDir: Path) {
        tmp = tempDir
    }

    @Test
    fun `fixture flush prevents a delayed usage write from recreating deleted paths`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val settled = CountDownLatch(1)
        assertTrue(
            AsyncFileIo.submit {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            },
        )
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val directory = Files.createDirectory(tmp.resolve("delayed-usage"))
            val usage = directory.resolve("usage.json")
            val store = UsageStore(usage, directory.resolve("ratelimit.json")).also(usageStores::add)
            store.appendOutputTokens(1)
            flushUsage()
            Files.deleteIfExists(usage)
            Files.delete(directory)
            assertTrue(AsyncFileIo.submit(USAGE_FLUSH_DELAY_MS) { settled.countDown() })
            release.countDown()
            assertTrue(settled.await(5, TimeUnit.SECONDS))
            assertFalse(Files.exists(directory), "the scheduled flush must not resurrect the fixture after deletion")
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `accepted or committed work cannot move to another login`() = runBlocking {
        val fixture = HandoffFixture("committed")
        val drive = EndingRig("committed").drive(clientGone = false).also { it.account = fixture.initial }
        try {
            fixture.hold(0)
            fixture.route.commit()
            assertFalse(fixture.route.move(drive))
            assertSame(fixture.initial, drive.account)
        } finally {
            drive.account?.releaseCredentialProbe()
            drive.slot.release()
        }
    }

    @Test
    fun `handoff changes quota together and never revisits a refused login even after its hold lifts`() = runBlocking {
        val fixture = HandoffFixture("visited")
        val drive = EndingRig("visited").drive(clientGone = false).also { it.account = fixture.initial }
        try {
            fixture.hold(0)
            assertTrue(fixture.route.move(drive))
            assertEquals("two", drive.account?.account?.label)
            assertSame(fixture.quotas.getValue("two"), drive.quota)
            fixture.accounts[0].cooldown.acceptance()()
            fixture.hold(1)
            assertFalse(fixture.route.move(drive), "the now-free first login was already refused in this request")
        } finally {
            drive.account?.releaseCredentialProbe()
            drive.slot.release()
        }
    }

    private inner class HandoffFixture(tag: String) {
        val quotas = listOf("one", "two").associateWith { QuotaTracker(tmp.resolve("$tag-$it-quota.json")) }
        val accounts = quotas.map { (label, quota) ->
            PoolAccount(
                label,
                label == "one",
                BranchlessFakeAuth(),
                AccountQuotaSource(quota::snapshot),
                RateLimitCooldown(ElapsedClock { 0L }),
            )
        }
        private val pool = AccountPool(accounts, WallClock { 1_000_000L })
        val initial = (pool.select(null) as Selection.Chosen).account
        val route = TurnAccountHandoff(pool, TurnQuota(pool, quotas, null))

        fun hold(index: Int) {
            val cooldown = accounts[index].cooldown
            cooldown.rateLimitReply = RateLimitReply("synthetic refusal", emptyMap())
            cooldown.arm(30_000L)
        }
    }

    private fun EndingRig(tag: String): EndingRig = EndingRig(tag, tmp, accountingProvider())

    // DR-129: finishTurn ran finishStream before the stamps, and a dead client's IOException out
    // of the SUCCESS terminal skipped stampSuccess — the only production writer of
    // appendOutputTokens for successes — so a turn whose usage was IN HAND landed as a token-less
    // conn-reset row. The stamps must survive the throw; the row tag and health stay owned by the
    // conn-reset surface that catches the rethrow (status quo, no double count).
    @Test
    fun `a Success whose terminal write fails still stamps its known usage - DR-129`() {
        val rig = EndingRig("dr129-success")
        val store = UsageStore(tmp.resolve("usage-dr129.json"), tmp.resolve("rl-dr129.json")).also(usageStores::add)
        val finish = TurnFinish(
            clock = ElapsedClock { 5L },
            log = rig.log,
            usageStamp = TurnUsageStamp(store, rig.log, rig.telemetry),
            health = rig.health,
            telemetry = rig.telemetry,
        )
        val success = TurnOutcome.Success(
            hasToolUse = false,
            incomplete = false,
            usage = Usage(inputTokens = 100, outputTokens = 42, cachedTokens = 7),
            bodyText = "answer",
            emittedText = true,
        )
        runBlocking {
            val drive = rig.drive(emitter = DeadClientSuccessTerminal())
            try {
                assertThrows<IOException>("the dead-client write still propagates (status quo at the driver)") {
                    runBlocking { finish.finishTurn(drive, success) }
                }
                assertEquals(42L, drive.perf.snapshot().counters["out_tokens"], "perf counts must carry the tokens")
                assertEquals(100L, drive.perf.snapshot().counters["in_tokens"])
                assertEquals(42, store.readState().outputTokens5h, "the usage store must receive the billed tokens")
            } finally {
                drive.slot.release()
            }
        }
    }

    // DR-125 (review 2026-08-31, codex): the permanent DR-125 arms all live at the RUNNERS —
    // they prove FoldRunner/ReanchorRunner emit a ClientAbandoned carrying the absorbed rounds'
    // usage. None of them reaches finishTurn, so deleting the production ClientAbandoned
    // stampSalvaged call left every one of them green: an abandoned turn's real billed tokens
    // could stop reaching UsageStore and perf entirely with the wall green. Same hole DR-129
    // closed for Success, one outcome over. This drives the real finish path.
    @Test
    fun `a ClientAbandoned turn stamps its salvaged usage - DR-125`() {
        val rig = EndingRig("dr125-abandoned")
        val store = UsageStore(tmp.resolve("usage-dr125.json"), tmp.resolve("rl-dr125.json")).also(usageStores::add)
        val finish = TurnFinish(
            clock = ElapsedClock { 5L },
            log = rig.log,
            usageStamp = TurnUsageStamp(store, rig.log, rig.telemetry),
            health = rig.health,
            telemetry = rig.telemetry,
        )
        val abandoned = TurnOutcome.ClientAbandoned(
            salvagedUsage = Usage(inputTokens = 310, outputTokens = 64, cachedTokens = 11),
        )
        runBlocking {
            val drive = rig.drive()
            try {
                finish.finishTurn(drive, abandoned)
                val counters = drive.perf.snapshot().counters
                assertEquals(64L, counters["out_tokens"], "the abandoned turn's burned output must reach perf")
                assertEquals(310L, counters["in_tokens"], "and its input")
                assertEquals(
                    64,
                    store.readState().outputTokens5h,
                    "the usage store must receive tokens the vendor already billed for an abandoned turn",
                )
            } finally {
                drive.slot.release()
            }
        }
    }

    @Test
    fun `upstream-failed records perf + provider health despite a dead client - DR-128`() {
        val rig = EndingRig("dr128-upstream")
        emitExpectingDeadClient(rig, UpstreamFailed("""{"error":{"type":"api_error"}}""", 500))
        rig.assertRecorded("error:upstream-failed")
        assertEquals(1L, rig.health.snapshot().providerError, "G20 must still see the upstream failure")
    }

    @Test
    fun `conn-reset records perf + local health despite a dead client - DR-128`() {
        val rig = EndingRig("dr128-connreset")
        emitExpectingDeadClient(rig, IOException("upstream socket tore"))
        rig.assertRecorded("error:conn-reset")
        assertEquals(1L, rig.health.snapshot().localOrigin)
    }

    @Test
    fun `auth-missing records perf + local health despite a dead client - DR-128`() {
        val rig = EndingRig("dr128-auth")
        emitExpectingDeadClient(rig, UpstreamAuthMissing())
        rig.assertRecorded("error:auth-missing")
        assertEquals(1L, rig.health.snapshot().localOrigin)
    }

    @Test
    fun `oversized-frame records perf + provider health despite a dead client - DR-128`() {
        val rig = EndingRig("dr128-frame")
        emitExpectingDeadClient(rig, SseFrameTooLargeException("data", 1))
        rig.assertRecorded("error:upstream-frame-too-large")
        assertEquals(1L, rig.health.snapshot().providerError)
    }

    @Test
    fun `unexpected runtime failure records perf + local health despite a dead client - DR-128`() {
        val rig = EndingRig("dr128-unexpected")
        emitExpectingDeadClient(rig, IllegalStateException("synthetic gateway bug"))
        rig.assertRecorded("error:unexpected")
        assertEquals(1L, rig.health.snapshot().localOrigin)
    }

    @Test
    fun `cancellation stamps known usage for collect disconnected and already-ended paths`() = runBlocking {
        val cases = listOf(
            Triple("collect", false, false) to DeadClientSuccessTerminal(),
            Triple("disconnected", true, true) to DeadClientSuccessTerminal(),
            Triple("already-ended", true, false) to DeadClientTerminal(),
        )
        cases.forEach { (case, emitter) ->
            val (name, sealRequested, clientGone) = case
            val rig = EndingRig("usage-cancel-$name")
            val store = UsageStore(tmp.resolve("usage-cancel-$name.json"), tmp.resolve("rl-cancel-$name.json"))
                .also(usageStores::add)
            val stamp = TurnUsageStamp(store, rig.log, rig.telemetry)
            val seal = CancellationSeal(accountingProvider(), rig.log, rig.telemetry, rig.health, stamp)
            val drive = rig.drive(emitter, clientGone)
            try {
                drive.recordRawRound(
                    TurnOutcome.Success(
                        hasToolUse = false,
                        incomplete = false,
                        usage = Usage(inputTokens = 50, outputTokens = 4, cachedTokens = 5),
                    ),
                )
                seal.stampAndSeal(drive, sealRequested, CancellationException("$name cancellation"))
                assertEquals(4, store.readState().outputTokens5h, "$name must retain returned raw usage")
            } finally {
                drive.slot.release()
            }
        }
    }

    /** Ledger 320: a streamed turn cancelled after a raw round returned wrote its perf row before the stamp set
     *  that round's counters, so the row read zero while the usage store held the round's output. */
    @Test
    fun `a streamed cancellation writes the returned rounds' counters into its perf row`() = runBlocking {
        val cases = listOf(
            SealCase("connected", ConnectedTerminal(), false, CancellationException("cut"), "error:cancelled"),
            SealCase("client-gone", ConnectedTerminal(), true, CancellationException("left"), "client_abort"),
            SealCase("unwritable", DeadClientSuccessTerminal(), false, CancellationException("cut"), "client_abort"),
            SealCase("restart", ConnectedTerminal(), false, HeadRestart(), "error:restarted"),
        )
        cases.forEach { case ->
            val rig = EndingRig("row-cancel-${case.name}")
            val store = UsageStore(tmp.resolve("row-${case.name}.json"), tmp.resolve("rl-row-${case.name}.json"))
                .also(usageStores::add)
            val stamp = TurnUsageStamp(store, rig.log, rig.telemetry)
            val seal = CancellationSeal(accountingProvider(), rig.log, rig.telemetry, rig.health, stamp)
            val drive = rig.drive(case.emitter, case.clientGone)
            try {
                drive.recordRawRound(
                    TurnOutcome.Success(
                        hasToolUse = false,
                        incomplete = false,
                        usage = Usage(inputTokens = 50, outputTokens = 4, cachedTokens = 5),
                    ),
                )
                seal.stampAndSeal(drive, seal = true, original = case.cause)
                AsyncFileIo.drain() // perf rows append asynchronously
                val row = Files.readAllLines(rig.perfFile).single()
                assertTrue(row.contains("\"outcome\":\"${case.outcome}\""), "${case.name}: $row")
                assertEquals(50L, rowCount(row, "in_tokens"), "${case.name}: $row")
                assertEquals(5L, rowCount(row, "cached_tokens"), "${case.name}: $row")
                assertEquals(4L, rowCount(row, "out_tokens"), "${case.name}: $row")
                assertEquals(4, store.readState().outputTokens5h, "${case.name} must still stamp the store once")
            } finally {
                drive.slot.release()
            }
        }
    }

    private data class SealCase(
        val name: String,
        val emitter: TurnTerminal,
        val clientGone: Boolean,
        val cause: CancellationException,
        val outcome: String,
    )

    /** Oct 4: a code-mode turn that parks while the round writing its script still streams wrote 0 in, 0 out, and a
     *  later turn absorbed that round. Its row now waits for the round and keeps the time its turn ended. */
    @Test
    fun `a row held for a streaming round lands with that round's usage at the time its turn ended`() = runBlocking {
        val rig = EndingRig("held-row")
        var now = HELD_TURN_END
        val telemetry = TurnTelemetry("codex", PerfStats(rig.perfFile, WallClock { now }), rig.log, ElapsedClock { 5L })
        val drive = rig.drive()
        val release = drive.sourceRow.hold()
        telemetry.recordPerf(drive, "ok")
        AsyncFileIo.drain()
        assertFalse(Files.exists(rig.perfFile), "the row waits for the round")
        now = HELD_TURN_END + HELD_ROUND_MS
        release.release(Usage(inputTokens = 50, outputTokens = 4, cachedTokens = 5))
        release.release(Usage(inputTokens = 999))
        AsyncFileIo.drain()
        val row = Files.readAllLines(rig.perfFile).single()
        assertEquals(HELD_TURN_END, rowCount(row, "ts"), row)
        assertEquals(listOf(50L, 5L, 4L), listOf(IN, CACHED, OUT).map { rowCount(row, it) }, row)
        assertEquals(null, rowCount(row, PerfKeys.ABSORBED_ROUNDS), row)
        assertEquals(null, rowCount(row, "failure_permanent"), "a healthy row must not invent a failure decision")

        val early = rig.drive()
        early.sourceRow.hold().release(Usage(inputTokens = 7, outputTokens = 1))
        telemetry.recordPerf(early, "ok")
        AsyncFileIo.drain()
        assertEquals(7L, rowCount(Files.readAllLines(rig.perfFile).last(), IN), "a round that settled first is carried")
    }

    @Test
    fun `a head stop writes a held row with what is known, and the round settling afterwards changes nothing`() =
        runBlocking {
            val rig = EndingRig("held-stop")
            val drive = rig.drive()
            drive.perf.setCount(IN, 3L)
            val release = drive.sourceRow.hold()
            rig.telemetry.recordPerf(drive, "ok")
            rig.telemetry.flushHeld()
            release.release(Usage(inputTokens = 50, outputTokens = 4))
            val cut = rig.drive()
            cut.sourceRow.hold().also { rig.telemetry.recordPerf(cut, "ok") }.release(null)
            AsyncFileIo.drain()
            val rows = Files.readAllLines(rig.perfFile)
            assertEquals(2, rows.size, "$rows")
            assertEquals(3L, rowCount(rows.first(), IN), "the stop writes what the turn knew: ${rows.first()}")
            assertEquals(null, rowCount(rows.last(), IN), "a cut round releases with nothing: ${rows.last()}")
        }

    /** Blocker #5: terminal emission can itself cancel. The original cancellation remains the one
     *  the driver rethrows, while known completed raw rounds are synchronously stamped once. */
    @Test
    fun `cancellation during terminal emission preserves the original cancellation and stamps usage`() = runBlocking {
        val rig = EndingRig("usage-cancel-during-seal")
        val store = UsageStore(tmp.resolve("usage-cancel-during-seal.json"), tmp.resolve("rl-cancel-during-seal.json"))
            .also(usageStores::add)
        val stamp = TurnUsageStamp(store, rig.log, rig.telemetry)
        val seal = CancellationSeal(accountingProvider(), rig.log, rig.telemetry, rig.health, stamp)
        val original = CancellationException("original turn cancellation")
        val duringEmission = CancellationException("terminal emission cancellation")
        val drive = rig.drive(CancellationDuringSealTerminal(duringEmission), clientGone = false)
        try {
            drive.recordRawRound(
                TurnOutcome.Success(
                    hasToolUse = false,
                    incomplete = false,
                    usage = Usage(inputTokens = 100, outputTokens = 3, cachedTokens = 10, reasoningTokens = 1),
                ),
            )
            drive.recordRawRound(
                TurnOutcome.Success(
                    hasToolUse = false,
                    incomplete = false,
                    usage = Usage(inputTokens = 200, outputTokens = 5, cachedTokens = 20, reasoningTokens = 2),
                ),
            )

            val thrown = try {
                try {
                    throw original
                } catch (caught: CancellationException) {
                    seal.stampAndSeal(drive, seal = true, original = caught)
                    throw caught
                }
            } catch (caught: CancellationException) {
                caught
            }

            assertSame(original, thrown, "cleanup must never replace the turn's cancellation instance")
            assertEquals(200L, drive.perf.snapshot().counters["in_tokens"])
            assertEquals(20L, drive.perf.snapshot().counters["cached_tokens"])
            assertEquals(8L, drive.perf.snapshot().counters["out_tokens"])
            assertEquals(8, store.readState().outputTokens5h)
        } finally {
            drive.slot.release()
        }
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TurnFailurePermanenceTest {
    private lateinit var tmp: Path
    private val usageStores = mutableListOf<UsageStore>()

    @BeforeAll
    fun setUp(@TempDir tempDir: Path) {
        tmp = tempDir
    }

    @AfterAll
    fun tearDown() {
        usageStores.forEach(UsageStore::flushNow)
        assertTrue(AsyncFileIo.drain(), "accepted perf writes must finish before temporary paths are deleted")
    }

    @Test
    fun `a reported failure retains permanence when its terminal write falls back to conn-reset`() = runBlocking {
        for ((permanent, expected) in listOf(false to 0L, true to 1L)) {
            val rig = EndingRig("dead-permanence-$permanent", tmp, accountingProvider())
            val store = UsageStore(tmp.resolve("dead-$permanent.json"), tmp.resolve("dead-rl-$permanent.json"))
                .also(usageStores::add)
            val finish = TurnFinish(
                ElapsedClock { 5L },
                rig.log,
                TurnUsageStamp(store, rig.log, rig.telemetry),
                rig.health,
                rig.telemetry,
            )
            val drive = rig.drive(DeadClientSuccessTerminal(), clientGone = false)
            try {
                val outcome = TurnOutcome.Failure(
                    "synthetic upstream verdict",
                    FailureCause.UPSTREAM_REPORTED,
                    FailurePhase.MID_OUTPUT,
                    permanent = permanent,
                )
                val torn = assertThrows<IOException> { runBlocking { finish.finishTurn(drive, outcome) } }
                assertThrows<IOException> { runBlocking { rig.ending.emitFailure(drive, torn) } }
                AsyncFileIo.drain()
                val row = Files.readAllLines(rig.perfFile).single()
                assertTrue(row.contains("\"outcome\":\"error:conn-reset\""), row)
                assertEquals(expected, rowCount(row, "failure_permanent"), row)
            } finally {
                drive.slot.release()
            }
        }
    }

    @Test
    fun `held rows retain observed false true and absent permanence decisions`() = runBlocking {
        for (permanent in listOf(null, false, true)) {
            val rig = EndingRig("held-permanence-$permanent", tmp, accountingProvider())
            val drive = rig.drive(ConnectedTerminal(), clientGone = false)
            val release = drive.sourceRow.hold()
            try {
                rig.telemetry.recordPerf(drive, "ok", permanent = permanent)
                AsyncFileIo.drain()
                assertFalse(Files.exists(rig.perfFile))
                release.release(Usage(inputTokens = 7, outputTokens = 1))
                AsyncFileIo.drain()
                val row = Files.readAllLines(rig.perfFile).single()
                val expected = permanent?.let { if (it) 1L else 0L }
                assertEquals(expected, rowCount(row, "failure_permanent"), row)
                assertEquals(7L, rowCount(row, IN), row)
            } finally {
                drive.slot.release()
            }
        }
    }

    @Test
    fun `reported failures retain both permanence decisions in their encoded perf rows`() = runBlocking {
        for ((permanent, expected) in listOf(false to 0L, true to 1L)) {
            val rig = EndingRig("permanence-$permanent", tmp, accountingProvider())
            val store = UsageStore(tmp.resolve("usage-$permanent.json"), tmp.resolve("rl-$permanent.json"))
                .also(usageStores::add)
            val finish = TurnFinish(
                ElapsedClock { 5L },
                rig.log,
                TurnUsageStamp(store, rig.log, rig.telemetry),
                rig.health,
                rig.telemetry,
            )
            val drive = rig.drive(ConnectedTerminal(), clientGone = false)
            try {
                finish.finishTurn(
                    drive,
                    TurnOutcome.Failure(
                        "synthetic upstream verdict",
                        FailureCause.UPSTREAM_REPORTED,
                        FailurePhase.MID_OUTPUT,
                        permanent = permanent,
                    ),
                )
                AsyncFileIo.drain()
                val row = Files.readAllLines(rig.perfFile).single()
                assertTrue(row.contains("\"cause\":\"UPSTREAM_REPORTED\""), row)
                assertEquals(expected, rowCount(row, "failure_permanent"), row)
            } finally {
                drive.slot.release()
            }
        }
    }

    @Test
    fun `classified http permanence survives a dead client's error write`() {
        for ((status, expected) in listOf(500 to 0L, 400 to 1L)) {
            val rig = EndingRig("http-permanence-$status", tmp, accountingProvider())
            emitExpectingDeadClient(rig, UpstreamFailed("""{"error":{"type":"api_error"}}""", status))
            AsyncFileIo.drain()
            assertEquals(expected, rowCount(Files.readAllLines(rig.perfFile).single(), "failure_permanent"))
        }
    }
}

private fun accountingProvider(): Provider = TestResponsesProvider(
    tuning = ProviderTuning(
        key = "codex",
        label = "claudex",
        catalog = ModelCatalog(
            discoveryPrefix = "claude-codex--",
            models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
            defaultContextWindow = 272_000,
        ),
        pinnedModel = "gpt-5.6-sol",
        auth = BranchlessFakeAuth(),
        baseUrl = "http://127.0.0.1:1",
        watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
        loginCommand = "claudex login",
    ),
    showReasoning = ReasoningDisplay.TEXT,
    replayReasoning = false,
    configEffort = "high",
    configSummary = "detailed",
)

private fun emitExpectingDeadClient(rig: EndingRig, e: Throwable) = runBlocking {
    val drive = rig.drive()
    try {
        assertThrows<IOException>("the dead-client write still propagates (status quo at the driver)") {
            runBlocking { rig.ending.emitFailure(drive, e) }
        }
    } finally {
        drive.slot.release()
    }
}

private fun rowCount(row: String, key: String): Long? =
    Regex("\"$key\":(\\d+)").find(row)?.groupValues?.get(1)?.toLong()

/** One ending surface with observable instruments; [tag] isolates each test's perf file. */
private class EndingRig(tag: String, tmp: Path, p: Provider) {
    val logs = mutableListOf<String>()
    val log = LogSink { logs.add(it) }
    val health = HeadHealthCounters()
    val perfFile: Path = tmp.resolve("perf-$tag.jsonl")
    val telemetry = TurnTelemetry("codex", PerfStats(perfFile), log, ElapsedClock { 5L })
    val ending: TurnEnding
    init {
        val failures = TurnFailures(p)
        ending = TurnEnding(
            log,
            telemetry,
            health,
            TurnConnEnd(p, log, telemetry, failures, health),
            TurnKnownEnd(p, log, telemetry, failures, health),
        )
    }

    suspend fun drive(
        emitter: TurnTerminal = DeadClientTerminal(),
        clientGone: Boolean = true,
    ): TurnDrive = TurnDrive(
        requestBody = buildJsonObject { },
        meta = TurnMeta(
            compact = false,
            showReasoning = ReasoningDisplay.TEXT,
            stream = true,
            originalModel = "claude-codex--gpt-5.6-sol",
            upstreamModel = "gpt-5.6-sol",
            clientMaxTokens = 100,
            effort = "high",
            summary = "detailed",
            budgetTokens = null,
        ),
        emitter = emitter,
        watchdog = TurnWatchdog(WatchdogBudget(10.seconds, 10.seconds, 30.seconds)),
        slot = InflightGate(LiveLimit { 1 }).admittedSlot(),
        pipeline = TurnPipeline(
            CompactStats(perfFile.resolveSibling("compact-dr128.jsonl")),
            log = log,
            clampOutput = OutputClamp { it },
        ),
        t0 = 0,
        trace = null,
        perf = TurnPerf(),
        turnHeaders = emptyMap(),
        signals = RunnerSignals(),
        channel = ClientChannel(
            ImmediateSseWriter(writeRaw = { _ -> }, flushRaw = {}),
            Mutex(),
            AtomicBoolean(clientGone),
        ),
        toolSearch = null,
    )

    fun assertRecorded(tag: String) {
        AsyncFileIo.drain() // perf rows append asynchronously
        assertTrue(
            Files.readString(perfFile).contains(tag),
            "the perf row must survive a dead-client emit; file=${Files.readString(perfFile)}",
        )
    }
}

// why: the wall time a held row's turn ended, and how much later its source round settled; any two distinct values.
private const val HELD_TURN_END = 1_791_000_000_000L
private const val HELD_ROUND_MS = 40_000L

private const val IN = PerfKeys.IN_TOKENS
private const val CACHED = PerfKeys.CACHED_TOKENS
private const val OUT = PerfKeys.OUT_TOKENS
