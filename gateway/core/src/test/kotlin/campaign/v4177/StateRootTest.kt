// NEW: V4-177 — the state root's resolution, pinned as a table over the filesystem states a real
// box can be in rather than as one happy path.
//
// WHY EVERY ARM AND NOT JUST "THE DEFAULT MOVED". This row changes where a LIVE install's mgmt-key,
// config.json and every head's usage/perf/compact history are read from. The rename is one line; the
// whole risk is in the branch beside it, and each branch has a distinct way of being wrong that no
// other branch would reveal:
//  - the adoption branch wrong → the daemon comes up on an empty root and the operator sees a box
//    that lost every head's history (indistinguishable, from outside, from data loss);
//  - the leaf check wrong → `~/.claude-codex` is ALSO the wrapped Claude Code config dir of the head
//    keyed `codex` (LaunchSpecFactory's `~/.claude-$key`), so probing the ROOT would "adopt" a
//    directory that never held splice state on boxes that merely ran that head;
//  - the env branch wrong → every hermetic test in this repo and `splice-launch` point the state dir
//    with a variable, and a precedence slip silently redirects them;
//  - a blank variable not falling through → `SPLICE_STATE_DIR=` in a unit file resolves to the
//    process working directory and scatters the mgmt-key wherever the daemon was started.
//
// The table is enumerated from the DECISION, not from the code: four filesystem shapes × the env
// and override precedence, plus the one derived-path arm that proves adoption is a whole-root
// decision rather than a stateDir-only one. `homeDir` is injected for exactly this — before V4-177
// the default read `System.getProperty("user.home")` with no seam, so no arm below could have
// existed without mutating a JVM global.
package campaign.v4177

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.LEGACY_STATE_DIR_ENV
import splice.core.config.LEGACY_STATE_HOME
import splice.core.config.SPLICE_STATE_HOME
import splice.core.config.STATE_DIR_ENV
import splice.core.config.StateDirOrigin
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

private val NO_ENV = EnvReader { null }

private fun envOf(vararg pairs: Pair<String, String>): EnvReader {
    val map = pairs.toMap()
    return EnvReader { name -> map[name] }
}

private fun makeState(home: Path, root: String): Path = Files.createDirectories(home.resolve(root).resolve("state"))

class StateRootTest {

    @Test
    fun `a box with neither root takes splice's own`(@TempDir home: Path) {
        val paths = StatePaths(envReader = NO_ENV, homeDir = home)

        assertEquals(home.resolve(SPLICE_STATE_HOME).resolve("state"), paths.stateDir)
        assertEquals(StateDirOrigin.DEFAULT, paths.origin)
        assertNull(paths.unmigratedLegacyDir, "nothing to migrate on a box that never had the old root")
    }

    // The upgrade case, and the one that matters on every existing install: the old root is the only
    // one there, so it IS the state dir — read in place, nothing copied, nothing moved.
    @Test
    fun `a box with only the pre-0_4 root reads it in place`(@TempDir home: Path) {
        val legacy = makeState(home, LEGACY_STATE_HOME)

        val paths = StatePaths(envReader = NO_ENV, homeDir = home)

        assertEquals(legacy, paths.stateDir)
        assertEquals(StateDirOrigin.ADOPTED_LEGACY, paths.origin)
        assertNull(paths.unmigratedLegacyDir, "the old root is IN USE here, so it is not unmigrated")
    }

    // Mutant: keep adopting once the operator has moved. A half-migrated box is the one where an
    // operator most needs to be told which root is live — doctor turns this into its WARN row.
    @Test
    fun `a box with both roots reads the new one and reports the old as unmigrated`(@TempDir home: Path) {
        val legacy = makeState(home, LEGACY_STATE_HOME)
        val current = makeState(home, SPLICE_STATE_HOME)

        val paths = StatePaths(envReader = NO_ENV, homeDir = home)

        assertEquals(current, paths.stateDir)
        assertEquals(StateDirOrigin.DEFAULT, paths.origin)
        assertEquals(legacy, paths.unmigratedLegacyDir)
    }

