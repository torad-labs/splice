package splice.provider.codex

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.provider.codex.state.CodeModeStateDirectory
import splice.provider.codex.state.CodeModeStateJournal
import splice.provider.codex.state.save.CodeModeStateWrite
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeRedirectablePost
import splice.provider.codex.stream.CodeModeSourceLease
import splice.provider.codex.stream.CodeModeStreamAdmission
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundBody
import splice.upstream.RoundResult
import splice.upstream.TurnEnd
import splice.upstream.sse.WireSink
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.hours

class CodeModeCacheLifecycleTest : CodeModeBridgeTestSupport() {
    private val json = Json { encodeDefaults = true }
    private val clock = MutableClock(1_790_000_000_000L)

    @Test
    fun `disabling the journal still enforces the byte budget on completed idle work`() {
        val config = config().copy(state = stateLocation().copy(journaling = CodeModeJournaling { false }))
        val registry = registry(config)
        complete(registry, "alpha")
        complete(registry, "beta")
        registry.cache.trim()
        assertTrue(registry.records.isEmpty(), "a one-byte budget cannot keep completed payloads without a journal")
        assertTrue(logLines.any { "journal" in it && "let go" in it }, logLines.toString())
    }

    @Test
    fun `an explicit count policy also keeps its independent byte bound`() {
        val registry = registry(config().copy(retention = CodeModeRetention(records = 50_000, bytes = 1)))
        complete(registry, "alpha")
        complete(registry, "beta")
        registry.cache.trim()
        assertTrue(registry.records.isEmpty(), "the count setting must not disable the one-byte budget")
        assertTrue(CodexCodeModeStore(stateLocation(), json, {}).load().records.isEmpty())
    }

    @Test
    fun `an unreadable expired cold journal cannot prevent later conversations from expiring`() {
        val bad = record("bad")
        val healthy = record("healthy")
        seed(listOf(bad, healthy), listOf(CodeModeExpiredSnapshot("bad", "older", emptySet(), clock.millis())))
        val registry = registry()
        Files.writeString(path("bad"), "{broken\n")
        clock.now += 25.hours.inWholeMilliseconds

        registry.timed.sweep()

        assertTrue(CodeModeStateJournal.read(path("healthy"), json).records.isEmpty(), "healthy TTL work was skipped")
        assertEquals("{broken\n", Files.readString(path("bad")), "failed restore must preserve the evidence")
        assertTrue(logLines.any { "not swept" in it }, logLines.toString())
    }

    @Test
    fun `marker expiry does not decode a cold conversation that is still within TTL`() {
        val fresh = record("fresh").also { it.progress.updatedAt += 10.hours.inWholeMilliseconds }
        seed(listOf(fresh), listOf(CodeModeExpiredSnapshot("fresh", "older", emptySet(), clock.millis())))
        val registry = registry()
        val saved = Files.readString(path("fresh"))
        Files.writeString(path("fresh"), "{temporarily unreadable\n")
        clock.now += 25.hours.inWholeMilliseconds

        registry.timed.sweep()

        assertFalse(logLines.any { "could not save" in it || "not swept" in it }, logLines.toString())
        assertTrue(registry.records.isEmpty(), "marker work must not promote fresh cold payloads")
        Files.writeString(path("fresh"), saved)
        assertEquals(listOf(fresh.id), registry.completed("fresh").map(CodeModeRecord::id))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a cross-model completed read survives concurrent cache trimming`(cold: Boolean) {
        val prior = record("prior").also { it.conversationId = "conversation" }
        val current = record("current").also {
            it.phase = CodeModePhase.ACTIVE
            it.conversationId = "conversation"
        }
        seed(listOf(prior, current))
        val writing = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val block = AtomicBoolean(false)
        val writer = CodeModeStateWrite { file, text ->
            if (block.get() && file == path("current")) {
                writing.countDown()
                check(proceed.await(10, TimeUnit.SECONDS))
            }
            CodeModeStateJournal.write(file, text)
        }
        val registry = registry(writer = writer)
        if (!cold) registry.recordsFor("prior")
        val active = registry.recordsFor("current").single()
        block.set(true)
        val failure = AtomicReference<Throwable?>()
        val found = AtomicReference<List<String>>()
        val save = thread(failure) { registry.changes.save(active) { it.origin.source = "changed" } }
        val read = thread(failure) {
            found.set(registry.completed("current", "conversation").map(CodeModeRecord::id))
        }
        save.start()
        try {
            assertTrue(writing.await(10, TimeUnit.SECONDS))
            read.start()
            waiting(read)
            registry.cache.trim()
        } finally {
            proceed.countDown()
            save.join(10_000)
            read.join(10_000)
        }
        assertFalse(save.isAlive || read.isAlive)
        assertNull(failure.get())
        assertEquals(listOf(prior.id), found.get(), "the prior native cell disappeared between restore and lookup")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cooling a settled source releases the round while preserving its accepted replay`() = runTest {
        val config = config()
        val registry = registry(config)
        val record = complete(registry, "source")
        val wire = CodexCodeModeWire(json, {})
        val round = CodeModeLiveRound(config, registry, wire, CodeModeStreamAdmission { record }, RecordingSink())
        val post = object : RedirectableRoundPost {
            override suspend fun invoke(bodyJson: String): RoundResult = RoundResult.Outcome(completedOutcome())
            override suspend fun into(bodyJson: String, sink: WireSink): RoundResult = invoke(bodyJson)
        }
        round.start(this, CodeModeRedirectablePost(post, wire), wire.body(RoundBody.Text("{}")), TurnEnd {})
        runCurrent()
        assertTrue(round.readerSettled)
        val rounds = ConcurrentHashMap<String, CodeModeLiveRound>()
        rounds[record.id] = round
        record.sourceEnd = CodeModeSourceLease(record.id, round, rounds)

        registry.cache.trim()

        assertTrue(rounds.isEmpty(), "the completed upstream round remained retained after its records were cooled")
        assertNull(record.sourceEnd)
        assertEquals(listOf("accepted source"), registry.completed("source").map { it.progress.output })
    }

    private fun config() = CodeModeBridgeConfig(
        { error("no runtime is needed") },
        stateLocation(),
        retention = CodeModeRetention(bytes = 1),
        clock = clock,
        log = { logLines += it },
    )

    private fun registry(config: CodeModeBridgeConfig = config(), writer: CodeModeStateWrite? = null) =
        CodexCodeModeRegistry(config, json, 1.hours, writer)

    private fun record(key: String) = CodeModeRecords.of(key, 0, clock.millis()).also {
        it.phase = CodeModePhase.COMPLETED
        it.progress.output = "accepted $key"
    }

    private fun complete(registry: CodexCodeModeRegistry, key: String): CodeModeRecord =
        CodeModeRecords.of(key, 0, clock.millis()).also {
            assertTrue(registry.add(it))
            registry.complete(it, "accepted $key")
        }

    private fun seed(records: List<CodeModeRecord>, expired: List<CodeModeExpiredSnapshot> = emptyList()) =
        CodexCodeModeStore(stateLocation(), json, {}).save(records, expired)

    private fun path(key: String) = CodeModeStateDirectory(stateLocation().dir, json, {}).path(key)

    private fun thread(failure: AtomicReference<Throwable?>, body: Runnable): Thread = Thread(body).apply {
        isDaemon = true
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> failure.set(error) }
    }

    private fun waiting(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (thread.isAlive && thread.state != Thread.State.WAITING) {
            if (System.nanoTime() >= deadline) break
            Thread.onSpinWait()
        }
        assertEquals(Thread.State.WAITING, thread.state)
    }
}
