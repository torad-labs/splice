// NEW: Oct 26, 2026 (a relaunch brings a session back as it was) — the words the person gave a launch, read back from
// the arguments its client runs with. A relaunch starts the session on the command it was started with, and that
// launch puts splice's own words in front again, so only the person's may be carried: splice's would arrive twice, and
// a hub file named for the old launch's process is swept once that process is gone.
package splice.launch.recipe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.random.Random

// why: how many launches the property draws; each is a dozen words, so the whole run takes well under a second
private const val RUNS = 2_000
private const val SEED = 20_261_026L
private const val SKIP = "--dangerously-skip-permissions"
private const val MARK = """{"spliceLaunch":true}"""

// why: the peer server's inline config as a launch gives it, its one server splice's peer server
private const val PEER =
    """{"mcpServers":{"splice-peers":{"type":"stdio","command":"/x/splice","args":["peer","mcp"]}}}"""

/** What a person types after `claude`: none of it is a file in splice's state, and none is splice's own hub flag. */
private val GIVEN = listOf(
    listOf("--agent", "reviewer"), listOf("-n", "splice-review-3"), listOf("--allow-dangerously-skip-permissions"),
    listOf(SKIP), listOf("--model", "claude-fable-5-1"), listOf("--settings", "/home/p/settings.json"),
    listOf("--settings", """{"model":"x"}"""), listOf("--mcp-config", "/home/p/servers.json"),
    listOf("-r", "alpha-lead"),
    listOf("/review-pr https://github.com/torad/splice/pull/251"), listOf("--", "fix it"),
)

class LaunchRecipeArgsTest {

    @TempDir
    lateinit var state: Path

    @Test
    fun `a wrapped launch gives back what followed its overlay and its hub file`() {
        val hub = state.resolve("mcp-hub/launch-41.json").toString()
        val started = listOf(
            "--settings", """{"env":{"A":"1"}}""", "--mcp-config", hub, "--plugin-dir-no-mcp", "/p/a",
            "--plugin-dir-no-mcp", "/p/b", "--agent", "reviewer", "/review-pr 251",
        )
        val read = LaunchRecipeArgs.callerArgs(started, state, wrapped = true)

        assertEquals(listOf("--agent", "reviewer", "/review-pr 251"), read)
    }

    // Nov 21, 2026: a launch also carries the file of the MCP servers splice answers itself, under a flag of its own
    // after the hub's file and its plugin folders. It is the launch's, so it is not the person's words either; a file
    // the person named themselves, outside splice's state, still is.
    @Test
    fun `a launch gives back what followed the hub's file and the file of splice's own servers`() {
        val hub = state.resolve("mcp-hub/launch-41.json").toString()
        val own = state.resolve("own-mcp-claude-splice-door.json").toString()
        val started = listOf(
            "--settings", """{"env":{"A":"1"}}""", "--mcp-config", hub, "--plugin-dir-no-mcp", "/p/a",
            "--mcp-config", own,
            "--mcp-config", "/home/p/servers.json", "fix the tide table",
        )

        assertEquals(
            listOf("--mcp-config", "/home/p/servers.json", "fix the tide table"),
            LaunchRecipeArgs.callerArgs(started, state, wrapped = true),
        )
        assertEquals(
            listOf("fix the tide table"),
            LaunchRecipeArgs.callerArgs(
                listOf("--settings", "{}", "--mcp-config", own, "fix the tide table"),
                state,
                wrapped = true,
            ),
        )
    }

    @Test
    fun `a head's launch keeps settings the person named, and splice's mark goes`() {
        val own = listOf("--settings", "/home/p/s.json", "--name", "lead")
        assertEquals(own, LaunchRecipeArgs.callerArgs(own, state, wrapped = false), "his settings are his")
        assertEquals(
            listOf(SKIP, "--name", "lead"),
            LaunchRecipeArgs.callerArgs(listOf(SKIP, "--settings", MARK, "--name", "lead"), state, wrapped = false),
            "the skip the launch was asked for stays, and the macOS mark is splice's",
        )
    }

    /** Whatever a launch put in front, and whatever the person gave after it, the person's words come back whole and in
     *  order, with the skip the launch was asked for. */
    @Test
    fun `whatever a launch put in front, the person's words come back as he gave them`() {
        val random = Random(SEED)
        repeat(RUNS) { run ->
            val wrapped = random.nextBoolean()
            val skip = if (random.nextBoolean()) listOf(SKIP) else emptyList()
            val settings = when {
                wrapped -> listOf("--settings", """{"env":{"ANTHROPIC_BASE_URL":"http://127.0.0.1:3104"}}""")
                random.nextBoolean() -> listOf("--settings", MARK)
                else -> emptyList()
            }
            val mcp = when (random.nextInt(3)) {
                0 -> listOf("--mcp-config", state.resolve("mcp-hub/launch-$run.json").toString()) +
                    List(random.nextInt(0, 3)) { listOf("--plugin-dir-no-mcp", "/p/$it") }.flatten()
                1 -> listOf("--mcp-config", state.resolve("wrapped-mcp-claude-splice.json").toString())
                else -> emptyList()
            }
            val given = List(random.nextInt(0, 6)) { GIVEN.random(random) }.flatten()

            val read = LaunchRecipeArgs.callerArgs(skip + settings + mcp + given, state, wrapped)

            assertEquals(skip + given, read, "run $run: launched as ${skip + settings + mcp + given}")
        }
    }

    // Nov 26, 2026: the servers splice gives every launch, the file of the daemon's own and the peer server, are
    // splice's wherever a build put them: in front as one word now, after the person's words as two on Nov 25's build.
    @Test
    fun `a relaunch drops splice's servers the build of Nov 25 put after the person's words, and keeps the person's`() {
        val own = state.resolve("own-mcp-door.json").toString()
        val started = listOf(
            "--settings", MARK, "-n", "probe", "--mcp-config", "/home/p/servers.json",
            "--mcp-config", own, "--mcp-config", PEER, "--", "go",
        )
        assertEquals(
            listOf("-n", "probe", "--mcp-config", "/home/p/servers.json", "--", "go"),
            LaunchRecipeArgs.callerArgs(started, state, wrapped = true),
        )
    }

    @Test
    fun `a relaunch drops splice's servers given in front as one word`() {
        val own = state.resolve("own-mcp-door.json").toString()
        val started = listOf("--mcp-config=$PEER", SKIP, "--settings", MARK, "--mcp-config=$own", "-n", "probe")
        assertEquals(listOf(SKIP, "-n", "probe"), LaunchRecipeArgs.callerArgs(started, state, wrapped = true))
    }

    @Test
    fun `a file or an inline config of the person's own is never dropped, and keeps its flag`() {
        val own = state.resolve("own-mcp-door.json").toString()
        val mine = """{"mcpServers":{"splice-peers":{"type":"stdio","command":"/x"},"docs":{"type":"stdio"}}}"""
        val started =
            listOf("--settings", MARK, "-n", "probe", "--mcp-config", own, "/home/p/a.json", "--mcp-config=$mine")
        assertEquals(
            listOf("-n", "probe", "--mcp-config", "/home/p/a.json", "--mcp-config=$mine"),
            LaunchRecipeArgs.callerArgs(started, state, wrapped = true),
        )
    }
}
