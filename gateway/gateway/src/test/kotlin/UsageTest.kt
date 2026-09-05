// PORT-OF: usage pins from server/test/{control-server,codex-proxy}.test.mjs @ pre-public-port-baseline —
// warn table (ratelimit priority, critical bounds, tokens5h fallback, none), payload aliases
// + non-standard context fields, output clamp with exact log line, 5h window pruning,
// ratelimit header parsing.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.Usage
import splice.core.usage.RateLimitState
import splice.core.usage.UsageWarnPolicy
import splice.core.util.LogSink
import splice.gateway.round.RoundUsage
import splice.gateway.usage.OutputClampPolicy
import splice.gateway.usage.UsageHud
import splice.gateway.usage.UsageJson
import splice.gateway.usage.UsageStore
import splice.gateway.wire.TurnWiring
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executors

private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

private val hud = UsageHud()
private val usageJson = UsageJson()

class UsageTest {

    @Test
    fun `warn - ratelimit signal has priority and bounds`() {
        val ninety = UsageWarnPolicy.computeUsageWarn(ratelimit = RateLimitState(1000, 100, "6m0s"), warnPct = 80)
        assertEquals("warn", ninety.level)
        assertEquals(90, ninety.pct)
        assertEquals("ratelimit", ninety.source)
        assertEquals("6m0s", ninety.reset)
        assertEquals("critical", UsageWarnPolicy.computeUsageWarn(ratelimit = RateLimitState(1000, 0, null)).level)
        assertEquals("critical", UsageWarnPolicy.computeUsageWarn(ratelimit = RateLimitState(1000, 15, null)).level)
        assertEquals("ok", UsageWarnPolicy.computeUsageWarn(ratelimit = RateLimitState(1000, 500, null)).level)
        // ratelimit present but incomplete -> falls through to tokens5h
        assertEquals(
            "tokens5h",
            UsageWarnPolicy.computeUsageWarn(
                outputTokens5h = 10,
                ratelimit = RateLimitState(null, null, null),
                warnTokens5h = 100,
            ).source,
        )
    }

    @Test
    fun `warn - tokens5h fallback thresholds and none`() {
        assertEquals("ok", UsageWarnPolicy.computeUsageWarn(outputTokens5h = 10, warnTokens5h = 100).level)
        assertEquals("warn", UsageWarnPolicy.computeUsageWarn(outputTokens5h = 85, warnTokens5h = 100).level)
        assertEquals("critical", UsageWarnPolicy.computeUsageWarn(outputTokens5h = 120, warnTokens5h = 100).level)
        assertEquals(100, UsageWarnPolicy.computeUsageWarn(outputTokens5h = 120, warnTokens5h = 100).pct)
        val none = UsageWarnPolicy.computeUsageWarn(outputTokens5h = 999_999, warnTokens5h = 0)
        assertEquals("ok", none.level)
        assertEquals("none", none.source)
    }

    // A search round re-POSTs the WHOLE conversation (tool_search_call/output appended to input),
    // so it obeys the exact same RoundUsage law fold/re-anchor rounds do: input/cached are this
    // round's OWN reading (last-round-wins), never summed — an inflated prompt count would fire
    // Claude Code's context bar / autocompact early, the regression this law prevents.
    @Test
    fun `a search round obeys the RoundUsage law - last-round input cached, summed output reasoning`() {
        var acc = RoundUsage()
        acc = acc.plusRound(Usage(inputTokens = 1000, outputTokens = 50, cachedTokens = 800, reasoningTokens = 20))
        acc = acc.plusRound(Usage(inputTokens = 1500, outputTokens = 30, cachedTokens = 1200, reasoningTokens = 10))
        val usage = acc.toUsage()
        assertEquals(1500, usage.inputTokens, "last round's input wins - never summed across rounds")
        assertEquals(1200, usage.cachedTokens, "last round's cached wins")
        assertEquals(80, usage.outputTokens, "output accrues per round")
        assertEquals(30, usage.reasoningTokens, "reasoning accrues per round")
    }