    // THE COLLISION ARM. `~/.claude-codex` is the wrapped Claude Code config dir of the head keyed
    // `codex`, so it exists — with projects/, statsig/, .claude.json — on boxes that never put splice
    // state in it. Probing the ROOT rather than the `state` leaf would adopt that directory, and the
    // daemon would then mint its mgmt-key and write every head's history INSIDE another product's
    // config dir. Nothing else in this file fails if that check is loosened.
    @Test
    fun `a pre-0_4 ROOT with no state leaf is a head config dir, not a state root`(@TempDir home: Path) {
        Files.createDirectories(home.resolve(LEGACY_STATE_HOME).resolve("projects"))
        Files.writeString(home.resolve(LEGACY_STATE_HOME).resolve(".claude.json"), "{}")

        val paths = StatePaths(envReader = NO_ENV, homeDir = home)

        assertEquals(home.resolve(SPLICE_STATE_HOME).resolve("state"), paths.stateDir)
        assertEquals(StateDirOrigin.DEFAULT, paths.origin)
        assertNull(paths.unmigratedLegacyDir, "a head's config dir is not pre-0.4 splice state")
    }

    @Test
    fun `the new variable points the state dir past both roots`(@TempDir home: Path) {
        makeState(home, LEGACY_STATE_HOME)
        makeState(home, SPLICE_STATE_HOME)
        val elsewhere = home.resolve("elsewhere")

        val paths = StatePaths(envReader = envOf(STATE_DIR_ENV to elsewhere.toString()), homeDir = home)

        assertEquals(elsewhere, paths.stateDir)
        assertEquals(StateDirOrigin.ENVIRONMENT, paths.origin)
        assertNull(paths.unmigratedLegacyDir, "a root nobody asked for is not this install's history")
    }

    // Every hermetic test in this repo, bin/splice-launch and checks/e2e/* set the OLD name. Dropping
    // it would not fail loudly — it would silently resolve to the real `$HOME`, so a test suite would
    // start reading and WRITING the operator's live state dir.
    @Test
    fun `the pre-0_4 variable still points the state dir`(@TempDir home: Path) {
        val elsewhere = home.resolve("hermetic")

        val paths = StatePaths(envReader = envOf(LEGACY_STATE_DIR_ENV to elsewhere.toString()), homeDir = home)

        assertEquals(elsewhere, paths.stateDir)
        assertEquals(StateDirOrigin.ENVIRONMENT, paths.origin)
    }

    @Test
    fun `the new variable wins when a box exports both`(@TempDir home: Path) {
        val paths = StatePaths(
            envReader = envOf(
                STATE_DIR_ENV to home.resolve("new").toString(),
                LEGACY_STATE_DIR_ENV to home.resolve("old").toString(),
            ),
            homeDir = home,
        )

        assertEquals(home.resolve("new"), paths.stateDir)
    }

    // Mutant: treat "set" as "answered". `SPLICE_STATE_DIR=` in a systemd unit or a sourced profile
    // would resolve to Paths.get("") — the process working directory — and put the mgmt-key and every
    // head's history wherever the daemon happened to be started from.
    @Test
    fun `a variable set to empty falls through instead of resolving to the working directory`(@TempDir home: Path) {
        val paths = StatePaths(envReader = envOf(STATE_DIR_ENV to "   ", LEGACY_STATE_DIR_ENV to ""), homeDir = home)

        assertEquals(home.resolve(SPLICE_STATE_HOME).resolve("state"), paths.stateDir)
        assertEquals(StateDirOrigin.DEFAULT, paths.origin)
    }

    @Test
    fun `an explicit path beats the environment`(@TempDir home: Path) {
        val given = home.resolve("given")

        val paths = StatePaths(given, envOf(STATE_DIR_ENV to home.resolve("env").toString()), home)

        assertEquals(given, paths.stateDir)
        assertEquals(StateDirOrigin.OVERRIDE, paths.origin)
        assertNull(paths.unmigratedLegacyDir)
    }

    // Adoption is a WHOLE-ROOT decision, not a stateDir-only one. logsDir and compactStatsFile hang
    // off rootDir (the parent), so an adoption that moved only stateDir would read the mgmt-key from
    // the old root and write daemon.log and the compact history into a brand-new one — history split
    // across two directories, which is worse than either root alone.
    @Test
    fun `an adopted root keeps the log and compact files with it`(@TempDir home: Path) {
        makeState(home, LEGACY_STATE_HOME)
        val root = home.resolve(LEGACY_STATE_HOME)

        val paths = StatePaths(envReader = NO_ENV, homeDir = home)

        assertEquals(root, paths.rootDir)
        assertEquals(root.resolve("logs"), paths.logsDir)
        assertEquals(root.resolve("claudex-compact-stats.jsonl"), paths.compactStatsFile("claudex"))
        assertEquals(root.resolve("state").resolve("mgmt-key"), paths.mgmtKeyFile)
    }
}
