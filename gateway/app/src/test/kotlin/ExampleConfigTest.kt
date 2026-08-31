// NEW: the committed config/splice.example.toml is a TESTED artifact, not aspirational docs — it
// must parse and yield the three documented heads with the right dialects/auth. If someone edits
// the example into an invalid shape, this fails.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.TomlStructurePreflight
import splice.app.TopologyLoader
import splice.core.config.knobsByKey
import splice.core.topology.Dialect
import splice.core.topology.HeadModel
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration

class ExampleConfigTest {

    private fun exampleToml(): String {
        // walk up from the gateway module dir to the repo root
        var dir = Paths.get("").toAbsolutePath()
        repeat(4) {
            val candidate = dir.resolve("config").resolve("splice.example.toml")
            if (Files.exists(candidate)) return Files.readString(candidate)
            dir = dir.parent ?: return@repeat
        }
        error("config/splice.example.toml not found from ${Paths.get("").toAbsolutePath()}")
    }

    @Test
    fun `example topology parses into the documented heads`() {
        val topology = TopologyLoader.parse(exampleToml())
        assertEquals(
            setOf("claudex", "claude-grok", "openrouter", "fireworks", "claude-kimi", "claude-splice"),
            topology.heads.keys,
        )
        assertEquals(3096, topology.daemon.controlPort)

        val codex = topology.providers[topology.heads["claudex"]!!.provider]!!
        assertEquals(Dialect.OPENAI_RESPONSES, codex.dialect)
        assertEquals("chatgpt-oauth", codex.auth.kind)

        val xai = topology.providers[topology.heads["claude-grok"]!!.provider]!!
        assertEquals(Dialect.OPENAI_RESPONSES, xai.dialect)
        assertEquals("grok-oauth", xai.auth.kind)
        assertEquals("session-id", xai.quirks.cacheKey)
        // reasoning_cache round-trip (review 2026-07-24): a NON-default value must reach the
        // parsed field — the 2026-07-18 audit found five decorative quirks; never again silently
        assertEquals(false, xai.quirks.reasoningCache)

        val openrouter = topology.providers[topology.heads["openrouter"]!!.provider]!!
        assertEquals(Dialect.OPENAI_CHAT, openrouter.dialect)
        assertEquals("api-key", openrouter.auth.kind)
        assertEquals(null, openrouter.quirks.reasoningEffort)

        val fireworks = topology.providers[topology.heads["fireworks"]!!.provider]!!
        assertEquals(Dialect.OPENAI_CHAT, fireworks.dialect)
        assertEquals("api-key", fireworks.auth.kind)
        // reasoning_effort round-trip (issue #21): a NON-default value must reach the parsed field —
        // the reasoning_cache precedent (2026-07-18 audit) is exactly this failure mode recurring.
        assertEquals(false, fireworks.quirks.reasoningEffort)

        val kimi = topology.providers[topology.heads["claude-kimi"]!!.provider]!!
        assertEquals(Dialect.ANTHROPIC_PASSTHROUGH, kimi.dialect)
        assertEquals("kimi-oauth", kimi.auth.kind)
        assertEquals("k3[1m]", topology.heads["claude-kimi"]!!.pinnedModel)
        // The passthrough dialect is faithful by DEFAULT (campaign claude-head): every deformation
        // Kimi needs is now DECLARED, and the same round-trip law as reasoning_cache above applies —
        // a knob that never reaches the parsed field is decorative, which is how five of them
        // shipped once already. Values, not just presence.
        assertEquals(true, kimi.quirks.mfjs)
        assertEquals(true, kimi.quirks.stripCacheControl)
        assertEquals(true, kimi.quirks.synthesizeSignatures)
        assertEquals(true, kimi.quirks.mapThinkingAdaptive)
        assertEquals(
            listOf("text", "image", "thinking", "tool_use", "tool_result", "server_tool_use", "web_search_tool_result"),
            kimi.quirks.blockAllowlist,
        )
        // Static vendor headers as pure TOML — the seam that lets an anthropic-compatible vendor
        // need no provider code at all.
        assertEquals("2023-06-01", kimi.staticHeaders["anthropic-version"])
        assertEquals("KimiCLI/1.5", kimi.staticHeaders["User-Agent"])
        // A head that declares nothing must stay faithful: the openai-dialect providers above carry
        // no passthrough knobs, and their defaults are the neutral (false/absent) ones.
        assertNull(codex.quirks.mfjs)
        assertNull(codex.quirks.stripCacheControl)
        assertNull(codex.quirks.blockAllowlist)

        // the isolate override survives the round-trip
        assertTrue(topology.heads["claude-grok"]!!.claude.isolate.contains("commands"))
        assertEquals("claudex", topology.heads["claudex"]!!.claude.command)
    }

