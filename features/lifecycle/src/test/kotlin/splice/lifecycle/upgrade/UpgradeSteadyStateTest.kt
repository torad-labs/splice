// `splice upgrade` when the answer is "nothing to do" or "nothing to undo": the release already installed, the
// previous release kept through the prune, and a doctor that goes red after the restart.
package splice.lifecycle.upgrade

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

internal class UpgradeSteadyStateTest : UpgradeRig() {

    @Test
    fun `upgrading to the release already installed changes nothing and restarts nothing`(@TempDir home: Path) {
        val intact = flatInstall(home)
        reportedVersion = INSTALLED

        val (ok, out) = captured { command(home, release(home)).upgrade(listOf("--to", "v$INSTALLED")) }

        assertTrue(ok, out)
        assertTrue(out.contains("$INSTALLED is already installed"), out)
        assertEquals("old-jar", read(home, "splice.jar"), "the live jar is untouched")
        assertEquals(0, unitRestarts + verbRestarts, "no restart for an upgrade that changed nothing")
        assertEquals(0, stagingDirs(home), "the staged copy was discarded")
        assertIntact(intact)
    }

    @Test
    fun `the previous release survives the prune and debris does, until the next upgrade replaces it`(
        @TempDir home: Path,
    ) {
        flatInstall(home)
        Files.createDirectories(home.resolve("share/releases/1.0.0"))
        val base = release(home)

        assertTrue(captured { command(home, base).upgrade(listOf("--to", "v9.9.9", "--now")) }.first)
        assertEquals(setOf(INSTALLED, "9.9.9"), releaseDirs(home), "previous kept, old debris pruned")
        assertEquals(INSTALLED, link(home, "previous"))

        reportedVersion = "9.9.10"
        assertTrue(captured { command(home, base).upgrade(listOf("--to", "v9.9.10", "--now")) }.first)
        assertEquals(setOf("9.9.9", "9.9.10"), releaseDirs(home), "the next upgrade made 9.9.9 the previous")
        assertEquals("9.9.9", link(home, "previous"))
    }

    /** Pre-merge review, Oct 13: the doctor's verdict counts. Until then this test pinned the opposite, a red doctor
     *  that left the new release active and the upgrade reported as done. */
    @Test
    fun `a doctor that is red after the restart puts the previous release back and refuses the new one`(
        @TempDir home: Path,
    ) {
        flatInstall(home)
        postUpgradeDoctor = 1
        val base = release(home)

        val (ok, out) = captured { command(home, base).upgrade(listOf("--to", "v9.9.9", "--now")) }

        assertFalse(ok, out)
        assertEquals("old-jar", read(home, "splice.jar"), "the release it replaced is live again")
        assertEquals(INSTALLED, link(home, "current"))
        assertTrue(UpgradeLayout(env(home, base)).rejected.has("9.9.9"), "auto-update must not install it again: $out")
    }
}
