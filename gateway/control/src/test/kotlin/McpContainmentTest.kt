// NEW: V4-147 — a hosted MCP child is capped by a slice hostshield declares and left selectable by
// every reaper, and splice's own process keeps its protection. Both halves are pinned here, because
// neither has a gauge of its own: an uncapped cgroup looks exactly like a capped one from inside, and
// an adj that did not land reads as success unless something goes back and looks.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// hostshield's floor: a reaper skips anything at or below it, so a hosted child must sit strictly
// above — that is what the raise is for.
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

        val child = StdioProcessLauncher(dir, containment { "infinity" })
            .invoke(McpServerSpec("sleeper", "sleep", listOf("30"), emptyMap()))
        try {
            assertEquals(
                HOSTED_ADJ.toString(),
                Files.readString(Path.of("/proc/${child.pid()}/oom_score_adj")).trim(),
                "read back from the child itself, never from the value we asked for",
            )
            assertEquals(before, Files.readString(ours).trim(), "splice's own protection is never written")
            assertFalse(logged().contains("oom_score_adj is"), logged())
        } finally {
            child.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        }
    }

    // The raise has one job: leave the child selectable. A value at or below hostshield's floor would
    // be a quieter version of the -1000 this row removes.
    @Test
    fun `the hosted adj is strictly above the floor every reaper skips`() {
        assertTrue(HOSTED_ADJ > ADJ_FLOOR, "HOSTED_ADJ=$HOSTED_ADJ must sit above $ADJ_FLOOR")
        assertTrue(HOSTED_ADJ < 0, "and below an ordinary user process: one kill costs a capability")
    }
}
