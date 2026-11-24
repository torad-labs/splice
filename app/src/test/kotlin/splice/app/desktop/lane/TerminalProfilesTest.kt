// Terminal launch profiles (issue #791, comment §4): what the door hands `splice launch claude --profile <name>`: its
// side-by-side launch with CLAUDE_CONFIG_DIR at the folder just rebuilt, the door's own variables kept out of the
// profile's settings, and for a name splice.toml does not declare, a refusal that names the ones it does.
package splice.app.desktop.lane

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.mcp.McpUserRegistry
import splice.core.topology.LaunchProfileConfig
import splice.launch.recipe.DoorLaunchSpec
import splice.launch.recipe.ProfileLaunch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class TerminalProfilesTest {
    @TempDir lateinit var tmp: Path

    /** The test's home as it really is: a profile's folder is where its links lead (macOS keeps temp under a link). */
    private val home: Path get() = tmp.toRealPath()

    private val side = DoorLaunchSpec(
        env = mapOf("ANTHROPIC_BASE_URL" to "http://127.0.0.1:3096/door", "ANTHROPIC_MODEL" to "m"),
        unset = listOf("CLAUDE_CONFIG_DIR", "CLAUDE_CODE_USE_BEDROCK"),
        overlay = "{}",
        window = SESSION_WINDOW,
    )

    private fun profiles(): TerminalProfiles {
        val work = LaunchProfileConfig(dir = home.resolve("work").toString())
        val registry = McpUserRegistry(StatePaths(home.resolve("state")))
        val declared = mapOf("work" to work, "lean" to LaunchProfileConfig())
        return TerminalProfiles(declared, home, home.resolve("root"), registry, Dispatchers.IO)
    }

    @Test
    fun `a declared profile launches with CLAUDE_CONFIG_DIR at its rebuilt folder`() {
        Files.createDirectories(home.resolve(".claude"))
        home.resolve(".claude/settings.json")
            .writeText("""{"env":{"ANTHROPIC_MODEL":"x","CLAUDE_CODE_USE_BEDROCK":"1","KEEP":"1"}}""")
        val launch = runBlocking { profiles().launch("work", side) }
        val spec = assertInstanceOf(ProfileLaunch.Ready::class.java, launch).spec
        assertEquals(home.resolve("work").toString(), spec.env["CLAUDE_CONFIG_DIR"])
        assertFalse("CLAUDE_CONFIG_DIR" in spec.unset)
        assertEquals(side.overlay, spec.overlay)
        assertEquals(side.window, spec.window, "the door's own values ride through")
        val env = Json.parseToJsonElement(home.resolve("work/settings.json").readText()).jsonObject.getValue("env")
        assertEquals(setOf("KEEP"), env.jsonObject.keys, "the door's own variables stay the door's")
    }

    @Test
    fun `a profile splice-toml does not declare launches nothing and names the ones it does`() {
        val launch = runBlocking { profiles().launch("nope", side) }
        val reason = assertInstanceOf(ProfileLaunch.Refused::class.java, launch).reason
        assertTrue("nope" in reason && "lean, work" in reason, reason)
    }

    @Test
    fun `launches of one profile at once each get the whole folder, rebuilt one after the other`() {
        Files.createDirectories(home.resolve(".claude/skills/mine"))
        home.resolve(".claude/settings.json").writeText("""{"theme":"dark"}""")
        val profiles = profiles()
        val launches = runBlocking(Dispatchers.IO) {
            (1..PARALLEL_LAUNCHES).map { async { profiles.launch("work", side) } }.awaitAll()
        }
        launches.forEach { assertInstanceOf(ProfileLaunch.Ready::class.java, it, it.toString()) }
        val folder = home.resolve("work")
        assertEquals(home.resolve(".claude/skills/mine"), Files.readSymbolicLink(folder.resolve("skills/mine")))
        assertTrue(folder.resolve("settings.json").readText().contains("dark"))
    }

    @Test
    fun `two profiles whose dirs reach one folder through a link never rebuild it both`() {
        Files.createDirectories(home.resolve(".claude"))
        Files.createDirectories(home.resolve("shared"))
        Files.createSymbolicLink(home.resolve("alias"), home.resolve("shared"))
        val declared = mapOf(
            "a" to LaunchProfileConfig(dir = home.resolve("shared").toString()),
            "b" to LaunchProfileConfig(dir = home.resolve("alias").toString()),
        )
        val registry = McpUserRegistry(StatePaths(home.resolve("state")))
        val profiles = TerminalProfiles(declared, home, home.resolve("root"), registry, Dispatchers.IO)
        val names = (1..PARALLEL_LAUNCHES).map { if (it % 2 == 0) "a" else "b" }
        val launches = runBlocking(Dispatchers.IO) {
            names.map { name -> async { name to profiles.launch(name, side) } }.awaitAll()
        }
        val readyBy = launches.groupBy({ it.first }, { it.second is ProfileLaunch.Ready })
        val outcome = readyBy.mapValues { (_, ready) -> ready.distinct() }
        assertEquals(setOf(listOf(true), listOf(false)), outcome.values.toSet(), launches.toString())
    }
}

// Enough launches at once that two rebuilds would meet if nothing kept them apart.
private const val PARALLEL_LAUNCHES = 8

// why: any window does here; this is the one a door session declares by default, 200k tokens.
private const val SESSION_WINDOW = 200_000L
