// NEW: V4-177 — the two halves of the state-root move that live outside StatePaths: what doctor
// TELLS the operator, and whether the shell copies of the rule still agree with the Kotlin one.
package campaign.v4177

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.CheckStatus
import splice.app.cli.DoctorStateLayout
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

private val NO_ENV = EnvReader { null }

// The two roots and the two variable names, spelled HERE rather than imported. They are
// :core-internal — the public-surface ratchet gates declarations no other module's MAIN sources
// consume, and nothing outside :core resolves a state root — so importing them into an :app test
// would be a test claiming an exemption from the module law. `the four names in this file are the
// ones StatePaths actually resolves` is what keeps the duplication honest: it fails loudly if :core
// moves a root or renames a variable, instead of leaving this file exercising a shape production
// abandoned.
private const val SPLICE_ROOT = ".splice"
private const val LEGACY_ROOT = ".claude-codex"
private const val STATE_DIR_ENV = "SPLICE_STATE_DIR"
private const val LEGACY_STATE_DIR_ENV = "CLAUDEX_STATE_DIR"

private fun makeState(home: Path, root: String): Path = Files.createDirectories(home.resolve(root).resolve("state"))

/**
 * The row an operator reads when the daemon comes up on a root they did not choose.
 *
 * WHY THIS ROW EXISTS AT ALL. V4-177's migration is an ADOPTION, not a move: on an upgraded box the
 * live state dir is still the pre-0.4 one, on a fresh box it is the new one, and from outside the
 * process the two are indistinguishable without `lsof`. The failure this prevents is not cosmetic —
 * an operator who cannot see which root is live reads an empty usage history as a daemon that lost
 * it, and the obvious "fix" for that (delete and re-init) destroys the history that was never gone.
 */
class StateLayoutDoctorTest {

    @Test
    fun `an adopted pre-0_4 root is named, and says nothing was moved`(@TempDir home: Path) {
        val legacy = makeState(home, LEGACY_ROOT)

        val check = DoctorStateLayout().check(StatePaths(envReader = NO_ENV, homeDir = home))

        assertNotNull(check)
        assertEquals(CheckStatus.INFO, check!!.status)
        assertTrue(legacy.toString() in check.detail, check.detail)
        assertTrue("nothing was copied, moved or deleted" in check.detail, check.detail)
        assertNull(check.fix, "adoption is the working state, not something to repair")
    }

    // The half-migrated box. WARN, not INFO: the numbers on screen are genuinely incomplete, and the
    // operator is the only one who can decide what to carry over.
    @Test
    fun `a leftover pre-0_4 root is a WARN that names both paths and what to do`(@TempDir home: Path) {
        val legacy = makeState(home, LEGACY_ROOT)
        val current = makeState(home, SPLICE_ROOT)

        val check = DoctorStateLayout().check(StatePaths(envReader = NO_ENV, homeDir = home))

        assertNotNull(check)
        assertEquals(CheckStatus.WARN, check!!.status)
        assertTrue(legacy.toString() in check.detail && current.toString() in check.detail, check.detail)
        assertTrue(current.toString() in check.fix.orEmpty(), "the fix must name where to move it: ${check.fix}")
    }

    // Mutant: emit the row unconditionally. A doctor line that appears on every healthy install is a
    // line operators learn to scroll past, which costs the two arms above the attention they need.
    @Test
    fun `a box on the current layout alone says nothing`(@TempDir home: Path) {
        makeState(home, SPLICE_ROOT)

        assertNull(DoctorStateLayout().check(StatePaths(envReader = NO_ENV, homeDir = home)))
    }

    @Test
    fun `a state dir named by the environment says nothing about either root`(@TempDir home: Path) {
        makeState(home, LEGACY_ROOT)
        val env = EnvReader { name -> home.resolve("elsewhere").toString().takeIf { name == STATE_DIR_ENV } }

        assertNull(
            DoctorStateLayout().check(StatePaths(envReader = env, homeDir = home)),
            "a root the operator pointed past is not unmigrated history",
        )
    }

    // The duplication guard for this file's four local names — every one of them, driven through
    // production. Without it, a :core rename leaves every arm above green while testing nothing.
    @Test
    fun `the four names in this file are the ones StatePaths actually resolves`(@TempDir home: Path) {
        fun pointedAt(variable: String, at: Path) =
            StatePaths(envReader = EnvReader { n -> at.toString().takeIf { n == variable } }, homeDir = home).stateDir

        val onAClearBox = StatePaths(envReader = NO_ENV, homeDir = home).stateDir
        assertEquals(home.resolve(SPLICE_ROOT).resolve("state"), onAClearBox)

        val legacy = makeState(home, LEGACY_ROOT)
        assertEquals(legacy, StatePaths(envReader = NO_ENV, homeDir = home).stateDir)

        assertEquals(home.resolve("by-new"), pointedAt(STATE_DIR_ENV, home.resolve("by-new")))
        assertEquals(home.resolve("by-old"), pointedAt(LEGACY_STATE_DIR_ENV, home.resolve("by-old")))
    }
}