    @Test
    fun `an explicit empty head model list is rejected rather than exposing the provider`() {
        val toml = exampleToml().replace(
            "models = [{ id = \"grok-4.6\", slot = \"opus\" }, { id = \"grok-4.5\", slot = \"sonnet\" }, " +
                "{ id = \"grok-build-latest\", slot = \"haiku\" }]",
            "models = []",
        )
        val topology = TopologyLoader.parse(toml)
        val head = topology.heads.getValue("claude-grok")
        val provider = topology.providers.getValue(head.provider)

        assertEquals(emptyList<HeadModel>(), head.models)
        assertThrows(IllegalArgumentException::class.java) { provider.catalogFor(head) }
    }

    // DR-43: undeclared tiers 400 cleanly pre-upstream (background titling / --model <tier>),
    // characterized by the roster lens. The example's DECISIONS are pinned in both directions:
    // grok serves a genuine fast tier (grok-build as haiku, no invented fable), and the
    // single-model heads stay opus-only ON PURPOSE with the degradation documented in the TOML.
    @Test
    fun `example heads declare the tier decision - grok gains haiku, single-model heads stay opus-only`() {
        val topology = TopologyLoader.parse(exampleToml())
        fun slots(head: String) = topology.heads.getValue(head).models.orEmpty().mapNotNull { it.slot }

        assertEquals(listOf("opus", "sonnet", "haiku"), slots("claude-grok"))
        assertEquals(
            "grok-build-latest",
            topology.heads.getValue("claude-grok").models.orEmpty().first { it.slot == "haiku" }.id,
        )
        for (head in listOf("openrouter", "fireworks", "claude-kimi")) {
            assertEquals(listOf("opus"), slots(head), "$head is opus-only by documented choice")
        }
    }

    // DR-43 redo (codex denominator catch): the documented-degradation set is FOUR local decisions —
    // grok's declined-fable marker plus three opus-only markers — anchored by the central slot note.
    // Each marker is pinned INSIDE its own head's section slice (or the comment block directly above
    // its header), with boundaries derived from the source text, so one comment elsewhere cannot
    // satisfy all four. Removing any single marker reds by head name.
    @Test
    fun `every tier decision is documented beside its own head - DR-43`() {
        val text = exampleToml()
        val header = Regex("\\n\\[heads\\.[a-z-]+]")
        fun section(head: String): String {
            val headerAt = text.indexOf("[heads.$head]")
            assertTrue(headerAt >= 0, "missing [heads.$head]")
            val preambleStart = text.lastIndexOf("\n\n", headerAt).coerceAtLeast(0)
            val end = header.find(text, headerAt + 1)?.range?.first ?: text.length
            return text.substring(preambleStart, end)
        }

        val central = section("claudex")
        assertTrue(
            "A tier NO row" in central && "clean pre-upstream 400" in central,
            "the central slot-semantics note must live on [heads.claudex]",
        )
        val grok = section("claude-grok")
        assertTrue(
            "No fable analog" in grok && "400s cleanly" in grok,
            "grok must document its declined fable tier beside its own roster",
        )
        for (head in listOf("openrouter", "fireworks", "claude-kimi")) {
            assertTrue(
                "opus-only on purpose" in section(head),
                "$head must document its opus-only decision beside its own roster",
            )
        }
    }

