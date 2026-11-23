// What an upgrade does once its release passed: the NEW jar's `splice upgrade --aftercare` (UpgradeAftercare). Its
// maintenance runs on every upgrade and never fails one; its "What's new / action needed" list is printed only on an
// interactive upgrade, never on the daemon's `--auto` run; and a release that failed its checks gets no aftercare.
package splice.lifecycle.upgrade

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import java.nio.file.Path

internal class UpgradeAftercareTest : UpgradeRig() {

    @Test
    fun `a release that passed runs its own aftercare, on the new jar`(@TempDir home: Path) {
        flatInstall(home)
        pristine(home)
        val (ok, out) = captured { command(home, release(home)).upgrade(listOf("--to", "v9.9.9")) }
        assertTrue(ok, out)
        val jar = home.resolve("share/splice.jar").toString()
        assertEquals(listOf("java", "-jar", jar, "upgrade", AFTERCARE_FLAG), calls.single { AFTERCARE_FLAG in it })
    }

    @Test
    fun `the daemon's auto upgrade asks its aftercare for maintenance only`(@TempDir home: Path) {
        flatInstall(home)
        pristine(home)
        val (ok, out) = captured { command(home, release(home)).upgrade(listOf("--auto", "--to", "v9.9.9")) }
        assertTrue(ok, out)
        assertTrue(AUTO_FLAG in calls.single { AFTERCARE_FLAG in it }, calls.toString())
    }

    @Test
    fun `a release whose doctor is red is put back and gets no aftercare`(@TempDir home: Path) {
        flatInstall(home)
        pristine(home)
        postUpgradeDoctor = 1
        val (ok, out) = captured { command(home, release(home)).upgrade(listOf("--to", "v9.9.9")) }
        assertFalse(ok, out)
        assertTrue(calls.none { AFTERCARE_FLAG in it }, calls.toString())
    }

    @Test
    fun `an interactive aftercare lists what is not set up yet and where to set it up`() {
        val said = mutableListOf<String>()
        val aftercare = UpgradeAftercare(
            TerminalOutput { said += it },
            gaps = AftercareGaps { listOf("Claude Desktop is on the older Code-tab-only door") },
        )
        assertTrue(aftercare.run(auto = false))
        assertTrue(said.any { "What's new / action needed" in it }, said.toString())
        assertTrue(said.any { "older Code-tab-only door" in it }, said.toString())
        assertTrue(said.any { "splice setup --update" in it }, said.toString())
    }

    @Test
    fun `an auto aftercare runs its steps and prints no list`() {
        val said = mutableListOf<String>()
        var ran = 0
        val aftercare = UpgradeAftercare(
            TerminalOutput { said += it },
            steps = listOf(AftercareStep { listOf("laid the shim").also { ran++ } }),
            gaps = AftercareGaps { listOf("a gap") },
        )
        assertTrue(aftercare.run(auto = true))
        assertEquals(1, ran)
        assertTrue(said.any { "laid the shim" in it }, said.toString())
        assertFalse(said.any { "What's new" in it || "a gap" in it }, said.toString())
    }

    @Test
    fun `a step that fails is said and the next step still runs`() {
        val said = mutableListOf<String>()
        val aftercare = UpgradeAftercare(
            TerminalOutput { said += it },
            steps = listOf(AftercareStep { error("disk full") }, AftercareStep { listOf("refreshed the hub") }),
        )
        assertTrue(aftercare.run(auto = true), "nothing in the aftercare can fail an upgrade that passed")
        assertTrue(said.any { "did not run" in it }, said.toString())
        assertTrue(said.any { "refreshed the hub" in it }, said.toString())
    }

    @Test
    fun `nothing to say prints no heading`() {
        val said = mutableListOf<String>()
        assertTrue(UpgradeAftercare(TerminalOutput { said += it }).run(auto = false))
        assertEquals(emptyList<String>(), said)
    }

    @Test
    fun `the verb answers --aftercare with the aftercare it was handed`() {
        var ran = 0
        val verb = UpgradeVerb(
            TerminalOutput { },
            TerminalOutput { },
            EnvReader { null },
            VersionedRestart { true },
            aftercare = UpgradeAftercare(
                TerminalOutput { },
                steps = listOf(AftercareStep { emptyList<String>().also { ran++ } }),
            ),
        )
        assertTrue(verb.upgrade(listOf(AFTERCARE_FLAG, AUTO_FLAG)))
        assertEquals(1, ran, "the step ran, and nothing was fetched or restarted")
    }
}
