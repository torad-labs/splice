// PORT-OF: server/test/launcher.test.mjs prepareClaudexConfig pins @ 4ca99f7 — refuses outside
// .claude*, settings is a REAL merged file (never a symlink), availableModels + enforce +
// preserved-allowed-model + statusline, shared items symlinked, a real operator DIRECTORY never
// deleted, .claude.json gets modelOptionsCache + MCP inherit + PORT_KEYS + onboarding, isolate
// overrides share.
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.launch.ClaudeConfigMaterializer
import splice.core.launch.ClaudePolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ClaudeConfigMaterializerTest {

    private val allPolicy = ClaudePolicy(
        share = setOf("settings", "settings.json", "agents", "commands", "skills", "hooks", "plugins", "CLAUDE.md"),
        isolate = emptySet(),
    )
    private val optionsCache = buildJsonObject { put("cache", "codex-models") }

    private fun materializer(home: Path) =
        ClaudeConfigMaterializer(home, statuslineCommand = "\"/usr/bin/curl\" -s :3096/statusline")

    private fun seedGlobal(home: Path) {
        val g = home.resolve(".claude")
        Files.createDirectories(g.resolve("agents"))
        Files.createDirectories(g.resolve("commands"))
        g.resolve("settings.json").writeText("""{"theme":"dark","permissions":{"allow":["Bash"]}}""")
        g.resolve("CLAUDE.md").writeText("global rules")
        home.resolve(".claude.json").writeText(
            """{"mcpServers":{"fs":{"command":"x"}},"verbose":true,"theme":"dark","extra":"keepme"}""",
        )
    }

    @Test
    fun `refuses to materialize outside a claude dir`(@TempDir tmp: Path) {
        assertThrows(IllegalArgumentException::class.java) {
            materializer(tmp).materialize(
                tmp.resolve("other"),
                allPolicy,
                listOf("gpt-5.6-sol"),
                "gpt-5.6-sol",
                optionsCache,
            )
        }
    }

    @Test
    fun `settings is a real merged file with allowlist, enforce, statusline`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val dir = tmp.resolve(".claude-codex")
        materializer(tmp).materialize(dir, allPolicy, listOf("gpt-5.6-sol", "gpt-5.4"), "gpt-5.6-sol", optionsCache)
        val settings = dir.resolve("settings.json")
        assertFalse(settings.isSymbolicLink()) // never a symlink (would clobber global)
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(settings.readText()).jsonObject
        assertEquals(
            listOf("gpt-5.6-sol", "gpt-5.4"),
            obj["availableModels"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("true", obj["enforceAvailableModels"]?.jsonPrimitive?.content)
        assertEquals("gpt-5.6-sol", obj["model"]?.jsonPrimitive?.content)
        assertEquals("dark", obj["theme"]?.jsonPrimitive?.content) // global merged in
        assertTrue(obj["statusLine"]!!.jsonObject["command"]?.jsonPrimitive?.content!!.contains("statusline"))
    }

    @Test
    fun `preserves a saved model choice when still allowed`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val dir = tmp.resolve(".claude-codex")
        Files.createDirectories(dir)
        dir.resolve("settings.json").writeText("""{"model":"gpt-5.4"}""")
        materializer(tmp).materialize(dir, allPolicy, listOf("gpt-5.6-sol", "gpt-5.4"), "gpt-5.6-sol", optionsCache)
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(dir.resolve("settings.json").readText()).jsonObject
        assertEquals("gpt-5.4", obj["model"]?.jsonPrimitive?.content) // preserved (still allowed)
        // a disallowed saved choice falls back to default
        dir.resolve("settings.json").writeText("""{"model":"gpt-4o"}""")
        materializer(tmp).materialize(dir, allPolicy, listOf("gpt-5.6-sol"), "gpt-5.6-sol", optionsCache)
        val obj2 = kotlinx.serialization.json.Json
            .parseToJsonElement(dir.resolve("settings.json").readText())
            .jsonObject
        assertEquals("gpt-5.6-sol", obj2["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun `shared items symlink but a real operator directory is never deleted`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val dir = tmp.resolve(".claude-codex")
        Files.createDirectories(dir.resolve("agents")) // a REAL dir the operator made
        materializer(tmp).materialize(dir, allPolicy, listOf("gpt-5.6-sol"), "gpt-5.6-sol", optionsCache)
        assertTrue(dir.resolve("commands").isSymbolicLink()) // fresh link
        assertFalse(dir.resolve("agents").isSymbolicLink()) // real dir preserved, not replaced
        assertTrue(Files.isDirectory(dir.resolve("agents")))
    }

    @Test
    fun `claude json gets model cache, mcp inherit, port keys, onboarding`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val dir = tmp.resolve(".claude-codex")
        val result = materializer(tmp).materialize(dir, allPolicy, listOf("gpt-5.6-sol"), "gpt-5.6-sol", optionsCache)
        assertEquals(1, result.mcpServers)
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(dir.resolve(".claude.json").readText()).jsonObject
        assertTrue(obj["additionalModelOptionsCache"]!!.jsonObject.containsKey("cache"))
        assertTrue(obj["mcpServers"]!!.jsonObject.containsKey("fs"))
        assertEquals("true", obj["verbose"]?.jsonPrimitive?.content) // PORT_KEY inherited
        assertEquals("true", obj["hasCompletedOnboarding"]?.jsonPrimitive?.content)
    }

    @Test
    fun `isolate overrides share - item not linked`(@TempDir tmp: Path) {
        seedGlobal(tmp)
        val dir = tmp.resolve(".claude-codex")
        val policy = ClaudePolicy(share = allPolicy.share, isolate = setOf("commands"))
        materializer(tmp).materialize(dir, policy, listOf("gpt-5.6-sol"), "gpt-5.6-sol", optionsCache)
        assertFalse(Files.exists(dir.resolve("commands"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) // isolated: no link
        assertTrue(dir.resolve("agents").isSymbolicLink()) // still shared
    }
}