    // DR-24 redo (codex honesty catch): this test used to CLAIM the documented xAI values "carry
    // through the real catalog" but only checked grok-4.6 after catalogFor — and 4.6's roster window
    // (500k) equals the head override (500k), so it could never reveal the flattening. The head's
    // context_window=500k replaces every SELECTED row's window (Topology.catalogFor:141), so
    // grok-build-latest's real 256k ceiling is served to the client as 500k. Pin two source-derived
    // layers (denominators enumerated from the SOURCE, not a hand-list):
    //   (1) the raw provider roster carries the exact docs.x.ai numbers — all four declared rows;
    //   (2) the head-effective catalog CURRENTLY flattens every row the head SELECTS to 500k.
    // A silent edit to any shipped window — provider row, head override, or the roster — reds by name.
    // §22 / DECISION-REQUIRED — layer (2) is NOT an endorsement. Serving grok-build-latest (haiku slot)
    // at 500k over-declares its 256k backend ceiling: the forbidden "never spell a row past its real
    // ceiling" shape (a `--model haiku` turn past 256k hard-fails instead of compacting). The compliant
    // fix — remove the head override so each selected row reports its provider window (500k/500k/256k;
    // pinned grok-4.6 still launches at 500k) — changes a value the operator LOCKED for DR-24 ("never
    // shipped window values"), so it is an operator decision, recorded in the DR-24 ledger and NOT
    // taken here. Layer (2) is a TRIPWIRE on the current unsafe behavior: the operator's fix reds it
    // and forces the update through review.
    @Test
    fun `example grok windows are the documented roster, flattened to the head window in the catalog`() {
        val topology = TopologyLoader.parse(exampleToml())
        val head = topology.heads.getValue("claude-grok")
        val xai = topology.providers.getValue(head.provider)

        // (1) SOURCE OF TRUTH: the raw declared rows are exactly the docs.x.ai windows, none extra.
        assertEquals(
            mapOf(
                "grok-4.6" to 500_000L,
                "grok-4.5" to 500_000L,
                "grok-4.3" to 1_000_000L,
                "grok-build-latest" to 256_000L,
            ),
            xai.models.associate { it.id to it.contextWindow },
            "every declared provider row, exactly the docs.x.ai numbers, none extra",
        )

        // (2) PRODUCTION: the head window replaces every SELECTED row. Denominator from head.models
        // (the source), so a newly-selected row cannot slip the flattening unpinned.
        assertEquals(500_000L, head.contextWindow, "the head-level context_window override under test")
        val catalog = xai.catalogFor(head)
        val selectedIds = head.models.orEmpty().map { it.id }
        assertEquals(
            listOf("grok-4.6", "grok-4.5", "grok-build-latest"),
            selectedIds,
            "the head selects exactly these three rows (grok-4.3 is declared but NOT served here)",
        )
        assertEquals(
            selectedIds.associateWith { 500_000L },
            selectedIds.associateWith { catalog.contextWindowFor(it) },
            "TRIPWIRE (§22, DECISION-REQUIRED): the head window currently over-declares every SELECTED " +
                "row to 500k — grok-build-latest's real 256k included; removing the override reds this",
        )

        // The undeclared [1m] tier lands on the real ceiling and scales usage (DR-24 ModelCatalog fix).
        assertEquals(500_000L, catalog.contextWindowFor("grok-4.6[1m]"), "undeclared tier lands on the real ceiling")
        assertEquals(2.0, catalog.usageScale("grok-4.6[1m]"), "client 1e6 / real 500k")
        assertEquals(1_000_000L, catalog.clientContextWindowFor("grok-4.6[1m]"))
    }

    // DR-44b: ktoml unions duplicate keys instead of rejecting them (TOML spec: duplicates are an
    // error), so a stale second `models = [...]` line kept retired models in the picker with
    // nothing red anywhere. The pre-decode guard makes it loud and names the section.
    // DR-44 redo (codex bypass): the duplicate-models guard counts per SECTION, so REOPENING the
    // same table spelling with a second models line was one line per section — while ktoml unions
    // both bodies, so a stale grok-4.3 roster rode beside the real one with nothing red. Quoted
    // and whitespace header variants already fail inside ktoml (probed); the exact respelling is
    // the one confirmed bypass, and this is its permanent counterexample.
    @Test
    fun `a reopened head table fails loud instead of unioning rosters - DR-44`() {
        val doctored = exampleToml() + "\n[heads.claude-grok]\nmodels = [{ id = \"grok-4.3\", slot = \"opus\" }]\n"
        val thrown = assertThrows(IllegalArgumentException::class.java) { TopologyLoader.parse(doctored) }
        assertTrue(thrown.message!!.contains("defined twice"), thrown.message)
        assertTrue(thrown.message!!.contains("[heads.claude-grok]"), thrown.message)
    }

