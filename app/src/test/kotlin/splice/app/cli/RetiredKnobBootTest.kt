// Failure-handling slice 4: the retry and fold knobs left the schema, so a splice.toml key that still names one refuses
// boot, every one listed at once with its fix, and the fix session a CLI verb offers removes the lines. upgrade-compat
// (Nov 23, 2026): an environment alias or a value saved in the state folder's config.json is a warning, never a refusal,
// since splice reads neither: the variable is named to unset where it is set, and the daemon clears the saved value at
// its start after a copy. The boot is the real one (a splice.toml on disk, DaemonProcess.prepare).
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
import splice.app.daemon.BootWarnings
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
    discovery_prefix = "claude-ex--"
    pinned_model = "m1"

    [heads.one.overrides]
    upstreamRetries = "9173"
    maxInflight = "4"

    [providers.ex.overrides]
    maxInflight = "4"
""".trimIndent() + "\n"

/** Two retired knobs' environment aliases, as a shell profile or a service unit would still set them. */
private val ALIASES = mapOf("CLAUDEX_UPSTREAM_RETRIES" to "7351", "CLAUDEX_FOLD_MAX_CONTINUE" to "8429")

/** What splice saved in its state folder: a retired fold knob beside a live one. */
private const val SAVED = """{"foldMarkerText":"planted saved marker","maxQueued":7}"""

/** Every value written above; none of them is ever repeated, since a setting can hold a secret. */
private val PLANTED = listOf("5917", "20917", "9173", "7351", "8429", "planted saved marker")

/** Where each retired setting in splice.toml is, as the refusal names it: a key path and its line. */
private val PLACES = listOf(
    "daemon.fold_max_tier (line 2)",
    "defaults.retryBackoffCapMs (line 5)",
    "heads.one.overrides.upstreamRetries (line 24)",
)

/** Where each retired setting splice reads past is, as the warnings name it: a variable, a saved key. */
private val WARNED = listOf("CLAUDEX_UPSTREAM_RETRIES", "CLAUDEX_FOLD_MAX_CONTINUE", "foldMarkerText")

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
     * The slice's tests-first entry: "a splice.toml that sets upstreamRetries, a retry backoff key or a fold key refuses
     * to boot, lists every finding at once and offers the fix session"; since upgrade-compat, an environment alias or a
     * state override of one is warned of, and the saved one cleared at the daemon's start.
     */
    @Test
    fun `a retired knob in splice toml refuses boot with its fix, and one set elsewhere is warned of and cleared`() {
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

        assertTrue(fixed, "the file's lines were all it took; what is set elsewhere never refuses boot: $said")
        assertTrue(said.any { "a fix session can repair this now" in it }, "the session is offered: $said")
        val toml = Files.readString(config)
        listOf("fold_max_tier", "retryBackoffCapMs", "upstreamRetries").forEach { key ->
            assertFalse(key in toml, "the line setting $key is removed: $toml")
        }
        assertTrue("maxInflight = \"6\"" in toml && "maxInflight = \"4\"" in toml, "live settings stay: $toml")
        startsWarnedAndCleared()
    }

    /** With the aliases still set, splice starts, says each place it reads past without a value, and clears the saved
     *  setting after one copy, so the next start has nothing left to say. */
    private fun startsWarnedAndCleared() {
        val started = DaemonProcess().prepare(env(ALIASES))
        assertEquals(setOf("one"), started.loaded.topology.heads.keys, "with the aliases still set, splice starts")
        val warned = started.loaded.warnings
        WARNED.forEach { place -> assertEquals(1, warned.count { place in it.text() }, "$place: $warned") }
        ALIASES.keys.forEach { name ->
            assertTrue(warned.single { name in it.text() }.message.contains("unset it"), "it says to unset $name")
        }
        val logged = mutableListOf<String>()
        BootWarnings { logged += it }.say(warned, started.statePaths)
        val shown = logged.joinToString("").replace(tmp.toString(), "<tmp>")
        PLANTED.forEach { value -> assertFalse(value in shown, "no value reaches the log: $value in $shown") }
        assertTrue(WARNED.all { it in shown }, "each one is said: $shown")
        val kept = Json.parseToJsonElement(Files.readString(state.resolve("config.json"))).jsonObject
        assertEquals(setOf("maxQueued"), kept.keys, "the retired saved setting is cleared and the live one kept")
        val backups = Files.list(started.statePaths.configBackupsDir).use { it.toList() }
            .filter { it.fileName.toString().startsWith("config.json.bak-") }
        assertEquals(1, backups.size, "the saved settings are copied once before the clear: $backups")
        assertTrue("foldMarkerText" in Files.readString(backups.single()), "the copy holds what was cleared")
        val next = DaemonProcess().prepare(env(emptyMap()))
        assertEquals(emptyList<Any>(), next.loaded.warnings, "nothing is left to say once it is cleared")
    }

    /** Audit, Nov 9, 2026: the two sign-in file variables no head reads any more are said together, each pointing to the
     *  provider's auth.file, instead of being ignored in silence; since upgrade-compat they are warnings and splice
     *  starts, since a variable a service unit sets cannot be unset from a daemon that does not run. */
    @Test
    fun `a retired sign-in file variable is warned of, names the provider's auth file, and splice starts`() {
        val live = CONFIG.removePrefix("[daemon]\nfold_max_tier = 5917\n\n").lines()
            .filterNot { "retryBackoffCapMs" in it || "upstreamRetries" in it }
        Files.writeString(config, live.joinToString("\n"))
        val set = mapOf("CODEX_AUTH_PATH" to "/planted/codex.json", "GROK_AUTH_PATH" to "/planted/grok.json")

        val warned = DaemonProcess().prepare(env(set)).loaded.warnings

        val shown = warned.joinToString("\n") { it.text() }
        assertEquals(2, warned.size, "one warning for each variable, listed at once: $shown")
        set.keys.forEach { name -> assertEquals(1, warned.count { name in it.text() }, "$name: $shown") }
        warned.forEach { assertTrue("auth.file" in it.message, "it names auth.file: ${it.text()}") }
        set.values.forEach { value -> assertFalse(value in shown, "no value reaches the warning: $value in $shown") }
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
