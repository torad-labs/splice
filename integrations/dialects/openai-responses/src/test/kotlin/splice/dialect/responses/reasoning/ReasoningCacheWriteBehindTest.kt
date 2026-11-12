// NEW: fix 3, approved by Marcos about 2:00 AM CT on Oct 26 — a session on a Responses head never waits on the disk
// for reasoning-cache writes, its own or another session's, and a daemon restart still keeps every record. Each
// completed reasoning item used to write its record and force it to disk inside the upstream read loop, under one
// lock every session on the head shared; on claudex, Oct 25, 6 to 11 PM CT, splice's first byte to the client
// trailed the upstream's by 6.4 s at p50 and 29.7 s at p90 with that lock on the path. A disk that holds what is
// written to it until the test lets go stands in for a slow disk, which cannot be produced here for real; the
// cache, its files and the head's turns above it run as the daemon runs them.
package splice.dialect.responses.reasoning

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.index.WireBlockIndex
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnOutcome
import splice.core.util.JsonlForce
import splice.core.util.JsonlSink
import splice.core.util.LogSink
import splice.dialect.responses.ReasoningContinuity
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRoundTripQuirks
import splice.dialect.responses.ResponsesTurnOptions
import splice.dialect.responses.ResponsesTurnSeams
import splice.dialect.responses.ResponsesTurnSeamsDeps
import splice.dialect.responses.RoundCarry
import splice.dialect.responses.WatchdogCaps
import splice.dialect.responses.request.ResponsesRequestBuilder
import splice.dialect.responses.request.ResponsesStableIds
import splice.dialect.responses.stream.ConversationSummaryParts
import splice.dialect.responses.tools.ToolSurfaceLatch
import splice.upstream.TurnSignals
import splice.upstream.sse.WireSink
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

// How long a turn, a put or a stop may take before the test says it waited on the disk. Far above what any of them
// takes when nothing waits (milliseconds), so a loaded machine cannot fail a correct run.
private const val BOUND_SECONDS = 10L

// The longest a held disk holds a record when a run never releases it, so a broken run cannot hang the suite.
private const val HOLD_LIMIT_SECONDS = 60L

// How long the property's stop waits for the disk to take the whole backlog. Each conversation begun again creates
// its file with a forced write, and one run begins hundreds, so the head's own 2 s bound would let a busy disk fail a
// correct run; the property is about the order the records land in, not about how fast.
private const val CATCH_UP_MS = 60_000L

// How long a stop here waits for the disk to take what is queued: the turn bound, far above what landing takes when
// nothing holds the disk.
private const val STOP_MS = BOUND_SECONDS * 1_000

/** The write lane a head builds for its cache over [dir] (ResponsesParts), with [disk] standing in for the disk. */
private fun lane(dir: Path, disk: JsonlForce = JsonlSink.PAGE_CACHE_FORCE) =
    ReasoningCacheWrites(ReasoningCacheFiles(dir, disk, LogSink {}))

/** A disk that lets [passing] records through, then holds every later one until [release]: the stand-in for a slow
 *  disk. Records from the cache's files reach it through the force each append makes. */
private class HeldDisk(private val passing: Int = 0) : JsonlForce {
    private val written = AtomicInteger()
    private val held = CountDownLatch(1)
    private val reached = CountDownLatch(1)

    override fun force(path: Path, channel: FileChannel) {
        if (written.incrementAndGet() <= passing) return
        reached.countDown()
        held.await(HOLD_LIMIT_SECONDS, TimeUnit.SECONDS)
    }

    /** Whether a record reached the disk and is being held there. */
    fun holding(): Boolean = reached.await(BOUND_SECONDS, TimeUnit.SECONDS)

    fun release() = held.countDown()
}

/** What one session's client was handed: the tool calls opened on its stream. */
private class ClientCalls : WireSink {
    val opened: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val next = AtomicInteger()

    override suspend fun openText(): WireBlockIndex = WireBlockIndex(next.getAndIncrement())
    override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(next.getAndIncrement())
    override suspend fun openTool(id: String, name: String): WireBlockIndex {
        opened.add(id)
        return WireBlockIndex(next.getAndIncrement())
    }

    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) = Unit
    override suspend fun addRedactedThinking(data: String) = Unit
}