    // DR-96 (sweep-2): the reopen guard never REGISTERED the first table of a header-first file —
    // bounds[0]==0 keeps sectionStart 0 through the first real section, and the preamble-skip
    // gate (sectionStart > 0) swallowed the registration, so ONE exact reopen of the first table
    // passed preflight and ktoml merged both bodies (the DR-44-redo scar, resurrected for the
    // offset-0 table). Direct check() calls: the loader call site is pinned by the DR-44 arm.
    @Test
    fun `reopening the FIRST table of a header-first config fails loud - DR-96`() {
        val doctored = "[daemon]\ncontrol_port = 4400\n\n[providers.x]\ndialect = \"openai-chat\"\n\n" +
            "[daemon]\ncontrol_port = 4401\n"
        val thrown = assertThrows(IllegalArgumentException::class.java) { TomlStructurePreflight.check(doctored) }
        assertTrue(thrown.message!!.contains("defined twice"), thrown.message)
        assertTrue(thrown.message!!.contains("[daemon]"), thrown.message)
    }

    @Test
    fun `a legal header-first config still passes preflight - DR-96 control`() {
        TomlStructurePreflight.check("[daemon]\ncontrol_port = 4400\n\n[providers.x]\ndialect = \"openai-chat\"\n")
    }

    @Test
    fun `a preamble config still rejects a reopened non-first table - DR-96 control`() {
        val doctored = "# preamble comment\ntitle = \"x\"\n\n[daemon]\ncontrol_port = 4400\n\n" +
            "[providers.x]\ndialect = \"openai-chat\"\n\n[daemon]\ncontrol_port = 4401\n"
        val thrown = assertThrows(IllegalArgumentException::class.java) { TomlStructurePreflight.check(doctored) }
        assertTrue(thrown.message!!.contains("defined twice"), thrown.message)
    }

    @Test
    fun `a duplicated models key in one head fails loud instead of silently unioning`() {
        val valid = exampleToml()
        val roster =
            """models = [{ id = "grok-4.6", slot = "opus" }, { id = "grok-4.5", slot = "sonnet" }, { id = "grok-build-latest", slot = "haiku" }]"""
        val malformed = valid.replace(roster, roster + "\n" + """models = [{ id = "grok-4.3", slot = "haiku" }]""")
        assertTrue(malformed != valid, "test must duplicate the shipped inline roster")

        val failure = assertThrows(IllegalArgumentException::class.java) { TopologyLoader.parse(malformed) }
        assertTrue(failure.message.orEmpty().contains("duplicate"), failure.message)
        assertTrue(failure.message.orEmpty().contains("models"), failure.message)
    }

