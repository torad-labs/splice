// The committed app/src/main/resources/splice.example.toml is a TESTED artifact, not aspirational docs: it
// must parse, keep operators on splice's own OAuth credential files, answer to the Knob vocabulary, and the
// parser must refuse the malformed rosters an operator copying from it could produce.
package splice.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.config.knobsByKey
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.HeadModel
import splice.topology.TopologyLoader
import java.time.Duration

class ExampleConfigTest {

    private fun exampleToml(): String =
        checkNotNull(javaClass.getResourceAsStream("/splice.example.toml")) {
            "splice.example.toml is not on the classpath"
        }.bufferedReader().use { it.readText() }

    /**
     * SPLICE OWNS ITS CREDENTIAL, in the file operators COPY FROM. AuthKind's header records why
     * (2026-09-05): a credential shared with the vendor's CLI shares one refresh-token family, an
     * OpenAI-style refresh ROTATES it, and each side then invalidates the other's session — 24
     * turns failed inside one 16 s rotation. The native file is an explicit opt-in, never a
     * default, so no OAuth head in the shipped example may pin one.
     *
     * This law exists because the example silently lost it once (2026-09-06: a status-line commit
     * copied a whole stale TOML in and reverted all three OAuth heads to the vendor files, deleting
     * the guidance with them) and NOTHING failed — the daemon never parses these defaults, and the
     * knob-name law below only checks that names resolve, so a vendor path is a valid example. It
     * was caught in peer review, which is not a gate.
     *
     * The denominator is the parsed example crossed with the AuthKind REGISTRY, never a list of
     * head names: an OAuth kind added later is covered the day it is registered.
     */
    @Test
    fun `example - no OAuth head pins a vendor CLI credential file`() {
        val topology = TopologyLoader.parse(exampleToml())
        val oauthHeads = topology.providers.filterValues { AuthKindRegistry.from(it.auth.kind) is AuthKind.OAuth }
        assertTrue(oauthHeads.isNotEmpty(), "the example must keep demonstrating OAuth heads")

        oauthHeads.forEach { (name, provider) ->
            val kind = AuthKindRegistry.from(provider.auth.kind) as AuthKind.OAuth
            assertNull(
                provider.auth.file,
                "provider '$name' pins auth.file — the shipped example must default ${kind.wire} to " +
                    "splice's own ${kind.authFile}, never ${kind.nativeApp}'s ${kind.nativeAppFile}, " +
                    "which rotates the same refresh token and signs both sides out",
            )
        }
    }

    @Test
    fun `an explicit empty head model list is rejected rather than exposing the provider`() {
        val toml = exampleToml().replace(
            "models = [{ id = \"grok-4.7\", slot = \"opus\" }, { id = \"grok-4.6\", slot = \"sonnet\" }, " +
                "{ id = \"grok-build-0.1\", slot = \"haiku\" }]",
            "models = []",
        )
        val topology = TopologyLoader.parse(toml)
        val head = topology.heads.getValue("claude-grok")
        val provider = topology.providers.getValue(head.provider)

        assertEquals(emptyList<HeadModel>(), head.models)
        assertThrows(IllegalArgumentException::class.java) { provider.catalogFor(head) }
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

    // DR-96's three direct preflight arms moved with TomlStructurePreflight to
    // integrations/topology (TomlStructurePreflightTest); they never read the example.

    @Test
    fun `a duplicated models key in one head fails loud instead of silently unioning`() {
        val valid = exampleToml()
        val roster =
            """models = [{ id = "grok-4.7", slot = "opus" }, { id = "grok-4.6",""" +
                """ slot = "sonnet" }, { id = "grok-build-0.1", slot = "haiku" }]"""
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
            """models = [{ id = "grok-4.7", slot = "opus" }, { id = "grok-4.6",""" +
                """ slot = "sonnet" }, { id = "grok-build-0.1", slot = "haiku" }]""",
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
            """models = [{ id = "grok-4.7", slot = "opus" }, { id = "grok-4.6",""" +
                """ slot = "sonnet" }, { id = "grok-build-0.1", slot = "haiku" }]""",
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
            """models = [{ id = "grok-4.7", slot = "opus" }, { id = "grok-4.6",""" +
                """ slot = "sonnet" }, { id = "grok-build-0.1", slot = "haiku" }]""",
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
            pinned_model = "grok-4.7"
            models = [{ id = "grok-4.7", slot = "opus" }, { id = "grok-4.6", slot = "sonnet" }, { id = "grok-build-0.1", slot = "haiku" }]
            [heads.claude-grok.claude]
            command = "claude-grok"
            isolate = ["commands"]         # this head gets its own commands/, everything else shared
        """.trimIndent()
        val inlineRoster =
            """models = [{ id = "grok-4.7", slot = "opus" }, { id = "grok-4.6",""" +
                """ slot = "sonnet" }, { id = "grok-build-0.1", slot = "haiku" }]"""
        val inlineHead = """
            [heads]
            claude-grok = { provider = "xai", port = 3100, discovery_prefix = "claude-grok--", pinned_model = "grok-4.7", $inlineRoster, claude = { command = "claude-grok", isolate = ["commands"] } }
        """.trimIndent()
        val valid = exampleToml().replace(tableHead, inlineHead)
        assertTrue(valid != exampleToml(), "test must rewrite the shipped head as an inline table")
        assertEquals("grok-4.7", TopologyLoader.parse(valid).heads.getValue("claude-grok").pinnedModel)

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
            """models = [{ id = "grok-4.7", slot = "opus" }, { id = "grok-4.6",""" +
                """ slot = "sonnet" }, { id = "grok-build-0.1", slot = "haiku" }]"""
        val multilineRoster = """
            models = [
                # models = ["comment", "text"]
                { id = "grok-4.7", slot = "opus" },
                { id = "grok-4.6", slot = "sonnet" },
            ]
        """.trimIndent()
        val quotedText = "discovery_prefix = \"\"\"\n\"models\" = [\"quoted\", \"text\"]\n" +
            "x".repeat(2_000) + "\n\"\"\""
        val toml = exampleToml()
            .replace(inlineRoster, multilineRoster)
            .replace("discovery_prefix = \"claude-grok--\"", quotedText)

        val head = TopologyLoader.parse(toml).heads.getValue("claude-grok")
        assertEquals(listOf("grok-4.7", "grok-4.6"), head.models?.map(HeadModel::id))
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
        val prose = setOf("xAI", "usageScale", "vLLM")
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