private const val OPENING = "fix the failing build"
private const val TOOLS =
    """"tools":[{"name":"run","description":"run","input_schema":{"type":"object","properties":{}}}]"""

private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

private val opening = AnthropicParse.parseAnthropicBody(
    """{"model":"gpt-5.6-sol","max_tokens":1000,"messages":[{"role":"user","content":"$OPENING"}],$TOOLS}""",
)

/** The client's request after [callId]'s tool ran: the opening, the call, its result. */
private fun afterCall(callId: String) = AnthropicParse.parseAnthropicBody(
    """{"model":"gpt-5.6-sol","max_tokens":1000,"messages":[
        {"role":"user","content":"$OPENING"},
        {"role":"assistant","content":[{"type":"tool_use","id":"$callId","name":"run","input":{}}]},
        {"role":"user","content":[{"type":"tool_result","tool_use_id":"$callId","content":"ok"}]}
    ],$TOOLS}""",
)

/** The upstream stream of one tool round: its reasoning item, then the call it planned, then the terminal. */
private fun toolRound(callId: String) = listOf(
    ev("""{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning"}}"""),
    ev(
        """{"type":"response.output_item.done","output_index":0,""" +
            """"item":{"type":"reasoning","id":"rs_$callId","encrypted_content":"cipher-$callId","summary":[]}}""",
    ),
    ev(
        """{"type":"response.output_item.added","output_index":1,""" +
            """"item":{"type":"function_call","call_id":"$callId","name":"run"}}""",
    ),
    ev("""{"type":"response.function_call_arguments.done","output_index":1,"arguments":"{}"}"""),
    ev(
        """{"type":"response.completed",""" +
            """"response":{"id":"r_$callId","usage":{"input_tokens":100,"output_tokens":7}}}""",
    ),
)

private val signals = TurnSignals(watchdogFired = { null }, clientGone = { false })

/** One claudex head over [cache], wired as ResponsesParts wires it with the shipped quirks (the reasoning cache on,
 *  client replay off): each turn's request is built and its upstream round translated as the daemon does. */
private class CacheHead(cache: ReasoningCache) {
    private val quirks = ResponsesQuirks(
        lite = ResponsesLiteQuirks(),
        providerTag = "claudex",
        roundTrip = ResponsesRoundTripQuirks(reasoningCache = true),
    )
    private val continuity = ReasoningContinuity(cache, ReasoningCachePolicy(), ResponsesStableIds())
    private val options = ResponsesTurnOptions(
        ReasoningSettings(ReasoningDisplay.TEXT, false, null, null),
        quirks,
        ModelCatalog(
            discoveryPrefix = "claude-codex",
            models = listOf(ModelEntry(id = "gpt-5.6-sol", label = "sol", contextWindow = 400_000)),
            defaultContextWindow = 400_000,
        ),
        LogSink {},
        continuity,
        ToolSurfaceLatch(),
    )
    private val seams = ResponsesTurnSeams(
        ResponsesTurnSeamsDeps(
            quirks = quirks,
            continuity = continuity,
            summaryParts = ConversationSummaryParts(),
            turnOptions = options,
            carry = RoundCarry(fold = null),
            caps = WatchdogCaps(streamIdleMs = 180_000, upstreamTimeoutMs = 900_000),
        ),
    )
    private val builder = ResponsesRequestBuilder(quirks)

    /** [session]'s tool round [callId]: its request built, then its upstream stream translated to [client]. */
    fun turn(session: String, callId: String, client: WireSink): TurnOutcome = runBlocking {
        val built = builder.build(opening.typed, opening.raw, options.build(opening, false, session))
        seams.streamTranslator(built.meta, signals).driveTurn(toolRound(callId).asFlow(), client)
    }

    /** The ciphertext of the reasoning item [session]'s next request carries right before [callId]'s
     *  function_call, or null when that call goes out with no reasoning in front of it. */
    fun reasoningBefore(session: String, callId: String): String? {
        val body = afterCall(callId)
        val input = builder.build(body.typed, body.raw, options.build(body, false, session)).req
            .getValue("input").jsonArray.map { it.jsonObject }
        val at = input.indexOfFirst {
            it["type"]?.jsonPrimitive?.content == "function_call" && it["call_id"]?.jsonPrimitive?.content == callId
        }
        check(at >= 0) { "the request carries no function_call $callId: $input" }
        val before = input.getOrNull(at - 1)?.takeIf { it["type"]?.jsonPrimitive?.content == "reasoning" }
        return before?.get("encrypted_content")?.jsonPrimitive?.content
    }
}

