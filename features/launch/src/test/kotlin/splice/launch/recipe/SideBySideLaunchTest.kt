// Terminal launch profiles (issue #791, comment §4): `splice launch claude --profile <name> [args...]` runs the
// side-by-side launch in the profile the door builds, hands Claude Code every argument but splice's own, and keeps the
// profile's CLAUDE_CONFIG_DIR in the recipe; a profile the door does not know, or `--profile` with no name, launches
// nothing and says why.
package splice.launch.recipe

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.wrap.WrapStateStore
import splice.client.wrap.WrappedHead
import splice.core.util.EnvReader
import splice.launch.LaunchOutcome
import java.nio.file.Path

class SideBySideLaunchTest {
    private val side = DoorLaunchSpec(
        env = mapOf("ANTHROPIC_BASE_URL" to "http://127.0.0.1:3096/door"),
        unset = listOf("CLAUDE_CONFIG_DIR", "ANTHROPIC_API_KEY"),
        overlay = "{}",
        window = SESSION_WINDOW,
    )

    /** A door that knows profile `work`, in /profiles/work. */
    private val door = object : ClaudeDoor {
        override suspend fun launch(): DoorLaunchSpec = side

        override suspend fun launchProfile(
            name: String,
            side: DoorLaunchSpec,
            inheritedConfigDir: String?,
        ): ProfileLaunch = if (name != "work") {
            ProfileLaunch.Refused("splice: no launch profile named '$name'")
        } else {
            ProfileLaunch.Ready(side.copy(env = side.env + ("CLAUDE_CONFIG_DIR" to "/profiles/work")))
        }

        override fun onDoor(): Boolean = true
    }

    private fun start(vararg args: String, plain: DoorLaunchSpec = side) =
        runBlocking { SideBySideLaunch(door).start(plain, args.toList(), null) }

    @Test
    fun `--profile and --profile= name the profile, and Claude Code gets the rest in order`() {
        for (args in listOf(arrayOf("--profile", "work", "-c", "hi"), arrayOf("-c", "--profile=work", "hi"))) {
            val ready = assertInstanceOf(SideBySideLaunch.Started.Ready::class.java, start(*args))
            assertEquals(listOf("-c", "hi"), ready.args)
            assertEquals("/profiles/work", ready.spec.env["CLAUDE_CONFIG_DIR"])
        }
    }

    @Test
    fun `no --profile is the plain side-by-side launch, and after -- every word is Claude Code's`() {
        val plain = assertInstanceOf(SideBySideLaunch.Started.Ready::class.java, start("-p", "hi"))
        assertEquals(side, plain.spec)
        assertEquals(listOf("-p", "hi"), plain.args)
        val after = assertInstanceOf(SideBySideLaunch.Started.Ready::class.java, start("--", "--profile", "work"))
        assertEquals(side, after.spec)
        assertEquals(listOf("--", "--profile", "work"), after.args)
    }

    @Test
    fun `an unknown profile, a missing name and a second --profile launch nothing`() {
        val unknown = assertInstanceOf(SideBySideLaunch.Started.Refused::class.java, start("--profile", "nope"))
        assertTrue("nope" in unknown.reason, unknown.reason)
        for (args in listOf(arrayOf("--profile"), arrayOf("--profile", "-c"), arrayOf("--profile="))) {
            assertInstanceOf(SideBySideLaunch.Started.Refused::class.java, start(*args), args.toList().toString())
        }
        assertInstanceOf(SideBySideLaunch.Started.Refused::class.java, start("--profile", "work", "--profile=work"))
    }

    @Test
    fun `the recipe sets the profile's CLAUDE_CONFIG_DIR and never takes it out`(@TempDir home: Path) {
        val ready = assertInstanceOf(SideBySideLaunch.Started.Ready::class.java, start("--profile", "work"))
        val wrap = WrappedHead(home, stateStore = WrapStateStore(home.resolve("wrap-state.json")))
        val recipe = DoorRecipe(wrap, wrap, "claude", EnvReader { null })
            .outcome(ready.spec, ready.args, false, LaunchCaller(), emptyList())
        val launched = assertInstanceOf(LaunchOutcome.Ready::class.java, recipe).recipe
        assertEquals("/profiles/work", launched.env["CLAUDE_CONFIG_DIR"])
        assertFalse("CLAUDE_CONFIG_DIR" in launched.unset, launched.unset.toString())
        assertFalse("--profile" in launched.argv, launched.argv.toString())
    }

    @Test
    fun `a profile's launch keeps the wall, timers and window the route gave the door's launch`() {
        val extended = side.copy(wallMs = EXTENDED_WALL_MS)
        val ready = assertInstanceOf(
            SideBySideLaunch.Started.Ready::class.java,
            start("--profile", "work", plain = extended),
        )
        assertEquals(EXTENDED_WALL_MS, ready.spec.wallMs)
        assertEquals(SESSION_WINDOW, ready.spec.window)
    }
}

// why: any window does here; this is the one a door session declares by default, 200k tokens.
private const val SESSION_WINDOW = 200_000L

// why: a head's 15-minute wall, longer than the door's own, as the route extends a launch to outlive it.
private const val EXTENDED_WALL_MS = 900_000L
