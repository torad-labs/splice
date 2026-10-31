// Failure-handling slice 4: the retry and fold knobs left the schema, so a setting that still names one refuses boot
// wherever it is written: a splice.toml key, an environment alias or a value saved in the state folder's config.json.
// The environment and the saved settings win over splice.toml, so either would otherwise be ignored in silence. Every
// place is listed at once, each with its fix, and the fix session a CLI verb offers removes the lines, clears the saved
// settings and names each variable to unset. The boot is the real one (a splice.toml on disk, DaemonProcess.prepare).
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.app.DaemonProcess
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.TopologyRefusal
import java.nio.file.Files
import java.nio.file.Path

/** One retired key in each place splice.toml sets a knob, beside live settings the fix must leave alone. Every value
 *  is one the retired knobs accepted, so nothing but their retirement can refuse them. */
private val CONFIG = """
    [daemon]
    fold_max_tier = 5917

    [defaults]
    retryBackoffCapMs = "20917"
    maxInflight = "6"

    [providers.ex]
    dialect = "openai-chat"
    base_url = "https://api.example.com/v1"
    auth = { kind = "api-key", env = "EX_KEY" }

    [[providers.ex.models]]
    id = "m1"
    context_window = 128000

    [heads.one]
    provider = "ex"
    port = 8801
    discovery_prefix = "ex/"
    pinned_model = "m1"

    [heads.one.overrides]
    upstreamRetries = "9173"
    maxInflight = "4"
""".trimIndent() + "\n"

/** Two retired knobs' environment aliases, as a shell profile or a service unit would still set them. */
private val ALIASES = mapOf("CLAUDEX_UPSTREAM_RETRIES" to "7351", "CLAUDEX_FOLD_MAX_CONTINUE" to "8429")

/** What splice saved in its state folder: a retired fold knob beside a live one. */
private const val SAVED = """{"foldMarkerText":"planted saved marker","maxQueued":7}"""

/** Every value written above; none of them is ever repeated, since a setting can hold a secret. */
private val PLANTED = listOf("5917", "20917", "9173", "7351", "8429", "planted saved marker")

/** Where each retired setting is, as the refusal names it: a key path and its line, a variable, a saved key. */
private val PLACES = listOf(
    "daemon.fold_max_tier (line 2)",
    "defaults.retryBackoffCapMs (line 5)",
    "heads.one.overrides.upstreamRetries (line 24)",
    "CLAUDEX_UPSTREAM_RETRIES",
    "CLAUDEX_FOLD_MAX_CONTINUE",
    "foldMarkerText",
)

// why: a session that offers the same finding over and over would hang the test; real sessions here ask six times.
private const val MAX_ASKS = 20

class RetiredKnobBootTest {
    @TempDir
    lateinit var tmp: Path

    private val config by lazy { tmp.resolve("splice.toml") }
    private val state by lazy { tmp.resolve("state") }

    private fun env(extra: Map<String, String>): EnvReader {
        val all = mapOf(
            "SPLICE_CONFIG" to config.toString(),
            "SPLICE_STATE_DIR" to state.toString(),
            "HOME" to tmp.resolve("home").toString(),
        ) + extra
        return EnvReader { all[it] }
    }