/** [work]'s result, or a failure saying [what] waited on the disk. */
private fun <T> finished(work: Future<T>, what: String): T = try {
    work.get(BOUND_SECONDS, TimeUnit.SECONDS)
} catch (_: TimeoutException) {
    throw AssertionError(
        "$what did not finish within ${BOUND_SECONDS}s while a reasoning-cache record was held on the disk",
    )
}

class ReasoningCacheWriteBehindTest(@param:TempDir private val state: Path) {

    @Test
    fun `while a session's reasoning record is held on the disk, its own turn and another session's turn finish`() {
        val disk = HeldDisk()
        val writes = lane(state.resolve("reasoning"), disk)
        val head = CacheHead(ReasoningCache(writes = writes))
        val sessions = Executors.newCachedThreadPool()
        try {
            val clientA = ClientCalls()
            val turnA = sessions.submit<TurnOutcome> { head.turn("session-a", "call_a", clientA) }
            assertTrue(disk.holding(), "session-a's reasoning never reached the disk")
            val clientB = ClientCalls()
            val turnB = sessions.submit<TurnOutcome> { head.turn("session-b", "call_b", clientB) }

            assertTrue(finished(turnB, "session-b's turn") is TurnOutcome.Success)
            assertEquals(1, clientB.opened.size, "session-b's client never got the call that followed its reasoning")
            assertTrue(finished(turnA, "session-a's own turn") is TurnOutcome.Success)
            assertEquals(1, clientA.opened.size, "session-a's client never got the call that followed its reasoning")
        } finally {
            disk.release()
            sessions.shutdown()
            writes.drain(STOP_MS)
        }
    }

    @Test
    fun `a stop waits for the records the disk still holds, and the next start restores every record put before it`() {
        val dir = state.resolve("reasoning")
        val disk = HeldDisk(passing = 2)
        val writes = lane(dir, disk)
        val cache = ReasoningCache(writes = writes)
        val sessions = Executors.newCachedThreadPool()
        try {
            cache.put("splice-a", listOf("call_a1"), listOf("ea1"))
            cache.put("splice-b", listOf("call_b1"), listOf("eb1"))
            sessions.submit<Unit> { cache.put("splice-a", listOf("call_a2"), listOf("ea2")) }
            assertTrue(disk.holding(), "the third record never reached the disk")

            val stopping = Thread({ writes.drain(STOP_MS) }, "daemon-stop").apply { start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS)
            while (!settled(stopping) && System.nanoTime() < deadline) Thread.onSpinWait()
            val waited = stopping.isAlive
            disk.release()
            stopping.join(TimeUnit.SECONDS.toMillis(BOUND_SECONDS))

            assertTrue(waited, "the stop returned while the disk still held a record")
            assertFalse(stopping.isAlive, "the stop did not return once the disk took the records")
        } finally {
            disk.release()
            sessions.shutdown()
        }

        val next = ReasoningCache(writes = lane(dir))
        assertEquals(mapOf("call_a1" to listOf("ea1"), "call_a2" to listOf("ea2")), next.snapshot("splice-a"))
        assertEquals(mapOf("call_b1" to listOf("eb1")), next.snapshot("splice-b"))
    }

