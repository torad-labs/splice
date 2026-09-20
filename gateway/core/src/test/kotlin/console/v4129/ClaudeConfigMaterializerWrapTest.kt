// NEW: V4-129 — materializeWrap() is a DIFFERENT DOOR from materialize(), never a bypass of the
// DR-102 guard: this pins that the guard still refuses the general entry point on the SAME path
// materializeWrap is required to accept, so a future edit that quietly widened requireIsolatedDir's
// exemption instead of adding a real second entry point fails here.
package console.v4129

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.launch.ClaudeConfigMaterializer
import splice.core.launch.ClaudePolicy
import splice.core.launch.MaterializeSpec
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ClaudeConfigMaterializerWrapTest {

    private val optionsCache: JsonElement = buildJsonObject { put("cache", "claude-splice-models") }
    private val policy = ClaudePolicy(share = setOf("settings"), isolate = emptySet())

    private fun spec(configDir: Path) = MaterializeSpec(
        configDir = configDir,
        policy = policy,
        availableModelIds = listOf("claude-fable-5"),
        defaultModel = "claude-fable-5",
        modelOptionsCache = optionsCache,
        statuslineCommand = "curl -sS :3096/statusline/claude-splice",
    )

    @Test
    fun `the general entry point still refuses the vanilla dir — the guard is intact`(@TempDir home: Path) {
        val vanilla = home.resolve(".claude")
        assertThrows(IllegalArgumentException::class.java) {
            ClaudeConfigMaterializer(home).materialize(spec(vanilla))
        }
    }

    @Test
    fun `materializeWrap writes into that exact same path through the narrow door`(@TempDir home: Path) {
        val vanilla = home.resolve(".claude").createDirectories()
        vanilla.resolve("settings.json").writeText("""{"operatorOwn":"keepme"}""")

        val result = ClaudeConfigMaterializer(home).materializeWrap(spec(vanilla))

        assertEquals(vanilla, result.configDir)
        assertEquals(1, result.models)
        val settings = vanilla.resolve("settings.json").readText()
        assertTrue(settings.contains("keepme"), "shares=[settings] carries the operator's own key forward: $settings")
        assertTrue(settings.contains("\"claude-fable-5\""), settings)
        assertTrue(vanilla.resolve(".claude.json").readText().contains("additionalModelOptionsCache"))
    }

    @Test
    fun `materializeWrap never symlinks or migrates anything besides the two files`(@TempDir home: Path) {
        val vanilla = home.resolve(".claude").createDirectories()
        val agents = vanilla.resolve("agents").createDirectories()
        agents.resolve("marker.md").writeText("operator-authored, must survive untouched")

        ClaudeConfigMaterializer(home).materializeWrap(spec(vanilla))

        assertEquals("operator-authored, must survive untouched", agents.resolve("marker.md").readText())
        assertTrue(agents.toFile().isDirectory, "agents/ must stay a real directory, never replaced by a link")
    }
}
