// NEW: V4-232's settings.json `modelOverrides`: a head that presents a row writes the Claude model it is
// resolved as, merged over whatever the operator's shared settings map, the head winning a key; a head
// that presents nothing leaves the shared value exactly as it was carried before. The wrap's door takes
// no overrides at all: it writes the operator's own ~/.claude.
package campaign.v4232

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ClaudeConfigMaterializer
import splice.client.ClaudePolicy
import splice.client.MaterializeSpec
import java.nio.file.Files
import java.nio.file.Path

private val PRESENTED = mapOf("claude-sonnet-4-6" to "bonsai-2-27b")

class ModelOverridesMaterializeTest {

    private fun spec(home: Path, share: Set<String>) = MaterializeSpec(
        configDir = home.resolve(".claude-bonsai"),
        policy = ClaudePolicy(share = share, isolate = emptySet()),
        availableModelIds = listOf("bonsai-2-27b"),
        defaultModel = "bonsai-2-27b",
        modelOptionsCache = buildJsonObject { },
        statuslineCommand = "true",
    )

    private fun shared(home: Path, overrides: JsonObject) {
        Files.createDirectories(home.resolve(".claude"))
        val settings = buildJsonObject { put("modelOverrides", overrides) }
        Files.writeString(home.resolve(".claude/settings.json"), settings.toString())
    }

    private fun settings(file: Path): JsonObject = Json.parseToJsonElement(Files.readString(file)).jsonObject

    private fun headSettings(home: Path): JsonObject = settings(home.resolve(".claude-bonsai/settings.json"))

    @Test
    fun `a presenting head writes its row under the Claude model it is resolved as`(@TempDir home: Path) {
        ClaudeConfigMaterializer(home).materialize(spec(home, emptySet()), modelOverrides = PRESENTED)

        assertEquals(buildJsonObject { put("claude-sonnet-4-6", "bonsai-2-27b") }, headSettings(home)["modelOverrides"])
    }

    @Test
    fun `the head's map merges over the shared one, the head winning a key`(@TempDir home: Path) {
        val operators = buildJsonObject {
            put("claude-opus-4-6", "operator-profile-arn")
            put("claude-sonnet-4-6", "operator-sonnet-arn")
        }
        shared(home, operators)
        ClaudeConfigMaterializer(home).materialize(spec(home, setOf("settings")), modelOverrides = PRESENTED)

        val merged = buildJsonObject {
            put("claude-opus-4-6", "operator-profile-arn")
            put("claude-sonnet-4-6", "bonsai-2-27b")
        }
        assertEquals(merged, headSettings(home)["modelOverrides"])
        assertEquals(
            operators,
            settings(home.resolve(".claude/settings.json"))["modelOverrides"],
            "the operator's own settings.json is read, never written",
        )
    }

    @Test
    fun `a head presenting nothing carries the shared value as it was, and writes none`(@TempDir home: Path) {
        ClaudeConfigMaterializer(home).materialize(spec(home, emptySet()))
        assertNull(headSettings(home)["modelOverrides"])

        val other = Files.createDirectories(home.resolve("shared-home"))
        shared(other, JsonObject(emptyMap()))
        ClaudeConfigMaterializer(other).materialize(spec(other, setOf("settings")))
        assertEquals(JsonObject(emptyMap()), headSettings(other)["modelOverrides"])
    }
}