    @Test
    fun `payload - aliases, cached detail, and the non-standard context fields`() {
        val usage = usageJson.from(
            obj("""{"prompt_tokens":100,"completion_tokens":7,"input_tokens_details":{"cached_tokens":60}}"""),
        )
        assertEquals(100, usage.inputTokens)
        assertEquals(7, usage.outputTokens)
        assertEquals(60, usage.cacheReadInputTokens)
        val payload = hud.buildUsagePayload(usage, contextWindow = 272_000)
        assertEquals("100", payload["input_tokens"]?.jsonPrimitive?.content)
        assertEquals("272000", payload["context_window"]?.jsonPrimitive?.content)
        assertEquals("272000", payload["context_window_size"]?.jsonPrimitive?.content)
        val pct = payload["used_percentage"]?.jsonPrimitive?.content?.toDouble() ?: 0.0
        assertTrue(pct > 0.058 && pct < 0.06, "used pct = $pct") // 160/272000*100
        assertNull(hud.buildUsagePayload(usage, contextWindow = null)["context_window"])
    }

    @Test
    fun `payload - integral used_percentage serializes bare, JS-number parity`() {
        // The migration oracle byte-compares our SSE against the legacy Node reference, and
        // JSON.stringify prints an integral double bare ("0", never "0.0"). CX-19 replay
        // 2026-08-07: every streaming fixture diverged at exactly this byte.
        val zero = usageJson.from(obj("""{"input_tokens":0,"output_tokens":0}"""))
        val payload = hud.buildUsagePayload(zero, contextWindow = 272_000)
        assertEquals("0", payload["used_percentage"]?.jsonPrimitive?.content)
        val full = usageJson.from(obj("""{"input_tokens":136000,"output_tokens":0}"""))
        assertEquals(
            "50",
            hud.buildUsagePayload(full, contextWindow = 272_000)["used_percentage"]?.jsonPrimitive?.content,
        )
        val frac = usageJson.from(obj("""{"input_tokens":160,"output_tokens":0}"""))
        val content = hud.buildUsagePayload(frac, contextWindow = 272_000)["used_percentage"]?.jsonPrimitive?.content
        assertTrue(content!!.startsWith("0.0588"), "non-integral stays decimal: $content")
        // Below 1e-3 the JVM flips to E-notation where JS stays decimal — the basic fixture's
        // exact value (1 input token against the 272k default window).
        val tiny = usageJson.from(obj("""{"input_tokens":1,"output_tokens":0}"""))
        assertEquals(
            "0.0003676470588235294",
            hud.buildUsagePayload(tiny, contextWindow = 272_000)["used_percentage"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `cache log line format is exact`() {
        val line = hud.cacheLogLine(
            "codex-proxy",
            "gpt-5.6-sol",
            obj("""{"input_tokens":200,"input_tokens_details":{"cached_tokens":150},"output_tokens":9}"""),
            compact = true,
        )
        assertEquals(
            "[codex-proxy] cache: input=200 cached=150 hit=75% output=9 compact model=gpt-5.6-sol\n",
            line,
        )
    }

    @Test
    fun `output clamp - over clamps with the log line, under passes, null max passes`() {
        val logs = mutableListOf<String>()
        val clamp = OutputClampPolicy.makeOutputClamp(
            32_000,
            compact = false,
            headTag = "codex-proxy",
            log = { logs.add(it) },
        )
        assertEquals(32_000, clamp(200_000))
        assertTrue(logs.single().contains("output_tokens 200000 > client max_tokens 32000 compact=false"))
        assertEquals(10, clamp(10))
        val noMax = OutputClampPolicy.makeOutputClamp(null, compact = false, headTag = "t", log = { logs.add(it) })
        assertEquals(999_999, noMax(999_999))
    }

    @Test
    fun `usage store - 5h window prunes, sums, and same-instance ratelimit round-trips`(@TempDir tmp: Path) {
        var now = 10_000_000_000L
        val store = UsageStore(tmp.resolve("codex-usage.json"), tmp.resolve("codex-ratelimit.json"), clock = { now })
        store.appendOutputTokens(100)
        now += 1_000
        store.appendOutputTokens(50)
        // jump past the 5h window: the next append prunes both old entries
        now += 5 * 60 * 60 * 1000L
        store.appendOutputTokens(7)
        store.flushNow()
        val state = store.readState()
        assertEquals(1, state.entries)
        assertEquals(7, state.outputTokens5h)
        assertEquals(5, state.windowHours)

        store.persistRateLimit { name ->
            mapOf(
                "x-ratelimit-limit-tokens" to "5000",
                "x-ratelimit-remaining-tokens" to "1200",
                "x-ratelimit-reset-tokens" to "6m0s",
            )[name]
        }
        val rl = store.readRateLimit()!!
        assertEquals(5000, rl.limitTokens)
        assertEquals(1200, rl.remainingTokens)
        assertEquals("6m0s", rl.resetTokens)
        // absent limit header -> no-op (file unchanged)
        store.persistRateLimit { null }
        assertEquals(5000, store.readRateLimit()!!.limitTokens)
    }

    @Test
    fun `ratelimit persistence is durable across a fresh store and coalesces to the final value`(@TempDir tmp: Path) {
        // review gap J (core): the round-trip above reads from the SAME instance, so pending in-memory
        // state could satisfy it without anything reaching disk. Prove a FRESH store reads the persisted
        // headers, and that a burst of writes with no explicit flush between them coalesces to the LAST
        // value on disk (latest-wins). The scheduler/file-lane race bullets need an injectable seam
        // UsageStore does not expose (process-global AsyncFileIo) and remain out of scope here.
        val usageFile = tmp.resolve("usage.json")
        val rateFile = tmp.resolve("ratelimit.json")
        fun headers(limit: String, remaining: String, reset: String): (String) -> String? = { name ->
            mapOf(
                "x-ratelimit-limit-tokens" to limit,
                "x-ratelimit-remaining-tokens" to remaining,
                "x-ratelimit-reset-tokens" to reset,
            )[name]
        }
        val store = UsageStore(usageFile, rateFile)
        // A burst of successive updates before any explicit flush — latest must win.
        store.persistRateLimit(headers("5000", "4000", "6m0s"))
        store.persistRateLimit(headers("5000", "2500", "4m0s"))
        store.persistRateLimit(headers("5000", "900", "2m0s"))
        store.flushNow()

        val fresh = UsageStore(usageFile, rateFile).readRateLimit()!!
        assertEquals(5000, fresh.limitTokens)
        assertEquals(900, fresh.remainingTokens, "a burst must coalesce to the final value, on disk")
        assertEquals("2m0s", fresh.resetTokens)
    }

    // DR-127: flushRateLimit consumed the pending payload with getAndSet(null) BEFORE the disk
    // write could fail, so a throwing writer discarded the NEWEST rate-limit state with no log
    // and no retention — statusline/HUD served the stale older snapshot until the next round
    // carried headers. The sibling UsageRingFile.persistSnapshot keeps memory state and logs a
    // failure streak; this pins the same contract for the ratelimit lane.
    @Test
    fun `a failing ratelimit write retains the payload for the next flush - DR-127`(@TempDir tmp: Path) {
        val roDir = tmp.resolve("ro")
        Files.createDirectories(roDir)
        val rateFile = roDir.resolve("ratelimit.json")
        val store = UsageStore(tmp.resolve("usage.json"), rateFile)
        store.persistRateLimit { name ->
            mapOf(
                "x-ratelimit-limit-tokens" to "5000",
                "x-ratelimit-remaining-tokens" to "1200",
                "x-ratelimit-reset-tokens" to "6m0s",
            )[name]
        }
        Files.setPosixFilePermissions(roDir, PosixFilePermissions.fromString("r-x------"))
        try {
            store.flushNow()
            val retained = store.readRateLimit()
            assertEquals(
                1200,
                retained?.remainingTokens,
                "the failed write must not consume the newest snapshot",
            )
        } finally {
            Files.setPosixFilePermissions(roDir, PosixFilePermissions.fromString("rwx------"))
        }
        store.flushNow()
        val fresh = UsageStore(tmp.resolve("usage.json"), rateFile).readRateLimit()
        assertEquals(1200, fresh?.remainingTokens, "the retained payload must land on disk once the writer recovers")
    }

    @Test
    fun `concurrent completions aggregate and restart without lost usage`(@TempDir tmp: Path) {
        val usageFile = tmp.resolve("usage.json")
        val rateFile = tmp.resolve("ratelimit.json")
        val now = 20_000_000_000L
        val store = UsageStore(usageFile, rateFile, clock = { now })
        val pool = Executors.newFixedThreadPool(8)
        repeat(200) { pool.submit { store.appendOutputTokens(3) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        store.flushNow()

        assertEquals(600, store.readState().outputTokens5h)
        assertEquals(1, store.readState().entries, "same-minute turns should persist as one bounded bucket")
        val restarted = UsageStore(usageFile, rateFile, clock = { now })
        assertEquals(600, restarted.readState().outputTokens5h)
        assertEquals(1, restarted.readState().entries)
    }
}

/** The proxy seam that gives a picker row a window the CLIENT has no way to represent.
 *
 * Claude Code resolves a context window two ways only — `/\[1m\]/i` on the id -> 1e6, else the one
 * process-wide CLAUDE_CODE_MAX_CONTEXT_TOKENS, which the launch plants as a constant 1e6. It
 * compacts on `(input + cache_creation + cache_read) / window`, and splice writes that numerator,
 * which is every declared window's only possible source. These pin that the scale actually reaches
 * the payload, keyed on the RAW picker id (two rows can share one upstream id).
 */
class UsageScalingTest {

    private val wiring = TurnWiring()

    private val xai = ModelCatalog(
        discoveryPrefix = "claude-grok--",
        models = listOf(
            ModelEntry(id = "grok-4.6", contextWindow = 256_000),
            ModelEntry(id = "grok-4.6[500k]", contextWindow = 500_000),
            ModelEntry(id = "grok-4.3[1m]", contextWindow = 1_000_000),
        ),
        defaultContextWindow = 256_000,
        pinnedModel = "grok-4.6",
    )

    private fun meta(model: String) = TurnMeta(
        compact = false,
        showReasoning = ReasoningDisplay.TEXT,
        stream = true,
        originalModel = model,
        upstreamModel = xai.stripSuffixes(model),
        clientMaxTokens = null,
        effort = "high",
        summary = null,
        budgetTokens = null,
    )

    private fun payload(model: String, input: Long, cached: Long) =
        wiring.usagePayloadBuilder(xai, meta(model))(Usage(input, 7, cached))

    @Test
    fun `a scaled row logs its factor once per turn and an exact row logs nothing`() {
        val logged = mutableListOf<String>()
        val builder = TurnWiring(LogSink { logged += it }).usagePayloadBuilder(xai, meta("grok-4.6[500k]"))
        builder(Usage(1_000, 7, 200))
        builder(Usage(2_000, 7, 200))
        assertEquals(1, logged.count { it.contains("client-scaled") }, "one factor line per turn, got $logged")
        assertTrue(logged.single().contains("grok-4.6[500k]"), "the line names the row: $logged")

        val exact = mutableListOf<String>()
        TurnWiring(LogSink { exact += it }).usagePayloadBuilder(xai, meta("grok-4.3[1m]"))(Usage(1_000, 7, 200))
        assertTrue(exact.isEmpty(), "an exact row (declared 1e6 = the client's 1e6) must not log, got $exact")
    }

    @Test
    fun `the pinned row scales too - the client's constant 1e6 carries its declared 256k`() {
        // Since 2026-09-05 the launch plants a constant 1e6 client window, so the pinned row is no
        // longer exempt: real 100k of a 256k row must read as 39.06% of the client's 1e6, which is
        // what lets a TOML window edit reach this process without a relaunch.
        val p = payload("grok-4.6", input = 100_000, cached = 40_000)
        assertEquals(234_375, p["input_tokens"]?.jsonPrimitive?.content?.toLong(), "(100k - 40k) x 3.90625")
        assertEquals(156_250, p["cache_read_input_tokens"]?.jsonPrimitive?.content?.toLong(), "40k x 3.90625")
        assertEquals(1_000_000, p["context_window"]?.jsonPrimitive?.content?.toLong(), "what the client uses")
        assertEquals("39.0625", p["used_percentage"]?.jsonPrimitive?.content, "100k of the row's own 256k")
    }

    @Test
    fun `a 500k row doubles the reported counts so it compacts at a REAL 500k`() {
        // The client believes 1e6. Real 250k of context must read as 500k (50.0%), so the bar
        // fills at real 500k. Selectable live from /model.
        val p = payload("grok-4.6[500k]", input = 250_000, cached = 0)
        assertEquals(500_000, p["input_tokens"]?.jsonPrimitive?.content?.toLong())
        assertEquals(1_000_000, p["context_window"]?.jsonPrimitive?.content?.toLong(), "what the client uses")
        assertEquals("50", p["used_percentage"]?.jsonPrimitive?.content, "half of the row's own 500k")
    }

    // The scaled case above pins cached = 0 and the cache-bearing case is on the UNSCALED row, so
    // nothing pinned how cache_read behaves under scaling — and with prompt caching on, essentially
    // every real turn carries non-zero cached tokens, making the untested combination the production
    // shape rather than an edge case (review 2026-08-28, PR 99). BOTH terms scale, deliberately: the
    // ratio Claude Code compacts on is (input + cache_read)/window, so scaling only one half would
    // report 250k of real context as 69% of the client's 256k instead of the true 50%, and the row's
    // whole reason for existing is that that percentage is honest against ITS window. Scaled only
    // one half, 250k real on a 500k row would read 250k+200k = 45% of 1e6 instead of the true 50%.
    @Test
    fun `cache_read scales with input, so the compaction ratio is unchanged by the cached split`() {
        val p = payload("grok-4.6[500k]", input = 250_000, cached = 100_000)
        assertEquals(300_000, p["input_tokens"]?.jsonPrimitive?.content?.toLong(), "(250k - 100k) x 2.0")
        assertEquals(200_000, p["cache_read_input_tokens"]?.jsonPrimitive?.content?.toLong(), "100k x 2.0")
        assertEquals("50", p["used_percentage"]?.jsonPrimitive?.content, "same 50% as the cached=0 case")
    }

    @Test
    fun `output tokens are never scaled - they are not part of the context total`() {
        assertEquals(7, payload("grok-4.6[500k]", 250_000, 0)["output_tokens"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun `a 1m row rides Claude Code's own hook and is reported exactly`() {
        val p = payload("grok-4.3[1m]", input = 400_000, cached = 0)
        assertEquals(400_000, p["input_tokens"]?.jsonPrimitive?.content?.toLong())
        assertEquals(1_000_000, p["context_window"]?.jsonPrimitive?.content?.toLong())
    }

    // DR-41c: the ring's read side logged both its degradations while the persist side dropped a
    // failing write with no trace — the 5h window silently became memory-only until restart forgot
    // it. One line per failure STREAK (persist runs per usage event; a full disk must not
    // firehose), reset by the next success.
    @Test
    fun `a failing usage persist logs once per streak and re-arms after success`(@TempDir dir: Path) {
        // A read-only parent denies the staged temp file (writeAtomic0600 repairs a MISSING parent,
        // so absence is not an injection — denial is).
        val usageFile = dir.resolve("usage.json")
        val log = mutableListOf<String>()
        val ring = splice.gateway.usage.UsageRingFile(usageFile, Any(), LogSink { log += it })
        val denied = PosixFilePermissions.fromString("r-x------")
        val writable = PosixFilePermissions.fromString("rwx------")
        Files.setPosixFilePermissions(dir, denied)
        try {
            ring.persistSnapshot(listOf(kotlinx.serialization.json.buildJsonObject { }), version = 1)
            ring.persistSnapshot(listOf(kotlinx.serialization.json.buildJsonObject { }), version = 2)
            assertEquals(1, log.count { it.contains("persist FAILED") }, "one warning in first streak: $log")

            Files.setPosixFilePermissions(dir, writable)
            ring.persistSnapshot(listOf(kotlinx.serialization.json.buildJsonObject { }), version = 3)
            assertTrue(Files.exists(usageFile), "the recovery write must succeed before the next streak")

            Files.setPosixFilePermissions(dir, denied)
            ring.persistSnapshot(listOf(kotlinx.serialization.json.buildJsonObject { }), version = 4)
        } finally {
            Files.setPosixFilePermissions(dir, writable)
        }

        assertEquals(2, log.count { it.contains("persist FAILED") }, "success must re-arm the warning: $log")
    }

    // DR-58: the read-gate twin of DR-41c. A usage file behind a symlink whose target parent loses
    // read (a permissions blip) is PRESENT but inaccessible; the old Files.exists gate FOLLOWED the
    // link, read false, and returned empty with no log — the user's real 5h spend vanished from the
    // HUD silently. Direct-read reaches the AccessDenied and logs it; quiet empty is ONLY proven
    // absence (NoSuchFile with no NOFOLLOW entry — a dangling link logs, see its own arm below).
    @Test
    fun `an inaccessible usage file logs the read failure, not a silent empty - DR-58`(@TempDir dir: Path) {
        val externalDir = Files.createDirectories(dir.resolve("external"))
        val target = Files.writeString(externalDir.resolve("usage.json"), "[]")
        val link = dir.resolve("usage.json").also { Files.createSymbolicLink(it, target) }
        Files.setPosixFilePermissions(externalDir, PosixFilePermissions.fromString("---------"))
        val log = mutableListOf<String>()
        val ring = splice.gateway.usage.UsageRingFile(link, Any(), LogSink { log += it })
        try {
            assertTrue(ring.readEntriesFromDisk().isEmpty(), "an unreadable ring degrades to empty")
            assertEquals(
                1,
                log.count { it.contains("unreadable") && it.contains("5h window reset") },
                "an inaccessible usage file must log the drop, not read as silent absence: $log",
            )
        } finally {
            Files.setPosixFilePermissions(externalDir, PosixFilePermissions.fromString("rwx------"))
        }
    }

    // DR-58 companion (NEVER-BELOW-STATUS-QUO): a genuinely-absent usage file is the quiet first-run
    // empty — NoSuchFile AND no path entry is the one shape that must not warn. Guards the
    // direct-read from over-correcting into a log-everything firehose on the common cold-start path.
    @Test
    fun `a genuinely absent usage file reads empty and quiet - DR-58`(@TempDir dir: Path) {
        val log = mutableListOf<String>()
        val ring = splice.gateway.usage.UsageRingFile(dir.resolve("nope.json"), Any(), LogSink { log += it })
        assertTrue(ring.readEntriesFromDisk().isEmpty())
        assertTrue(log.isEmpty(), "genuine first-run absence must not warn: $log")
    }

    // DR-58 (codex class law): the usage file sits DIRECTLY under a dir whose search bit is gone —
    // no symlink. Any exists() pre-gate reads false through the untraversable parent; only the
    // direct read reaches the AccessDenied and logs the window reset.
    @Test
    fun `an untraversable usage-file parent logs the read failure - DR-58`(@TempDir dir: Path) {
        val externalDir = Files.createDirectories(dir.resolve("external"))
        val file = Files.writeString(externalDir.resolve("usage.json"), "[]")
        Files.setPosixFilePermissions(externalDir, PosixFilePermissions.fromString("---------"))
        val log = mutableListOf<String>()
        val ring = splice.gateway.usage.UsageRingFile(file, Any(), LogSink { log += it })
        try {
            assertTrue(ring.readEntriesFromDisk().isEmpty())
            assertEquals(1, log.count { it.contains("unreadable") }, "parent denial must log: $log")
        } finally {
            Files.setPosixFilePermissions(externalDir, PosixFilePermissions.fromString("rwx------"))
        }
    }

    // DR-58 (codex class law): a DANGLING usage symlink throws NoSuch on read, but the entry exists
    // — the ring's disk lane broke, which is not a quiet first run. exists(NOFOLLOW) disambiguates
    // the caught NoSuch only; unconditional NoSuch->quiet would silently reset the 5h window.
    @Test
    fun `a dangling usage symlink logs the read failure, not a quiet first run - DR-58`(@TempDir dir: Path) {
        val link = dir.resolve("usage.json").also { Files.createSymbolicLink(it, dir.resolve("never-created")) }
        val log = mutableListOf<String>()
        val ring = splice.gateway.usage.UsageRingFile(link, Any(), LogSink { log += it })
        assertTrue(ring.readEntriesFromDisk().isEmpty())
        assertEquals(1, log.count { it.contains("unreadable") }, "a dangling ring link must log: $log")
    }
}

// 2026-09-05, the second half of the constant-window move: a session launched before the constant
// existed still divides by its old env. The head learns that window from the session's status-line
// posts (ClientWindows) and scales THAT session's counts against it, so it compacts at the row's
// real window instead of a third of it — live, no relaunch.
class SessionWindowUsageTest {

    private val xai = ModelCatalog(
        discoveryPrefix = "claude-grok--",
        models = listOf(
            ModelEntry(id = "grok-4.6", contextWindow = 256_000),
            ModelEntry(id = "grok-4.3[1m]", contextWindow = 1_000_000),
        ),
        defaultContextWindow = 256_000,
        pinnedModel = "grok-4.6",
    )

    private fun meta(model: String) = TurnMeta(
        compact = false,
        showReasoning = ReasoningDisplay.TEXT,
        stream = true,
        originalModel = model,
        upstreamModel = xai.stripSuffixes(model),
        clientMaxTokens = null,
        effort = "high",
        summary = null,
        budgetTokens = null,
        sessionId = "s-old",
    )

    private fun payload(model: String, input: Long, cached: Long, sessionWindow: Long?) =
        TurnWiring().usagePayloadBuilder(xai, meta(model), sessionWindow)(Usage(input, 7, cached))

    @Test
    fun `a session's own window drives its scale and is what the payload declares`() {
        val p = payload("grok-4.6", input = 100_000, cached = 40_000, sessionWindow = 400_000)
        assertEquals(93_750, p["input_tokens"]?.jsonPrimitive?.content?.toLong(), "(100k - 40k) x 400k/256k")
        assertEquals(62_500, p["cache_read_input_tokens"]?.jsonPrimitive?.content?.toLong(), "40k x 400k/256k")
        assertEquals(400_000, p["context_window"]?.jsonPrimitive?.content?.toLong(), "the session's window")
        assertEquals("39.0625", p["used_percentage"]?.jsonPrimitive?.content, "100k of the row's own 256k")
    }

    @Test
    fun `a session already at the row's window rides raw and an unknown session gets the constant`() {
        val exact = payload("grok-4.6", input = 100_000, cached = 40_000, sessionWindow = 256_000)
        assertEquals(60_000, exact["input_tokens"]?.jsonPrimitive?.content?.toLong())
        assertEquals(256_000, exact["context_window"]?.jsonPrimitive?.content?.toLong())
        val unknown = payload("grok-4.6", input = 100_000, cached = 40_000, sessionWindow = null)
        assertEquals(1_000_000, unknown["context_window"]?.jsonPrimitive?.content?.toLong(), "the launch constant")
        assertEquals("39.0625", unknown["used_percentage"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a 1m row ignores the session window - the client sizes it from the id`() {
        val p = payload("grok-4.3[1m]", input = 100_000, cached = 0, sessionWindow = 400_000)
        assertEquals(100_000, p["input_tokens"]?.jsonPrimitive?.content?.toLong())
        assertEquals(1_000_000, p["context_window"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun `the factor line says whose window it used`() {
        val logged = mutableListOf<String>()
        TurnWiring(LogSink { logged += it }).usagePayloadBuilder(xai, meta("grok-4.6"), 400_000)(Usage(1_000, 7, 0))
        assertTrue(logged.single().contains("the session's own"), logged.toString())
        val constant = mutableListOf<String>()
        TurnWiring(LogSink { constant += it }).usagePayloadBuilder(xai, meta("grok-4.6"), null)(Usage(1_000, 7, 0))
        assertTrue(constant.single().contains("the launch constant"), constant.toString())
    }
}
