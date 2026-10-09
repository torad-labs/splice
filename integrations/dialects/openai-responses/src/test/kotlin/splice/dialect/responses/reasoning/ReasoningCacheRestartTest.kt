// NEW: V4-334 — GPT's reasoning outlives a daemon restart, an idle pause and a neighbor session. codex-rs
// replays every reasoning item until compaction (context_manager/history.rs:944-960); splice held them in
// an in-memory map with a 30-minute idle expiry, so after each of ten restarts on 2026-09-26 not one
// pre-restart tool step in three live claudex histories carried its reasoning (0/634, 0/389, 0/14).
// Every cell drives the real provider: its stream translator captures a round, and a request it builds
// either carries that round's reasoning immediately before the round's function_call or does not.
package splice.dialect.responses.reasoning

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
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
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.index.WireBlockIndex
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnOutcome
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRoundTripQuirks
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.TurnSignals
import splice.upstream.sse.WireSink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

private object RestartAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok", "acct")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "stub")
}

/** One daemon's view of a claudex head: the shipped quirks (the reasoning cache on), client replay off,
 *  and the head's state dir — a second instance over the same dir is the restarted daemon. */
private class RestartProbe(
    stateDir: Path?,
    logs: MutableList<String> = mutableListOf(),
    cacheOn: Boolean = true,
) : ResponsesProvider(
    tuning = ProviderTuning(
        name = ProviderName(key = "claudex", label = "claudex"),
        catalog = ModelCatalog(
            discoveryPrefix = "claude-codex",
            models = listOf(ModelEntry(id = "gpt-5.6-sol", label = "sol", contextWindow = 400_000)),
            defaultContextWindow = 400_000,
        ),
        pinnedModel = "gpt-5.6-sol",
        auth = RestartAuth,
        locations = ProviderLocations(
            baseUrl = "https://chatgpt.com/backend-api/codex",
            stateDir = stateDir,
        ),
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
    ),
    reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, null, null),
    quirks = ResponsesQuirks(
        providerTag = "claudex",
        roundTrip = ResponsesRoundTripQuirks(
            reasoningCache = cacheOn,
        ),
    ),
    log = { logs.add(it) },
) {
    override fun extraHeaders(creds: Credentials): Map<String, String> = emptyMap()
}

