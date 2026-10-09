// NEW: code-mode records are kept per conversation, not 128 for the whole head. A
// conversation's records are placed in its history in order, each on top of the one before
// (CodexCodeModeHistory.canonicalize), so a record that goes takes every later one of its
// conversation with it: the callbacks stay in history as bare toolu_splice_* calls with no splice_exec
// wrapper. Live on 2026-09-26, 128 records covered 80 minutes across 18 conversations, and the day
// logged 74 'history rewrite skipped' and 25 'abandoned record' lines.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private const val START = 1_790_000_000_000L
private const val RECORDS_PER_CONVERSATION = 10

/** A clock the test moves by hand. */
private class HandClock(var now: Long) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(now)
}

class CodeModeConversationRetentionTest {

    @TempDir
    lateinit var tempDir: Path

    private val clock = HandClock(START)
    private val logs = mutableListOf<String>()

    private fun registry(retention: CodeModeRetention = CodeModeRetention()) =
        CodexCodeModeRegistry(
            CodeModeBridgeConfig(
                { error("no script runs in a registry test") },
                CodeModeStateLocation(tempDir.resolve("code-mode"), tempDir.resolve("state.json")),
                retention = retention,
                clock = clock,
                log = LogSink { logs += it },
            ),
            Json { encodeDefaults = true },
            5.minutes,
        )

    /** Admits script [n] of [conversation], one minute after the last one, and completes it unless it is
     *  to stay [running]. */
    private fun CodexCodeModeRegistry.script(
        conversation: Int,
        n: Int,
        source: String = "text('$conversation/$n')",
        running: Boolean = false,
    ): CodeModeRecord {
        clock.now += 1.minutes.inWholeMilliseconds
        val record = CodeModeRecord(
            id = "c$conversation-s$n",
            key = "conversation-$conversation",
            outer = JsonObject(emptyMap()),
            outerCallId = "outer-$conversation-$n",
            source = source,
            phase = CodeModePhase.LOST,
            updatedAt = clock.now,
            lastDigest = "digest-$conversation-$n",
            baselineInputCount = n,
            baselineInputDigest = "input-$conversation-$n",
            metadataVersion = CODE_MODE_METADATA_VERSION,
            baselineLogicalCount = n,
            baselineLogicalDigest = "logical-$conversation-$n",
            nativeSegments = emptyList(),
            continuity = emptyList(),
            continuityReplay = emptyList(),
        )
        assertTrue(add(record), "script $n of conversation $conversation was refused")
        if (!running) complete(record, "done $conversation/$n")
        return record
    }

    private fun CodexCodeModeRegistry.kept(conversation: Int): List<String> =
        completed("conversation-$conversation").map(CodeModeRecord::id)

    /** Every conversation in [conversations] holds all its records or none: never a part. */
    private fun CodexCodeModeRegistry.wholeOrGone(conversations: IntRange) = conversations.forEach { c ->
        val kept = kept(c).size
        assertTrue(kept == 0 || kept == RECORDS_PER_CONVERSATION, "conversation $c kept $kept records")
    }

    @Test
    fun `the head's count takes whole conversations, least recently used first, never the live one`() {
        val registry = registry(CodeModeRetention(records = 40))
        (1..8).forEach { other ->
            registry.script(0, other)
            repeat(RECORDS_PER_CONVERSATION) { n -> registry.script(other, n) }
        }

        assertEquals((1..8).map { "c0-s$it" }, registry.kept(0), "the live conversation lost records")
        registry.wholeOrGone(1..8)
        assertEquals(emptyList<String>(), registry.kept(1), "the least recently used conversation stayed")
        assertEquals(RECORDS_PER_CONVERSATION, registry.kept(8).size, "the newest conversation went")
        assertTrue(logs.any { "conversation conversa: 10 record(s)" in it && "least recently used" in it }, "$logs")
    }

    @Test
    fun `a conversation with a running script keeps even its finished records when the head is full`() {
        val registry = registry(CodeModeRetention(records = 12))
        repeat(5) { n -> registry.script(1, n) }
        registry.script(1, 5, running = true)
        repeat(5) { n -> registry.script(2, n) }

        registry.script(3, 0)
        registry.script(3, 1)

        assertEquals((0..4).map { "c1-s$it" }, registry.kept(1), "the running script's history was let go under it")
        assertEquals(emptyList<String>(), registry.kept(2), "the idle conversation stayed")
    }

    @Test
    fun `the head's bytes take whole conversations, and never refuse a script`() {
        val big = "x".repeat(10_000)
        val registry = registry(CodeModeRetention(bytes = 250_000))
        (1..6).forEach { other ->
            registry.script(0, other)
            repeat(RECORDS_PER_CONVERSATION) { n -> registry.script(other, n, source = big) }
        }

        assertEquals((1..6).map { "c0-s$it" }, registry.kept(0), "the live conversation lost records")
        registry.wholeOrGone(1..6)
        assertEquals(emptyList<String>(), registry.kept(1), "the byte bound let nothing go")
        assertEquals(RECORDS_PER_CONVERSATION, registry.kept(6).size, "the newest conversation went")
    }