    /**
     * The slice's tests-first entry: "a splice.toml that sets upstreamRetries, a retry backoff key or a fold key, or an
     * environment alias or state override of one, refuses to boot, lists every finding at once and offers the fix
     * session".
     */
    @Test
    fun `a retired knob set in splice toml, the environment or saved settings refuses boot and offers the fix`() {
        Files.writeString(config, CONFIG)
        Files.createDirectories(state)
        Files.writeString(state.resolve("config.json"), SAVED)

        val refusal = assertThrows<TopologyRefusal> { DaemonProcess().prepare(env(ALIASES)) }

        val shown = refusal.message.orEmpty().replace(tmp.toString(), "<tmp>")
        assertEquals(PLACES.size, refusal.findings.size, "one finding for each place a retired knob is set: $shown")
        PLACES.forEach { place ->
            assertEquals(1, refusal.findings.count { place in it.text() }, "$place is listed once: $shown")
        }
        refusal.findings.forEach { assertTrue("retired in 0.4.0" in it.message, "it says why: ${it.text()}") }
        PLANTED.forEach { value -> assertFalse(value in shown, "no value reaches the refusal: $value in $shown") }

        val said = mutableListOf<String>()
        val offer = ConfigFixOffer(
            env = env(ALIASES),
            interactive = true,
            prompter = TakesTheFix(said),
            backups = tmp.resolve("backups"),
            output = TerminalOutput { said += it },
        )
        val fixed = offer.offer()

        assertFalse(fixed, "the aliases are still set where this command runs, so splice still cannot start: $said")
        assertTrue(said.any { "a fix session can repair this now" in it }, "the session is offered: $said")
        ALIASES.keys.forEach { name -> assertTrue(said.any { "unset $name" in it }, "it names $name to unset: $said") }
        val toml = Files.readString(config)
        listOf("fold_max_tier", "retryBackoffCapMs", "upstreamRetries").forEach { key ->
            assertFalse(key in toml, "the line setting $key is removed: $toml")
        }
        assertTrue("maxInflight = \"6\"" in toml && "maxInflight = \"4\"" in toml, "live settings stay: $toml")
        val kept = Json.parseToJsonElement(Files.readString(state.resolve("config.json"))).jsonObject
        assertEquals(setOf("maxQueued"), kept.keys, "the retired saved setting is cleared and the live one kept")
        val backups = Files.list(tmp.resolve("backups")).use { it.toList() }
            .filter { it.fileName.toString().startsWith("config.json.bak-") }
        assertEquals(1, backups.size, "the saved settings are backed up once before the clear: $backups")
        assertTrue("foldMarkerText" in Files.readString(backups.single()), "the backup holds what was cleared")

        val started = DaemonProcess().prepare(env(emptyMap()))
        assertEquals(setOf("one"), started.loaded.topology.heads.keys, "with the aliases unset, splice starts")
    }

    /** A file the decode accepts reaches the knob check, which names a retired key in the same words and line. */
    @Test
    fun `a retired key in a splice toml that decodes is named as retired, with its line and how to fix it`() {
        Files.writeString(config, CONFIG.removePrefix("[daemon]\nfold_max_tier = 5917\n\n"))

        val refusal = assertThrows<TopologyRefusal> { DaemonProcess().prepare(env(emptyMap())) }

        val shown = refusal.message.orEmpty()
        assertTrue(shown.startsWith("splice.toml has 2 problem(s)"), "both are the file's: $shown")
        assertTrue("defaults.retryBackoffCapMs (line 2): was retired in 0.4.0" in shown, shown)
        assertTrue("heads.one.overrides.upstreamRetries (line 21): was retired in 0.4.0" in shown, shown)
        refusal.findings.forEach { assertTrue(it.message.endsWith("; remove the line"), "its fix: ${it.text()}") }
        PLANTED.forEach { value -> assertFalse(value in shown, "no value reaches the refusal: $value in $shown") }
    }

    /** A person at the session who takes the fix each finding offers, clearing a saved setting or removing a line,
     *  and otherwise moves on. */
    private class TakesTheFix(private val said: List<String>) : FixPrompter {
        private var asked = 0

        override fun ask(question: String): String? {
            asked += 1
            val offered = said.takeLastWhile { "Fixing:" !in it }
            return when {
                asked > MAX_ASKS -> null
                offered.any { "[c]" in it } -> "c"
                offered.any { "[d]" in it } -> "d"
                else -> "s"
            }
        }

        override fun secret(question: String): String? = null

        override fun edit(file: Path, line: Int?): Boolean = false
    }
}