private object QuietSink : WireSink {
    private var next = 0
    override suspend fun openText(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(next++)
    override suspend fun openTool(id: String, name: String): WireBlockIndex = WireBlockIndex(next++)
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

/** The upstream stream of one tool round: its reasoning item, then the call it planned. */
private fun toolRound(callId: String, cipher: String) = listOf(
    ev("""{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning"}}"""),
    ev(
        """{"type":"response.output_item.done","output_index":0,""" +
            """"item":{"type":"reasoning","id":"rs_$callId","encrypted_content":"$cipher","summary":[]}}""",
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

/** A compaction's answer: text, completed. */
private val summaryAnswer = listOf(
    ev("""{"type":"response.output_item.added","output_index":0,"item":{"type":"message","role":"assistant"}}"""),
    ev("""{"type":"response.output_text.delta","output_index":0,"delta":"the summary"}"""),
    ev("""{"type":"response.output_item.done","output_index":0,"item":{"type":"message","role":"assistant"}}"""),
    ev("""{"type":"response.completed","response":{"id":"r_sum","usage":{"input_tokens":100,"output_tokens":7}}}"""),
)

/** The client's request after the round's tool ran: the opening, the call, its result. */
private fun afterCall(callId: String) = AnthropicParse.parseAnthropicBody(
    """{"model":"gpt-5.6-sol","max_tokens":1000,"messages":[
        {"role":"user","content":"$OPENING"},
        {"role":"assistant","content":[{"type":"tool_use","id":"$callId","name":"run","input":{}}]},
        {"role":"user","content":[{"type":"tool_result","tool_use_id":"$callId","content":"ok"}]}
    ],$TOOLS}""",
)

private val opening = AnthropicParse.parseAnthropicBody(
    """{"model":"gpt-5.6-sol","max_tokens":1000,"messages":[{"role":"user","content":"$OPENING"}],$TOOLS}""",
)

private val signals = TurnSignals(watchdogFired = { null }, clientGone = { false })

/** Drive one upstream round through [provider] exactly as the head does: build, then translate. */
private suspend fun drive(
    provider: ResponsesProvider,
    session: String?,
    frames: List<JsonObject>,
    compact: Boolean = false,
    body: splice.core.parse.AnthropicTurnBody = opening,
): TurnOutcome {
    val built = provider.buildTurn(body, compact = compact, sessionId = session)
    return provider.streamTranslator(built.meta, signals).driveTurn(frames.asFlow(), QuietSink)
}

/** The ciphertext of the reasoning item the built request carries right before [callId]'s
 *  function_call, or null when that call goes out with no reasoning in front of it. */
private fun reasoningBefore(provider: ResponsesProvider, session: String?, callId: String): String? {
    val input = provider.buildTurn(afterCall(callId), compact = false, sessionId = session)
        .requestBody.getValue("input").jsonArray.map { it.jsonObject }
    val at = input.indexOfFirst {
        it["type"]?.jsonPrimitive?.content == "function_call" && it["call_id"]?.jsonPrimitive?.content == callId
    }
    check(at >= 0) { "the request carries no function_call $callId: $input" }
    val before = input.getOrNull(at - 1)?.takeIf { it["type"]?.jsonPrimitive?.content == "reasoning" }
    return before?.get("encrypted_content")?.jsonPrimitive?.content
}

class ReasoningCacheRestartTest(@param:TempDir private val state: Path) {

    @Test
    fun `a restarted daemon injects the reasoning the last one recorded, for the same session`() = runTest {
        assertTrue(drive(RestartProbe(state), "session-1", toolRound("call_1", "cipher-1")) is TurnOutcome.Success)

        val restarted = RestartProbe(state)

        assertEquals("cipher-1", reasoningBefore(restarted, "session-1", "call_1"))
    }

    @Test
    fun `a client that sends no session id finds its reasoning after a restart by its opening alone`() = runTest {
        drive(RestartProbe(state), null, toolRound("call_1", "cipher-1"))

        assertEquals("cipher-1", reasoningBefore(RestartProbe(state), null, "call_1"))
    }

    @Test
    fun `a conversation idle for 31 minutes still injects every round`() {
        var now = 0L
        val cache = ReasoningCache(clock = { now })
        cache.put("splice-conv", listOf("call_1"), listOf("e1"))

        now = 31 * 60 * 1000L

        assertEquals(listOf("e1"), cache.snapshot("splice-conv")["call_1"], "31 minutes idle expired the conversation")
    }

    @Test
    fun `two sessions with a byte-identical opening hold separate conversations`() = runTest {
        val daemon = RestartProbe(state)
        drive(daemon, "session-a", toolRound("call_1", "cipher-a"))

        assertEquals(null, reasoningBefore(daemon, "session-b", "call_1"), "session-b was handed session-a's reasoning")
        assertEquals("cipher-a", reasoningBefore(daemon, "session-a", "call_1"))
    }

    @Test
    fun `a compaction that succeeds ends its conversation, on disk too, and spares the neighbor session`() = runTest {
        val daemon = RestartProbe(state)
        drive(daemon, "session-a", toolRound("call_1", "cipher-a"))
        drive(daemon, "session-b", toolRound("call_2", "cipher-b"))

        val compaction = drive(daemon, "session-a", summaryAnswer, compact = true, body = afterCall("call_1"))
        assertTrue(compaction is TurnOutcome.Success)

        assertEquals(null, reasoningBefore(daemon, "session-a", "call_1"), "the compacted conversation still injects")
        val restarted = RestartProbe(state)
        assertEquals(null, reasoningBefore(restarted, "session-a", "call_1"), "its file outlived the compaction")
        assertEquals("cipher-b", reasoningBefore(RestartProbe(state), "session-b", "call_2"))
    }

    @Test
    fun `a compaction that fails keeps its conversation's reasoning`() = runTest {
        val daemon = RestartProbe(state)
        drive(daemon, "session-a", toolRound("call_1", "cipher-a"))
        val failed = listOf(
            ev("""{"type":"response.failed","response":{"error":{"code":"server_error","message":"boom"}}}"""),
        )

        val compaction = drive(daemon, "session-a", failed, compact = true, body = afterCall("call_1"))
        assertFalse(compaction is TurnOutcome.Success)

        assertEquals("cipher-a", reasoningBefore(daemon, "session-a", "call_1"))
    }

    @Test
    fun `the store is owner-only - its directory 0700 and each conversation file 0600`() = runTest {
        drive(RestartProbe(state), "session-1", toolRound("call_1", "cipher-1"))

        val dirs = Files.walk(state).use { walk -> walk.filter { Files.isDirectory(it) && it != state }.toList() }
        val files = conversationFiles()
        assertEquals(1, files.size, "one conversation, one file: $files")
        assertTrue(dirs.isNotEmpty())
        dirs.forEach { assertEquals("rwx------", mode(it), "$it") }
        assertEquals("rw-------", mode(files.single()))
    }

    @Test
    fun `a corrupt conversation file degrades to no injection, says so, and is removed`() = runTest {
        drive(RestartProbe(state), "session-1", toolRound("call_1", "cipher-1"))
        val file = conversationFiles().single()
        file.writeText("{\"ids\":[\"call_1\"],\"envelopes\":[\"e\"]}\n{not json")
        val logs = mutableListOf<String>()

        assertEquals(null, reasoningBefore(RestartProbe(state, logs), "session-1", "call_1"))

        val named = logs.any { "[reasoning-cache]" in it && file.fileName.toString() in it }
        assertTrue(named, "no line names the file: $logs")
        assertTrue(file.parent.listDirectoryEntries().isEmpty(), "the corrupt file or its lock stayed")
    }

    @Test
    fun `a head that turns the cache off drops what an earlier start kept`() = runTest {
        drive(RestartProbe(state), "session-1", toolRound("call_1", "cipher-1"))
        val file = conversationFiles().single()

        RestartProbe(state, cacheOn = false)

        assertFalse(Files.exists(file), "a head with the cache off kept its conversations")
    }

    private fun conversationFiles(): List<Path> =
        Files.walk(state).use { walk -> walk.filter { it.fileName.toString().endsWith(".jsonl") }.toList() }

    private fun mode(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    @Test
    fun `a head with no state dir keeps today's in-memory cache and writes nothing`() = runTest {
        val daemon = RestartProbe(stateDir = null)
        drive(daemon, "session-1", toolRound("call_1", "cipher-1"))

        assertEquals("cipher-1", reasoningBefore(daemon, "session-1", "call_1"))
        assertEquals(null, reasoningBefore(RestartProbe(stateDir = null), "session-1", "call_1"))
    }
}

// The store under the provider: what a restart restores, and what the disk may never hold.
class ReasoningCacheFilesTest(@TempDir tempDir: Path) {
    private val dir: Path = tempDir.resolve("reasoning")
    private val logs = mutableListOf<String>()

    private fun cache(maxTotalBytes: Long = 64L * 1024 * 1024) = ReasoningCache(
        maxTotalBytes = maxTotalBytes,
        clock = { 0L },
        files = ReasoningCacheFiles(dir) { logs.add(it) },
    )

    @Test
    fun `a restart over the bound keeps the most recently written conversations and deletes the rest`() {
        val first = cache()
        first.put("conv-old", listOf("call_1"), listOf("aaaaa"))
        first.put("conv-new", listOf("call_2"), listOf("bbbbb"))
        Files.setLastModifiedTime(dir.resolve("conv-old.jsonl"), FileTime.fromMillis(1_000))
        Files.setLastModifiedTime(dir.resolve("conv-new.jsonl"), FileTime.fromMillis(2_000))

        val restarted = cache(maxTotalBytes = 6)

        assertEquals(listOf("bbbbb"), restarted.lookup("conv-new", "call_2"))
        assertEquals(null, restarted.lookup("conv-old", "call_1"))
        assertFalse(Files.exists(dir.resolve("conv-old.jsonl")), "the evicted conversation's file stayed")
    }

    @Test
    fun `a round the disk refused is served from memory, and its conversation never lands half on disk`() {
        val c = cache()
        c.put("conv-a", listOf("call_0"), listOf("e0"))
        c.dropConversation("conv-a") // the directory exists, empty
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"))
        c.put("conv-a", listOf("call_1"), listOf("e1"))
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        c.put("conv-a", listOf("call_2"), listOf("e2"))

        assertEquals(listOf("e1"), c.lookup("conv-a", "call_1"))
        assertEquals(listOf("e2"), c.lookup("conv-a", "call_2"))
        assertFalse(Files.exists(dir.resolve("conv-a.jsonl")), "round 2 was written without round 1")
        assertTrue(logs.any { "not written" in it }, "$logs")
        assertEquals(null, cache().lookup("conv-a", "call_2"), "a restart found half a conversation")
    }

    @Test
    fun `a conversation begun again after it ended is written again from its first round`() {
        val c = cache()
        c.put("conv-a", listOf("call_1"), listOf("e1"))
        c.dropConversation("conv-a")
        c.put("conv-a", listOf("call_2"), listOf("e2"))

        val restarted = cache()

        assertEquals(null, restarted.lookup("conv-a", "call_1"))
        assertEquals(listOf("e2"), restarted.lookup("conv-a", "call_2"))
    }

    @Test
    fun `the session is part of the conversation, hashed before it can reach a file name`() {
        val policy = ReasoningCachePolicy()
        val opening = "splice-0123456789abcdef0123456789abcdef"

        val keyed = policy.conversationKey("../../etc/passwd", opening)

        assertTrue(checkNotNull(keyed).matches(Regex("$opening-[0-9a-f]{16}")), keyed)
        assertTrue(policy.conversationKey("session-b", opening) != keyed)
        assertEquals(keyed, policy.conversationKey("../../etc/passwd", opening), "the same session keys the same")
        assertEquals(opening, policy.conversationKey(null, opening))
        assertEquals(opening, policy.conversationKey("", opening))
        assertEquals(null, policy.conversationKey("session-a", null))
    }
}