    @Test
    fun `a conversation that alone reached its bound lets its finished records go together at its turn`() {
        val registry = registry(CodeModeRetention(perConversation = 3))
        repeat(3) { n -> registry.script(0, n) }
        repeat(2) { n -> registry.script(1, n) }
        val running = registry.script(1, 2, running = true)

        registry.turnStart.begin("conversation-1")
        assertEquals(listOf("c1-s0", "c1-s1"), registry.kept(1), "a conversation with a running script let go")

        registry.turnStart.begin("conversation-0")
        assertEquals(emptyList<String>(), registry.kept(0), "the conversation at its bound kept records")
        assertEquals(1, logs.count { "let go (it reached 3 records)" in it }, "$logs")

        registry.complete(running, "done")
        registry.turnStart.begin("conversation-1")
        assertEquals(emptyList<String>(), registry.kept(1))
        registry.script(1, 3)
        assertEquals(listOf("c1-s3"), registry.kept(1), "the script after the let-go was not kept")
    }

    @Test
    fun `a conversation that alone holds more than the head's bytes lets go at its turn, not at an admission`() {
        val big = "x".repeat(10_000)
        val registry = registry(CodeModeRetention(bytes = 30_000))
        repeat(4) { n -> registry.script(0, n, source = big) }
        assertEquals(4, registry.kept(0).size, "an admission let the admitting conversation's records go")

        registry.turnStart.begin("conversation-0")

        assertEquals(emptyList<String>(), registry.kept(0))
        assertTrue(logs.any { "it alone holds more than 30000 bytes" in it }, "$logs")
    }

    @Test
    fun `a restart over the bounds keeps the most recently used conversations whole`() {
        val first = registry()
        (1..5).forEach { c -> repeat(RECORDS_PER_CONVERSATION) { n -> first.script(c, n) } }

        val restarted = registry(CodeModeRetention(records = 25))

        restarted.wholeOrGone(1..5)
        assertEquals(listOf(0, 0, 0, 10, 10), (1..5).map { restarted.kept(it).size })
    }

    @Test
    fun `restart removes an oversized newest conversation before an older small one`() {
        val original = registry()
        original.script(2, 0, source = "small")
        original.script(1, 0, source = "x".repeat(40_000))

        val restarted = registry(CodeModeRetention(bytes = 30_000))

        assertEquals(emptyList<String>(), restarted.kept(1), "the newest conversation exceeds the bound alone")
        assertEquals(listOf("c2-s0"), restarted.kept(2), "a small older conversation remains")
    }

    @Test
    fun `a live conversation keeps every record while eighteen other conversations fill the head`() {
        val registry = registry()
        // Conversation 0 runs a script, then two other conversations run all ten of theirs, nine times
        // over; conversation 0's tenth script is the newest record the head holds.
        repeat(RECORDS_PER_CONVERSATION) { round ->
            registry.script(0, round)
            if (round < RECORDS_PER_CONVERSATION - 1) {
                listOf(1 + 2 * round, 2 + 2 * round).forEach { other ->
                    repeat(RECORDS_PER_CONVERSATION) { n -> registry.script(other, n) }
                }
            }
        }

        val live = registry.kept(0)
        assertEquals(
            (0 until RECORDS_PER_CONVERSATION).map { "c0-s$it" },
            live,
            "the live conversation kept ${live.size} of its $RECORDS_PER_CONVERSATION records",
        )
        (1..18).forEach { other ->
            assertEquals(RECORDS_PER_CONVERSATION, registry.kept(other).size, "conversation $other")
        }
    }

    @Test
    fun `a conversation still in use keeps its first record past 24 hours, and an abandoned one ages out`() {
        val registry = registry()
        registry.script(0, 0)
        registry.script(1, 0)
        clock.now = START + 23.hours.inWholeMilliseconds
        registry.script(0, 1)

        // Conversation 1's one script ran at START + 2 min (each script moves the clock a minute).
        clock.now = START + 24.hours.inWholeMilliseconds + 3.minutes.inWholeMilliseconds

        assertEquals(listOf("c0-s0", "c0-s1"), registry.kept(0), "the conversation used an hour ago lost a record")
        assertEquals(emptyList<String>(), registry.kept(1), "the conversation abandoned 24 hours ago was kept")
    }

    @Test
    fun `a conversation that keeps taking turns without a script keeps its records past 24 hours`() {
        val registry = registry()
        registry.script(0, 0)
        registry.script(1, 0)
        // A turn that runs no script still replays conversation 0's script, so it is a use.
        clock.now = START + 12.hours.inWholeMilliseconds
        registry.turnStart.begin("conversation-0")

        clock.now = START + 24.hours.inWholeMilliseconds + 3.minutes.inWholeMilliseconds

        assertEquals(listOf("c0-s0"), registry.kept(0), "the conversation used 12 hours ago lost its script")
        assertEquals(emptyList<String>(), registry.kept(1), "the conversation abandoned 24 hours ago was kept")
        registry.onHeadStop()
        assertEquals(listOf("c0-s0"), registry().kept(0), "a restart forgot the turn that used the conversation")
    }

    @Test
    fun `the turn that uses a conversation writes nothing, so a large one is never refused for it`() {
        val registry = registry()
        registry.script(0, 0)
        val written = journalBytes()
        clock.now += 2.hours.inWholeMilliseconds

        registry.turnStart.begin("conversation-0")

        assertEquals(written, journalBytes(), "the turn saved the conversation it only used")
    }

    private fun journalBytes(): Long = Files.list(tempDir.resolve("code-mode")).use { files ->
        files.filter { it.fileName.toString().endsWith(".jsonl") }.toList().sumOf(Files::size)
    }
}
