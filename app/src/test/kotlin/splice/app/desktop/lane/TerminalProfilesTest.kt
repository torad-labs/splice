// Terminal launch profiles (issue #791, comment §4): what the door hands `splice launch claude --profile <name>`: its
// side-by-side launch with CLAUDE_CONFIG_DIR at the folder just rebuilt, the door's own variables kept out of the
// profile's settings, and for a name splice.toml does not declare, a refusal that names the ones it does.
package splice.app.desktop.lane

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
    @TempDir lateinit var home: Path

    private val side = DoorLaunchSpec(
        env = mapOf("ANTHROPIC_BASE_URL" to "http://127.0.0.1:3096/door", "ANTHROPIC_MODEL" to "m"),
        unset = listOf("CLAUDE_CONFIG_DIR", "CLAUDE_CODE_USE_BEDROCK"),
        overlay = "{}",
    )

    private fun profiles(): TerminalProfiles {
        val work = LaunchProfileConfig(dir = home.resolve("work").toString())
        val registry = McpUserRegistry(StatePaths(home.resolve("state")))
        val declared = mapOf("work" to work, "lean" to LaunchProfileConfig())
        return TerminalProfiles(declared, home, home.resolve("root"), registry)
    }

    @Test
    fun `a declared profile launches with CLAUDE_CONFIG_DIR at its rebuilt folder`() {
        Files.createDirectories(home.resolve(".claude"))
        home.resolve(".claude/settings.json")
            .writeText("""{"env":{"ANTHROPIC_MODEL":"x","CLAUDE_CODE_USE_BEDROCK":"1","KEEP":"1"}}""")
        val launch = profiles().launch("work", side)
        val spec = assertInstanceOf(ProfileLaunch.Ready::class.java, launch).spec
        assertEquals(home.resolve("work").toString(), spec.env["CLAUDE_CONFIG_DIR"])
        assertFalse("CLAUDE_CONFIG_DIR" in spec.unset)
        assertEquals(side.overlay, spec.overlay)
        val env = Json.parseToJsonElement(home.resolve("work/settings.json").readText()).jsonObject.getValue("env")
        assertEquals(setOf("KEEP"), env.jsonObject.keys, "the door's own variables stay the door's")
    }

    @Test
    fun `a profile splice-toml does not declare launches nothing and names the ones it does`() {
        val launch = profiles().launch("nope", side)
        val reason = assertInstanceOf(ProfileLaunch.Refused::class.java, launch).reason
        assertTrue("nope" in reason && "lean, work" in reason, reason)
    }
}
