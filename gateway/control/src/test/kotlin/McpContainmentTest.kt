// NEW: V4-147 — a hosted MCP child is capped by a slice the host declares and left selectable by
// every reaper, and splice's own process keeps its protection. Both halves are pinned here, because
// neither has a gauge of its own: an uncapped cgroup looks exactly like a capped one from inside, and
// an adj that did not land reads as success unless something goes back and looks.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.control.mcp.APP_MCP_SLICE
import splice.control.mcp.HOSTED_ADJ
import splice.control.mcp.McpContainment
import splice.control.mcp.SliceMemoryCap
import splice.control.mcp.StdioProcessLauncher
import splice.core.launch.McpServerSpec
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// The floor an out-of-memory reaper is conventionally told to skip: it passes over anything at or
// below this, so a hosted child must sit strictly above — that is what the raise is for.
private const val ADJ_FLOOR = -400

private val COMMAND = listOf("npx", "-y", "@modelcontextprotocol/server-filesystem")

class McpContainmentTest {

    private val log = StringBuilder()
    private val sink = LogSink { synchronized(log) { log.append(it) } }
    private fun logged() = synchronized(log) { log.toString() }

    private fun containment(cap: SliceMemoryCap) = McpContainment(sink, cap = cap)

    // Mutant: treat a slice that EXISTS as containment. systemd answers LoadState=loaded for a slice
    // nobody declared, so every child would be reported contained while running uncapped.
    @Test
    fun `a slice with no memory ceiling is not containment, and it is said once`() {
        val uncapped = containment { "infinity" }

        assertEquals(COMMAND, uncapped.placed(COMMAND))
        assertEquals(COMMAND, uncapped.placed(COMMAND))
        assertTrue(logged().contains("$APP_MCP_SLICE declares no memory ceiling"), logged())
        assertEquals(1, logged().split("declares no memory ceiling").size - 1, "said once, not per spawn")
    }

    @Test
    fun `systemd that cannot be asked is not containment either`() {
        assertEquals(COMMAND, containment { null }.placed(COMMAND))
        assertTrue(logged().contains("not capped"), logged())
    }

    // Mutant: drop the --slice argument. The scope lands under the caller's own slice, which is
    // splice.service's cgroup — the uncapped place this row exists to leave.
    @Test
    fun `a slice that carries a ceiling is where the child is spawned`() {
        val placed = containment { "8589934592" }.placed(COMMAND)

        assertEquals(
            listOf("systemd-run", "--user", "--scope", "--quiet", "--slice=$APP_MCP_SLICE", "--") + COMMAND,
            placed,
        )
        assertFalse(logged().contains("not capped"), logged())
    }

    // Mutants, both required by the row: (1) protect() writes nothing, so the child keeps splice's
    // -1000 and no killer may touch it; (2) protect() writes the CURRENT process, which unpins the
    // splice JVM itself — the heart of the box — and the second assertion is what catches it.
    @Test
    fun `the child is raised off splice's protection and splice's own adj is untouched`(@TempDir dir: Path) {
        val ours = Path.of("/proc/self/oom_score_adj")
        assumeTrue(Files.exists(ours), "oom_score_adj is a Linux mechanism; there is nothing to assert without it")
        val before = Files.readString(ours).trim()

        val lowering = loweringPermitted()

        val child = StdioProcessLauncher(dir, containment { "infinity" })
            .invoke(McpServerSpec("sleeper", "sleep", listOf("30"), emptyMap()))
        try {
            val landed = Files.readString(Path.of("/proc/${child.pid()}/oom_score_adj")).trim()
            if (lowering) {
                assertEquals(
                    HOSTED_ADJ.toString(),
                    landed,
                    "read back from the child itself, never from the value we asked for",
                )
                assertFalse(logged().contains("oom_score_adj is"), logged())
            } else {
                // The other half of McpContainment.protect, which nothing reached before: a box that
                // refuses the write must leave splice SAYING the raise did not land. Silence here
                // would be the uncapped-looks-capped failure this file exists to catch, one level up.
                assertNotEquals(
                    HOSTED_ADJ.toString(),
                    landed,
                    "the write was refused, so a value that landed anyway means the probe is wrong",
                )
                assertTrue(
                    logged().contains("oom_score_adj is"),
                    "a raise that could not land must be said out loud: ${logged()}",
                )
            }
            assertEquals(before, Files.readString(ours).trim(), "splice's own protection is never written")
        } finally {
            child.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        }
    }

    /** Can THIS process lower a child's oom_score_adj to [HOSTED_ADJ]? Some environments refuse the
     *  write and there is then nothing for splice to land. MEASURED, not predicted: on the GitHub
     *  runner this arm read 500 where the value was never written, while here the same write lands
     *  at -100 — that gap is the whole CI-only red ("expected: <-100> but was: <500>"). The kernel
     *  rule usually quoted for this is CAP_SYS_RESOURCE, but that does not reproduce on this host,
     *  which lowers a child from 500 to -100 with an empty CapEff, so the precondition is left
     *  UNNAMED on purpose and simply attempted.
     *
     *  Probed on a THROWAWAY child with the test's own hands, never by looking at what splice did:
     *  choosing the branch from the outcome under test would pass whatever the code does. */
    private fun loweringPermitted(): Boolean {
        val probe = ProcessBuilder("sleep", "30").start()
        return try {
            val adj = Path.of("/proc/${probe.pid()}/oom_score_adj")
            Files.writeString(adj, HOSTED_ADJ.toString())
            Files.readString(adj).trim() == HOSTED_ADJ.toString()
        } catch (_: IOException) {
            false
        } finally {
            probe.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        }
    }

    // The raise has one job: leave the child selectable. A value at or below that floor would be a
    // quieter version of the -1000 this row removes.
    @Test
    fun `the hosted adj is strictly above the floor every reaper skips`() {
        assertTrue(HOSTED_ADJ > ADJ_FLOOR, "HOSTED_ADJ=$HOSTED_ADJ must sit above $ADJ_FLOOR")
        assertTrue(HOSTED_ADJ < 0, "and below an ordinary user process: one kill costs a capability")
    }
}