/**
 * THE SHELL COPIES. `bin/splice-launch` reads the mgmt-key and `config.json` that the daemon WRITES,
 * and the e2e harnesses read the daemon's logs and key the same way — so the rule for finding the
 * state root now exists in Kotlin and in bash, and the two must not drift. A shim that picked the
 * other root would report "mgmt-key not found" on a perfectly healthy install, or cold-start a
 * second daemon against an empty state dir while the real one is already up on the other root.
 *
 * The denominator is ENUMERATED, not listed: every tracked file that declares `resolve_state_dir()`
 * is discovered by walking the tree, so a fourth copy added later is pinned the day it lands rather
 * than the day someone remembers this file. The table below is the same one StateRootTest drives
 * against StatePaths, so agreement is asserted branch by branch rather than on one happy path.
 */
class StateDirAgreementTest {

    private val repo: Path = run {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("install.sh")) && dir.parent != null) dir = dir.parent
        dir
    }

    private fun shellResolvers(): List<Path> =
        listOf("bin", "checks").map(repo::resolve).filter { Files.exists(it) }
            .flatMap { start -> Files.walk(start).use { walk -> walk.toList() } }
            .filter { it.isRegularFile() && declaresResolver(it) }
            .sorted()

    private fun declaresResolver(file: Path): Boolean =
        runCatching { "\nresolve_state_dir() {" in file.readText() }.getOrDefault(false)

    private fun resolveWith(script: Path, home: Path, env: Map<String, String>): String {
        val extract = "sed -n '/^resolve_state_dir()/,/^}/p' " + script.toAbsolutePath()
        val builder = ProcessBuilder("bash", "-c", "source <($extract); resolve_state_dir")
        builder.directory(repo.toFile())
        builder.environment().apply {
            remove(STATE_DIR_ENV)
            remove(LEGACY_STATE_DIR_ENV)
            put("HOME", home.toString())
            putAll(env)
        }
        builder.redirectErrorStream(true)
        val process = builder.start()
        val output = process.inputStream.readBytes().decodeToString().trim()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "$script resolver did not finish")
        assertEquals(0, process.exitValue(), "$script resolver failed: $output")
        return output
    }

    @Test
    fun `every shell copy of the state-root rule answers exactly what StatePaths answers`(@TempDir root: Path) {
        val scripts = shellResolvers()
        assertTrue(scripts.isNotEmpty(), "no shell resolver found under bin/ or checks/ — did the rule move?")

        // One temp HOME per filesystem shape, so the four branches are driven independently.
        val cases: List<Pair<String, Map<String, String>>> = listOf(
            "neither" to emptyMap(),
            "legacy-only" to emptyMap(),
            "both" to emptyMap(),
            "legacy-root-without-state-leaf" to emptyMap(),
            "env-new" to mapOf(STATE_DIR_ENV to root.resolve("env-new/pointed").toString()),
            "env-legacy" to mapOf(LEGACY_STATE_DIR_ENV to root.resolve("env-legacy/pointed").toString()),
            "env-both" to mapOf(
                STATE_DIR_ENV to root.resolve("env-both/new").toString(),
                LEGACY_STATE_DIR_ENV to root.resolve("env-both/old").toString(),
            ),
        )

        for ((name, env) in cases) {
            val home = Files.createDirectories(root.resolve(name))
            when (name) {
                "legacy-only", "env-legacy", "env-both" -> makeState(home, LEGACY_ROOT)
                "both" -> {
                    makeState(home, LEGACY_ROOT)
                    makeState(home, SPLICE_ROOT)
                }
                // The collision shape: `~/.claude-codex` present as the codex head's CLAUDE_CONFIG_DIR,
                // with no `state` leaf. Both implementations must decline to adopt it.
                "legacy-root-without-state-leaf" ->
                    Files.createDirectories(home.resolve(LEGACY_ROOT).resolve("projects"))
                else -> Unit
            }
            val expected = StatePaths(
                envReader = EnvReader { key -> env[key] },
                homeDir = home,
            ).stateDir.toString()

            for (script in scripts) {
                assertEquals(
                    expected,
                    resolveWith(script, home, env),
                    "$name: ${repo.relativize(script)} disagrees with StatePaths",
                )
            }
        }
    }

    // Mutant: the harness itself. If `resolve_state_dir` stopped being extractable — renamed, or its
    // body no longer closing on a column-0 brace — every arm above would silently compare nothing
    // against nothing. Plant a resolver that is deliberately WRONG and require the comparison to
    // notice, which is the only way to know the sed/source path actually runs the script's code.
    @Test
    fun `the harness would catch a shell copy that resolved the wrong root`(@TempDir root: Path) {
        val home = Files.createDirectories(root.resolve("home"))
        makeState(home, LEGACY_ROOT)
        val wrong = root.resolve("wrong.sh")
        val body = "  printf '%s\\n' \"\$HOME/" + SPLICE_ROOT + "/state\""
        Files.writeString(wrong, "#!/usr/bin/env bash\nresolve_state_dir() {\n$body\n}\n")

        val expected = StatePaths(envReader = NO_ENV, homeDir = home).stateDir.toString()

        assertEquals(home.resolve(LEGACY_ROOT).resolve("state").toString(), expected)
        assertTrue(
            resolveWith(wrong, home, emptyMap()) != expected,
            "a resolver that ignores the pre-0.4 root must not compare equal — the harness is not running the script",
        )
    }
}
