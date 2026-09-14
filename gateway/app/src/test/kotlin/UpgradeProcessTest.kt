// NEW: v0.4.0 FEATURES.md §5 — the upgrade's process seam has a deadline: a verifier that hangs
// (gh waiting on a network that never answers) ends as a failure, never as an upgrade that hangs.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.cli.JdkUpgradeProcess

class UpgradeProcessTest {
    @Test
    fun `a command past its deadline is killed and reported, a quick one is read whole`() {
        val slow = JdkUpgradeProcess(timeoutMs = 300).invoke(listOf("sleep", "30"), inherit = false)
        assertEquals(124, slow.code)
        assertTrue(slow.stdout.contains("did not finish within"), slow.stdout)
        val quick = JdkUpgradeProcess(timeoutMs = 10_000).invoke(listOf("echo", "hi"), inherit = false)
        assertEquals(0 to "hi\n", quick.code to quick.stdout)
    }
}
