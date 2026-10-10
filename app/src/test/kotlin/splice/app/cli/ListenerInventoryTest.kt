package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

/** What a socket manager reads to hold splice's ports: the boot parse's own listeners, nothing else. */
class ListenerInventoryTest {
    private val demo = """
        [providers.demo]
        dialect = "openai-chat"
        base_url = "https://demo.invalid"
        auth = { kind = "api-key", env = "DEMO_KEY_SECRET_NAME" }
        [heads.one]
        provider = "demo"
        port = 3101
        discovery_prefix = "claude-one--"
        pinned_model = "m"
        [heads.two]
        provider = "demo"
        port = 3102
        discovery_prefix = "claude-two--"
        pinned_model = "m"
    """

    private fun run(root: Path, text: String): Triple<Int, String, String> {
        val config = root.resolve("splice.toml")
        Files.writeString(config, text.trimIndent() + "\n")
        val out = mutableListOf<String>()
        val err = mutableListOf<String>()
        val env = EnvReader { name ->
            when (name) {
                "SPLICE_CONFIG" -> config.toString()
                "SPLICE_CONTROL_PORT" -> "3096"
                else -> null
            }
        }
        val code = ListenerInventory(env, { line: String -> out += line }, { line: String -> err += line }).print()
        return Triple(code, out.joinToString("\n"), err.joinToString("\n"))
    }

    @Test
    fun `the control port then each head, named for a socket manager, and no config value`(@TempDir root: Path) {
        val (code, out, _) = run(root, demo)

        assertEquals(0, code)
        val rows = Json.parseToJsonElement(out).jsonObject.getValue("listeners").jsonArray.map { it.jsonObject }
        assertEquals(listOf("control", "head-one", "head-two"), rows.map { it.getValue("name").jsonPrimitive.content })
        assertEquals(listOf("3096", "3101", "3102"), rows.map { it.getValue("port").jsonPrimitive.content })
        assertTrue(rows.all { it.getValue("bind").jsonPrimitive.content == "127.0.0.1" })
        assertEquals("one", rows[1].getValue("head").jsonPrimitive.content)
        assertFalse(out.contains("DEMO_KEY_SECRET_NAME") || out.contains("demo.invalid"), "only the four fields leave")
    }

    @Test
    fun `a splice toml boot would refuse prints its findings and no inventory`(@TempDir root: Path) {
        val (code, out, err) = run(root, demo.replace("provider = \"demo\"", "provider = \"missing\""))

        assertEquals(CONFIG_REFUSED_EXIT, code)
        assertEquals("", out)
        assertTrue(err.contains("heads.one.provider"), err)
    }

    @Test
    fun `capabilities say adoption is not built until it is`() {
        val out = mutableListOf<String>()
        assertEquals(0, CapabilityReport { line: String -> out += line }.print())
        val body = Json.parseToJsonElement(out.single()).jsonObject.getValue("socket_activation").jsonObject
        assertEquals("true", body.getValue("adopt_inherited").jsonPrimitive.content)
        assertEquals("LISTEN_FDNAMES", body.getValue("matches_by").jsonPrimitive.content)
    }
}
