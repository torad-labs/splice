// NEW: V4-177 — the two halves of the state-root move that live outside StatePaths: what doctor
// TELLS the operator, and whether the shell copies of the rule still agree with the Kotlin one.
package campaign.v4177

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.CheckStatus
import splice.app.cli.DoctorCheck
import splice.app.cli.DoctorStateLayout
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
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

/** The copies that must EXIST, by path. Walking the tree finds a copy someone adds; only this finds
 *  one someone deletes, renames, or edits out of the shape the walk recognises — and an enumeration
 *  that silently shrinks to one file still satisfies `isNotEmpty`. `.dev/web-console/lib/cdp.mjs`
 *  carries the rule too and is driven when present, but it is dev-only tooling outside the shipped
 *  tree, so it is not required here. */
private val REQUIRED = setOf(
    "bin/splice-launch",
    "checks/e2e/heads-e2e.sh",
    "checks/e2e/docker/inside.sh",
    "checks/e2e/console-wire-keys.ts",
)

/** One temp HOME per filesystem shape, so every branch is driven independently. The last five are
 *  the shapes that ACTUALLY diverged between the implementations before this wall covered them. */
private val CASES: List<Pair<String, Map<String, String>>> = listOf(
    "neither" to emptyMap(),
    "legacy-only" to emptyMap(),
    "both" to emptyMap(),
    "legacy-root-without-state-leaf" to emptyMap(),
    "env-new" to mapOf(STATE_DIR_ENV to "/pointed/new"),
    "env-legacy" to mapOf(LEGACY_STATE_DIR_ENV to "/pointed/old"),
    "env-both" to mapOf(STATE_DIR_ENV to "/pointed/new", LEGACY_STATE_DIR_ENV to "/pointed/old"),
    // `??` in TypeScript returned "" rather than falling through, so the legacy variable was skipped.
    "env-blank-falls-to-legacy" to mapOf(STATE_DIR_ENV to "", LEGACY_STATE_DIR_ENV to "/pointed/old"),
    // `-n " "` is true in bash; Kotlin's isNotBlank is false.
    "env-whitespace" to mapOf(STATE_DIR_ENV to "   "),
    // Files.exists said true for a regular file; `[ -d ]` said false.
    "legacy-state-is-a-file" to emptyMap(),
    "current-state-is-a-file" to emptyMap(),
    // `[ ! -d ]` is true for "cannot stat" exactly as for "absent" — the proven-absence law.
    "current-root-unreadable" to emptyMap(),
)

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

        val check = DoctorStateLayout().checks(StatePaths(envReader = NO_ENV, homeDir = home)).singleOrNull()

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

        val check = DoctorStateLayout().checks(StatePaths(envReader = NO_ENV, homeDir = home)).singleOrNull()

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

        val rows = DoctorStateLayout().checks(StatePaths(envReader = NO_ENV, homeDir = home))
        assertEquals(emptyList<DoctorCheck>(), rows)
    }

    @Test
    fun `a state dir named by the environment says nothing about either root`(@TempDir home: Path) {
        makeState(home, LEGACY_ROOT)
        val env = EnvReader { name -> home.resolve("elsewhere").toString().takeIf { name == STATE_DIR_ENV } }

        assertNull(
            DoctorStateLayout().checks(StatePaths(envReader = env, homeDir = home)).singleOrNull(),
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
 * THE OTHER IMPLEMENTATIONS OF THE STATE-ROOT RULE, pinned against the Kotlin one.
 *
 * `bin/splice-launch` reads the mgmt-key and `config.json` that the daemon WRITES; the two e2e
 * harnesses read the daemon's logs and key the same way; `console-wire-keys.ts --attach` reads the
 * key of whatever daemon is already up. So the rule exists in bash AND in TypeScript, and a copy
 * that picks the other root reports "mgmt-key not found" on a healthy install, or cold-starts a
 * second daemon against an empty state dir while the real one is serving.
 *
 * THE DENOMINATOR IS ENUMERATED AND THEN PINNED, which is two different jobs. Walking the tree
 * catches a copy someone ADDS. It does not catch a copy someone DELETES, RENAMES, or edits out of
 * the shape the walk recognises — and an enumeration that silently shrinks to one file still passes
 * `isNotEmpty`. So [REQUIRED] names the copies that must be there by path: a move fails by name,
 * and anything else the walk finds is driven too.
 *
 * The table is the same one StateRootTest drives against StatePaths, plus the shapes that ACTUALLY
 * DIVERGED before this wall covered them: a blank variable (`??` in TypeScript skipped the second
 * variable entirely; `-n` in bash called " " an answer), a non-directory at a root path
 * (`Files.exists` said true, `[ -d ]` said false), and an unreadable current root (`[ ! -d ]` is
 * true for "cannot stat", so bash adopted where Kotlin declines).
 */
class StateDirAgreementTest {

    private val repo: Path = run {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.exists(dir.resolve("install.sh")) && dir.parent != null) dir = dir.parent
        dir
    }

    private fun shellResolvers(): List<Path> =
        listOf("bin", "checks", ".dev").map(repo::resolve).filter { Files.exists(it) }
            .flatMap { start -> Files.walk(start).use { walk -> walk.toList() } }
            .filter { it.isRegularFile() && declaresResolver(it) }
            .sorted()

    private fun declaresResolver(file: Path): Boolean =
        runCatching { file.readText() }.getOrNull()?.let { text ->
            "\nresolve_state_dir() {" in text || "export function liveStateDir(" in text
        } ?: false

    /** A bash copy is sourced out of its file; a JS/TS copy is imported and called. Same contract,
     *  two runtimes — which is the point: the rule is not bash's, it is splice's. */
    private fun resolveWith(script: Path, home: Path, env: Map<String, String>): String {
        val absolute = script.toAbsolutePath().toString()
        val command = if (script.name.endsWith(".ts") || script.name.endsWith(".mjs")) {
            // Through the ENVIRONMENT, not argv: `bun -e` does not shift argv the way a file
            // invocation does, and an off-by-one silently imported the HOME path as a module.
            listOf(
                "bun",
                "-e",
                "const m = await import(process.env.PIN_MODULE); " +
                    "console.log(m.liveStateDir(process.env.PIN_HOME, JSON.parse(process.env.PIN_ENV)))",
            )
        } else {
            listOf("bash", "-c", "source <(sed -n '/^resolve_state_dir()/,/^}/p' $absolute); resolve_state_dir")
        }
        val builder = ProcessBuilder(command)
        builder.directory(repo.toFile())
        builder.environment().apply {
            remove(STATE_DIR_ENV)
            remove(LEGACY_STATE_DIR_ENV)
            put("HOME", home.toString())
            put("PIN_MODULE", absolute)
            put("PIN_HOME", home.toString())
            put("PIN_ENV", jsonOf(env))
            putAll(env)
        }
        builder.redirectErrorStream(true)
        val process = builder.start()
        val output = process.inputStream.readBytes().decodeToString().trim()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "$script resolver did not finish")
        assertEquals(0, process.exitValue(), "$script resolver failed: $output")
        return output
    }

    private fun jsonOf(env: Map<String, String>): String =
        env.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }

    @Test
    fun `every copy of the state-root rule answers exactly what StatePaths answers`(@TempDir root: Path) {
        val scripts = shellResolvers()
        val found = scripts.map { repo.relativize(it).toString() }.toSet()
        assertTrue(
            found.containsAll(REQUIRED),
            "a pinned copy of the rule is missing or moved: ${REQUIRED - found} (found: $found)",
        )

        for ((name, env) in CASES) {
            val home = Files.createDirectories(root.resolve(name))
            shape(name, home)
            val expected = StatePaths(envReader = EnvReader { key -> env[key] }, homeDir = home).stateDir.toString()

            for (script in scripts) {
                assertEquals(
                    expected,
                    resolveWith(script, home, env),
                    "$name: ${repo.relativize(script)} disagrees with StatePaths",
                )
            }
            restore(name, home)
        }
    }

    private fun shape(name: String, home: Path) {
        when (name) {
            "legacy-only", "env-legacy", "env-both", "env-blank-falls-to-legacy", "env-whitespace" ->
                makeState(home, LEGACY_ROOT)
            "both" -> {
                makeState(home, LEGACY_ROOT)
                makeState(home, SPLICE_ROOT)
            }
            // The collision shape: the pre-0.4 root present as the codex head's CLAUDE_CONFIG_DIR,
            // with no `state` leaf. Neither implementation may adopt it.
            "legacy-root-without-state-leaf" ->
                Files.createDirectories(home.resolve(LEGACY_ROOT).resolve("projects"))
            // A FILE where a state dir belongs. Files.exists called this true and `[ -d ]` called it
            // false, so the two implementations picked different roots until both probed for a
            // directory.
            "legacy-state-is-a-file" -> {
                Files.createDirectories(home.resolve(LEGACY_ROOT))
                Files.writeString(home.resolve(LEGACY_ROOT).resolve("state"), "not a directory")
            }
            "current-state-is-a-file" -> {
                makeState(home, LEGACY_ROOT)
                Files.createDirectories(home.resolve(SPLICE_ROOT))
                Files.writeString(home.resolve(SPLICE_ROOT).resolve("state"), "not a directory")
            }
            // Cannot be stat-ed. `[ ! -d ]` is true here exactly as it is for absent, so bash adopted
            // the pre-0.4 root while StatePaths declines and warns — the proven-absence law.
            "current-root-unreadable" -> {
                makeState(home, LEGACY_ROOT)
                Files.createDirectories(home.resolve(SPLICE_ROOT).resolve("state"))
                home.resolve(SPLICE_ROOT).toFile().setReadable(false, false)
                home.resolve(SPLICE_ROOT).toFile().setExecutable(false, false)
                assumeTrue(
                    !Files.isReadable(home.resolve(SPLICE_ROOT).resolve("state")),
                    "running as a user that ignores the permission bits (root); this shape is unobservable here",
                )
            }
            else -> Unit
        }
    }

    private fun restore(name: String, home: Path) {
        if (name == "current-root-unreadable") {
            home.resolve(SPLICE_ROOT).toFile().setReadable(true, false)
            home.resolve(SPLICE_ROOT).toFile().setExecutable(true, false)
        }
    }

    // Mutant: the harness itself. If a copy stopped being extractable — renamed, or its body no
    // longer closing on a column-0 brace — every arm above would compare nothing against nothing.
    // Plant a resolver that is deliberately WRONG and require the comparison to notice.
    @Test
    fun `the harness would catch a copy that resolved the wrong root`(@TempDir root: Path) {
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

    // Mutant: the JS runner. A `bun -e` that imported nothing, or a module that stopped exporting
    // liveStateDir, would make every JS/TS arm above vacuous in a way the bash mutant cannot see.
    @Test
    fun `the harness would catch a JS copy that resolved the wrong root`(@TempDir root: Path) {
        val home = Files.createDirectories(root.resolve("home"))
        makeState(home, LEGACY_ROOT)
        val wrong = root.resolve("wrong.mjs")
        Files.writeString(wrong, "export function liveStateDir(home) { return home + '/" + SPLICE_ROOT + "/state'; }\n")

        val expected = StatePaths(envReader = NO_ENV, homeDir = home).stateDir.toString()

        assertTrue(
            resolveWith(wrong, home, emptyMap()) != expected,
            "a JS resolver that ignores the pre-0.4 root must not compare equal — the runner is not calling it",
        )
    }
}