    // The property behind write order: the disk ends where memory ends. Sessions on several threads add rounds to
    // three conversations and compact them, under an entry bound small enough that bound pressure evicts a
    // conversation and a later round begins it again all the time; a conversation's file must be gone before its new
    // round lands, or the next start holds a round memory dropped, or loses one memory kept. The disk holds the
    // first record while the sessions act, then catches up while they act on.
    @Test
    fun `after a clean stop the next start holds exactly the rounds memory held, in order, whichever thread wrote`() {
        repeat(SEEDS) { seed ->
            val dir = state.resolve("seed-$seed")
            val disk = HeldDisk()
            val writes = lane(dir, disk)
            val cache = ReasoningCache(maxEntries = ENTRY_BOUND, writes = writes)
            val sessions = Executors.newFixedThreadPool(THREADS)
            try {
                act(sessions, cache, seed, phase = 0, "seed $seed: the sessions' writes while the disk held a record")
                disk.release()
                act(sessions, cache, seed, phase = 1, "seed $seed: the sessions' writes while the disk caught up")
            } finally {
                disk.release()
                sessions.shutdown()
            }
            assertTrue(writes.drain(CATCH_UP_MS), "seed $seed: the disk did not take the backlog in ${CATCH_UP_MS}ms")

            val restarted = lane(dir)
            val next = ReasoningCache(maxEntries = ENTRY_BOUND, writes = restarted)
            KEYS.forEach { key ->
                assertEquals(cache.snapshot(key).toList(), next.snapshot(key).toList(), "seed $seed, $key")
            }
            restarted.drain(STOP_MS)
        }
    }

    @Test
    fun `a record reaches the disk while the head runs, with no stop`() {
        val dir = state.resolve("reasoning")
        val writes = lane(dir)
        val cache = ReasoningCache(writes = writes)

        cache.put("splice-a", listOf("call_a1"), listOf("ea1"))

        val file = dir.resolve("splice-a.jsonl")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS)
        while (!holds(file, "ea1") && System.nanoTime() < deadline) Thread.onSpinWait()
        assertTrue(holds(file, "ea1"), "the record was still only in memory after ${BOUND_SECONDS}s")
        writes.drain(STOP_MS)
    }

    @Test
    fun `after the head restarts, a session's next request carries the reasoning recorded while the disk was held`() {
        val dir = state.resolve("reasoning")
        val disk = HeldDisk()
        val writes = lane(dir, disk)
        val head = CacheHead(ReasoningCache(writes = writes))
        val sessions = Executors.newCachedThreadPool()
        try {
            val turnA = sessions.submit<TurnOutcome> { head.turn("session-a", "call_a", ClientCalls()) }
            assertTrue(disk.holding(), "session-a's reasoning never reached the disk")
            val turnB = sessions.submit<TurnOutcome> { head.turn("session-b", "call_b", ClientCalls()) }
            finished(turnA, "session-a's turn")
            finished(turnB, "session-b's turn")
        } finally {
            disk.release()
            sessions.shutdown()
        }
        writes.drain(STOP_MS)

        val restartedWrites = lane(dir)
        val restarted = CacheHead(ReasoningCache(writes = restartedWrites))
        assertEquals("cipher-call_a", restarted.reasoningBefore("session-a", "call_a"))
        assertEquals("cipher-call_b", restarted.reasoningBefore("session-b", "call_b"))
        restartedWrites.drain(STOP_MS)
    }

    /** Each of [THREADS] sessions acts [ACTIONS] times on [cache]: a round for one of [KEYS], or that conversation's
     *  compaction. Every session must finish without waiting on the disk. */
    private fun act(sessions: ExecutorService, cache: ReasoningCache, seed: Int, phase: Int, what: String) {
        val work = (0 until THREADS).map { thread ->
            sessions.submit<Unit> {
                val random = Random(seed * SEED_STRIDE + phase * THREADS + thread)
                repeat(ACTIONS) { n ->
                    val key = KEYS[random.nextInt(KEYS.size)]
                    if (random.nextInt(COMPACTION_ODDS) == 0) {
                        cache.dropConversation(key)
                    } else {
                        cache.put(key, listOf("call-$phase-$thread-$n"), listOf("e-$phase-$thread-$n"))
                    }
                }
            }
        }
        work.forEach { finished(it, what) }
    }

    /** Whether [thread] has parked on a wait or returned: either way it no longer runs toward its return. */
    private fun settled(thread: Thread): Boolean = thread.state in SETTLED

    private fun holds(file: Path, record: String): Boolean = Files.exists(file) && record in Files.readString(file)
}

private val KEYS = listOf("splice-a", "splice-b", "splice-c")
private val SETTLED = setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING, Thread.State.TERMINATED)
private const val SEEDS = 20
private const val THREADS = 4
private const val ACTIONS = 40
private const val ENTRY_BOUND = 4
private const val COMPACTION_ODDS = 5
private const val SEED_STRIDE = 1_000
