// NEW: rig's exit codes in the operator's words, read straight off RigRefusals. rig v0.1.3 refuses a
// card or a disk too small for the head BEFORE the download (rig-dev #74), and says why in one
// sentence with the numbers; the wizard's flow around these lines is SetupLocalModelTest's.
package splice.app.cli.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class RigRefusalsTest {

    private val refusals = RigRefusals()

    @Test
    fun `up exit 3 is rig's own sentence, so a card below the VRAM floor is named with its numbers`() {
        val floor = "NVIDIA GeForce RTX 5070 at index 0 cannot serve bonsai-2-27b: 12227 MiB of VRAM is below " +
            "the smallest tier this head declares (16000 MiB)"
        val lines = refusals.up(RigRun(3, "", "$floor\n"))
        assertEquals(listOf(floor), lines)
    }

    @Test
    fun `up exit 2 is rig's own sentence, since v0_1_4 also refuses a port that neither answers nor refuses`() {
        val port = "REFUSING to start — :8099 did not answer /health in time and did not refuse the connection"
        assertEquals(listOf(port), refusals.up(RigRun(2, "", "$port\n")))
        assertEquals(listOf("rig refused to restart a head that is serving"), refusals.up(RigRun(2, "", "")))
    }

    @Test
    fun `exit 3 with nothing on stderr still says the card is the reason`() {
        assertEquals(listOf("this card is not one rig supports yet"), refusals.up(RigRun(3, "", "")))
        assertEquals(listOf("this card is not one rig supports yet"), refusals.prepare(RigRun(3, "", "\n")))
    }

    @Test
    fun `up exit 1 for a full disk leads with rig's numbers, and the head's log is offered, not presumed`() {
        val disk = "rig: ERROR not enough disk under /home/tester/.local/share/rig/local/packs/bonsai-2-27b: " +
            "bonsai-2-27b still needs 17.4 GB and 2.1 GB is free"
        val lines = refusals.up(RigRun(1, "", "$disk\n"))
        assertEquals(disk, lines[1], lines.toString())
        assertEquals("if the head started, its log: ~/.local/share/rig/local/logs/bonsai-2-27b.log", lines.last())
        assertFalse(lines.any { "journalctl" in it }, lines.toString())
    }
}