    @Test
    fun `a braces-dropped inline model roster fails promptly before ktoml`() {
        val valid = exampleToml()
        val malformed = valid.replace(
            """models = [{ id = "grok-4.6", slot = "opus" }, { id = "grok-4.5", slot = "sonnet" }, { id = "grok-build-latest", slot = "haiku" }]""",
            """models = ["grok-4.6", "grok-4.5"]""",
        )
        assertTrue(malformed != valid, "test must mutate the shipped inline roster")

        lateinit var failure: IllegalArgumentException
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            failure = assertThrows(IllegalArgumentException::class.java) { TopologyLoader.parse(malformed) }
        }
        assertTrue(failure.message.orEmpty().contains("models"), failure.message)
    }

    @Test
    fun `a quoted models key with a bare roster also fails promptly before ktoml`() {
        val valid = exampleToml()
        val malformed = valid.replace(
            """models = [{ id = "grok-4.6", slot = "opus" }, { id = "grok-4.5", slot = "sonnet" }, { id = "grok-build-latest", slot = "haiku" }]""",
            """"models" = ["grok-4.6", "grok-4.5"]""",
        )
        assertTrue(malformed != valid, "test must mutate the shipped inline roster")

        lateinit var failure: IllegalArgumentException
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            failure = assertThrows(IllegalArgumentException::class.java) { TopologyLoader.parse(malformed) }
        }
        assertTrue(failure.message.orEmpty().contains("models"), failure.message)
    }

    @Test
    fun `a four-quote multiline terminator cannot hide a malformed roster`() {
        val valid = exampleToml().replace(
            "discovery_prefix = \"claude-grok--\"",
            "discovery_prefix = \"\"\"claude-grok--\"\"\"\"",
        )
        assertEquals("claude-grok--\"", TopologyLoader.parse(valid).heads.getValue("claude-grok").discoveryPrefix)
        val malformed = valid.replace(
            """models = [{ id = "grok-4.6", slot = "opus" }, { id = "grok-4.5", slot = "sonnet" }, { id = "grok-build-latest", slot = "haiku" }]""",
            """models = ["grok-4.6", "grok-4.5"]""",
        )

        lateinit var failure: IllegalArgumentException
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            failure = assertThrows(IllegalArgumentException::class.java) { TopologyLoader.parse(malformed) }
        }
        assertTrue(failure.message.orEmpty().contains("models"), failure.message)
    }

    @Test
    fun `an inline-table head with a bare roster also fails promptly before ktoml`() {
        val tableHead = """
            [heads.claude-grok]
            provider = "xai"
            port = 3100
            discovery_prefix = "claude-grok--"
            pinned_model = "grok-4.6"
            models = [{ id = "grok-4.6", slot = "opus" }, { id = "grok-4.5", slot = "sonnet" }, { id = "grok-build-latest", slot = "haiku" }]
            context_window = 500000
            [heads.claude-grok.claude]
            command = "claude-grok"
            isolate = ["commands"]         # this head gets its own commands/, everything else shared
        """.trimIndent()
        val inlineRoster =
            """models = [{ id = "grok-4.6", slot = "opus" }, { id = "grok-4.5", slot = "sonnet" }, { id = "grok-build-latest", slot = "haiku" }]"""
        val inlineHead = """
            [heads]
            claude-grok = { provider = "xai", port = 3100, discovery_prefix = "claude-grok--", pinned_model = "grok-4.6", $inlineRoster, context_window = 500000, claude = { command = "claude-grok", isolate = ["commands"] } }
        """.trimIndent()
        val valid = exampleToml().replace(tableHead, inlineHead)
        assertTrue(valid != exampleToml(), "test must rewrite the shipped head as an inline table")
        assertEquals("grok-4.6", TopologyLoader.parse(valid).heads.getValue("claude-grok").pinnedModel)

        val malformed = valid.replace(inlineRoster, """models = ["grok-4.6", "grok-4.5"]""")
        lateinit var failure: IllegalArgumentException
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            failure = assertThrows(IllegalArgumentException::class.java) { TopologyLoader.parse(malformed) }
        }
        assertTrue(failure.message.orEmpty().contains("models"), failure.message)
    }

    @Test
    fun `valid multiline model rosters ignore comments and quoted text`() {
        val inlineRoster =
            """models = [{ id = "grok-4.6", slot = "opus" }, { id = "grok-4.5", slot = "sonnet" }, { id = "grok-build-latest", slot = "haiku" }]"""
        val multilineRoster = """
            models = [
                # models = ["comment", "text"]
                { id = "grok-4.6", slot = "opus" },
                { id = "grok-4.5", slot = "sonnet" },
            ]
        """.trimIndent()
        val quotedText = "discovery_prefix = \"\"\"\n\"models\" = [\"quoted\", \"text\"]\n" +
            "x".repeat(2_000) + "\n\"\"\""
        val toml = exampleToml()
            .replace(inlineRoster, multilineRoster)
            .replace("discovery_prefix = \"claude-grok--\"", quotedText)

        val head = TopologyLoader.parse(toml).heads.getValue("claude-grok")
        assertEquals(listOf("grok-4.6", "grok-4.5"), head.models?.map(HeadModel::id))
    }

    @Test
    fun `each example head owns its model roster and process window`() {
        val topology = TopologyLoader.parse(exampleToml())
        val headProfiles = mapOf(
            "claudex" to (
                listOf("gpt-5.6-sol" to "opus", "gpt-5.6-terra" to "sonnet", "gpt-5.6-luna" to "haiku") to
                    400_000L
                ),
            "claude-grok" to (
                listOf("grok-4.6" to "opus", "grok-4.5" to "sonnet", "grok-build-latest" to "haiku") to
                    500_000L
                ),
            "openrouter" to (listOf("meta-llama/llama-4-maverick" to "opus") to 1_048_576L),
            "fireworks" to (
                listOf("accounts/fireworks/models/llama-v3p1-70b-instruct" to "opus") to 131_072L
                ),
            "claude-kimi" to (listOf("k3[1m]" to "opus") to 1_000_000L),
            "claude-splice" to (
                listOf(
                    "claude-fable-5" to "fable",
                    "claude-opus-5" to "opus",
                    "claude-sonnet-5" to "sonnet",
                    "claude-haiku-4-5" to "haiku",
                ) to 200_000L
                ),
        )
        headProfiles.forEach { (key, profile) ->
            val head = topology.heads.getValue(key)
            assertEquals(
                profile.first,
                head.models?.map { it.id to it.slot },
                "$key must expose only its own slotted model roster",
            )
            assertEquals(profile.second, head.contextWindow, "$key must declare its process window")
        }
    }

    // The campaign's whole claim, asserted on the shipped example: a head whose upstream is
    // Anthropic itself, declared with no provider code and NO quirks — a declared quirk here would
    // be a bug, since this is the one upstream that accepts everything as sent.
    @Test
    fun `the claude head is declared as pure TOML with no quirks`() {
        val topology = TopologyLoader.parse(exampleToml())
        // The claude head: pure TOML, no provider code, and FAITHFUL — it must declare no quirks
        // at all, or it would deform the one upstream that accepts everything as sent.
        val anthropic = topology.providers[topology.heads["claude-splice"]!!.provider]!!
        assertEquals(Dialect.ANTHROPIC_PASSTHROUGH, anthropic.dialect)
        assertEquals("client", anthropic.auth.kind)
        assertEquals("https://api.anthropic.com", anthropic.baseUrl)
        assertNull(anthropic.quirks.mfjs)
        assertNull(anthropic.quirks.stripCacheControl)
        assertNull(anthropic.quirks.synthesizeSignatures)
        assertNull(anthropic.quirks.mapThinkingAdaptive)
        assertNull(anthropic.quirks.blockAllowlist)
        assertEquals("2023-06-01", anthropic.staticHeaders["anthropic-version"])
        assertEquals("claude-fable-5", topology.heads["claude-splice"]!!.pinnedModel)
        assertEquals(
            setOf(200_000L),
            anthropic.models.map { it.contextWindow }.toSet(),
            "all claude-splice rows must match: clientContextWindowFor has no claude-* client branch",
        )
        assertEquals(3104, topology.heads["claude-splice"]!!.port)
        // shadowing the real binary would make the wrapper invoke itself
        assertEquals("claude-splice", topology.heads["claude-splice"]!!.claude.command)
    }

    // 2026-07-26 review: the per-head block documented knob names in prose, and the prose had
    // drifted (it leaned three times on a [defaults] table the file never defined). Prose rots
    // silently; this makes the example's knob vocabulary answer to the Knob enum mechanically.
    // Covers BOTH the live override tables and the names the comments teach operators to use.
    @Test
    fun `every knob the example uses or names is a real Knob key`() {
        val toml = exampleToml()
        val topology = TopologyLoader.parse(toml)

        // 1. Live [heads.<key>.overrides] tables — a typo here ships a silently-ignored knob.
        val used = topology.heads.values.flatMap { it.overrides.keys }.toSet()
        assertTrue(used.isNotEmpty(), "the example must keep demonstrating per-head overrides")
        used.forEach { assertTrue(knobsByKey.containsKey(it), "example sets unknown knob '$it'") }

        // 2. Knob names the COMMENTS teach. Anything camelCase-and-backticked-or-listed in the
        //    per-head doc block must resolve; that is what went stale and got hand-fixed once.
        //
        //    2026-07-27 review: this used to pre-filter to `startsWith("max") || endsWith("Ms")`,
        //    which was lossless only by accident — the example happens to name only the
        //    timeout-and-capacity family today. `toolSurface`, `usageWarnPct`, `pinnedModel`,
        //    `foldMarkerText` and `contextWindowOverride` are all live Knob keys the filter would
        //    have silently dropped, and silent skipping is the exact drift this test exists to
        //    catch. Every camelCase token now resolves against the enum; prose words that are not
        //    knobs go in an EXPLICIT allowlist, so they stay visible instead of vanishing into a
        //    shape rule.
        val docNames = Regex("\\b([a-z]+[A-Z][A-Za-z]*)\\b")
            .findAll(toml.lines().filter { it.trimStart().startsWith("#") }.joinToString("\n"))
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(docNames.isNotEmpty(), "the example must keep teaching knob names in comments")

        // Prose, not knobs. An entry here that IS a knob would silence a real drift, so the
        // allowlist is asserted disjoint from the enum rather than trusted.
        // usageScale is a ModelCatalog METHOD, not a knob: the example's k3[1m] note has to name it
        // to explain why that row must declare exactly 1000000 (Claude Code hardcodes 1e6 for a
        // "[1m]" id, so any other declared value becomes a scale factor on a pinned row).
        val prose = setOf("xAI", "usageScale")
        prose.forEach {
            assertTrue(
                !knobsByKey.containsKey(it),
                "'$it' is a real knob — remove it from the prose allowlist",
            )
        }

        (docNames - prose).forEach {
            assertTrue(
                knobsByKey.containsKey(it),
                "example comment names unknown knob '$it' " +
                    "(add it to the prose allowlist only if it is not a knob)",
            )
        }
    }
}
