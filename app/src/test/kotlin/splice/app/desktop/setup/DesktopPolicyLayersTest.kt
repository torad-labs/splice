// What Claude Desktop reads, layer by layer, on a fixture home: splice's default, the person's Claude Code over it, the
// console's values over that, and splice's own keys over everything; for the configuration splice writes and for one
// on the door the person made or edited themselves.
package splice.app.desktop.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.desktop.DesktopAppConfig
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class DesktopPolicyLayersTest {
    @TempDir
    lateinit var tmp: Path

    private val door = "http://127.0.0.1:3096/door"
    private val env by lazy {
        val vars = mapOf(
            "HOME" to tmp.resolve("home").toString(),
            "XDG_CONFIG_HOME" to tmp.resolve("config").toString(),
        )
        EnvReader { vars[it] }
    }
    private val config by lazy { DesktopAppConfig(env, osName = "Linux") }
    private val overrides by lazy { DesktopPolicyOverrides(tmp.resolve("state/desktop-policy-overrides.json")) }
    private val library by lazy { DesktopAppLibrary(config, managedPaths = emptyList(), overrides = overrides) }

    @Test
    fun `the console's value wins over the build's default and the person's Claude Code, never over splice's keys`() {
        val claude = Files.createDirectories(tmp.resolve("home/.claude"))
        Files.writeString(claude.resolve("settings.json"), """{"effortLevel":"low"}""")
        Files.createDirectories(overrides.file.parent)
        Files.writeString(overrides.file, """{"defaultModelEffort":"max","inferenceProvider":"bedrock"}""")
        library.putIn(ID, door, tmp.resolve("helper"), held = null)
        val written = desktop()
        assertEquals("max", written.str("defaultModelEffort"))
        assertEquals("gateway", written.str("inferenceProvider"))
        assertEquals("3p", written.str("deploymentMode"))
        assertEquals("standard", written.str("desktopHome"), "a key the console leaves alone keeps the default")
    }

    @Test
    fun `a value the person edited in their configuration gives way to the console's, and comes back on reset`() {
        library.putIn(ID, door, tmp.resolve("helper"), held = null)
        val edited = JsonObject(desktop() + mapOf("mcpToolTimeoutSec" to Json.parseToJsonElement("120")))
        Files.writeString(config.file(ID), edited.toString())
        assertEquals(false, library.publish(door), "with no console value, the person's edit stays")
        assertEquals("120", desktop().str("mcpToolTimeoutSec"))

        overrides.write(Json.parseToJsonElement("""{"mcpToolTimeoutSec":600}""").jsonObject)
        assertTrue(library.publish(door))
        assertEquals("600", desktop().str("mcpToolTimeoutSec"))

        overrides.write(JsonObject(emptyMap()))
        assertTrue(library.publish(door, reset = setOf("mcpToolTimeoutSec")))
        assertEquals("1800", desktop().str("mcpToolTimeoutSec"), "back to splice's default")
    }

    @Test
    fun `part of the import settings in the console keeps the rest of splice's`() {
        overrides.write(Json.parseToJsonElement("""{"claudeAiImport":{"bannerBehavior":"off"}}""").jsonObject)
        library.putIn(ID, door, tmp.resolve("helper"), held = null)
        val import = desktop().getValue("claudeAiImport").jsonObject
        assertEquals("off", import.str("bannerBehavior"))
        assertEquals("true", import.str("enabled"))
        assertEquals("10", import.str("bannerMaxDismissals"))
    }

    private fun desktop(): JsonObject = Json.parseToJsonElement(Files.readString(config.file(ID))).jsonObject

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
}

private const val ID = "0d0d0d0d-1111-4c2d-9e8f-123456789abc"
