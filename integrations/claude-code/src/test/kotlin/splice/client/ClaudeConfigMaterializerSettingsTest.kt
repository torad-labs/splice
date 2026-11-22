package splice.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class ClaudeConfigMaterializerSettingsTest {

    private fun spec(configDir: Path) = MaterializeSpec(
        configDir = configDir,
        policy = ClaudePolicy(share = setOf("agents"), isolate = emptySet()),
        statuslineCommand = "curl",
        signIn = MaterializeSignIn(
            loginCommand = "claudex login",
        ),
    )

    @Test
    fun `an unreadable settings json aborts before the config dir is touched`(@TempDir home: Path) {
        Files.createDirectories(home.resolve(".claude").resolve("agents"))
        val configDir = Files.createDirectories(home.resolve(".claude-head"))
        val settings = configDir.resolve("settings.json")
        Files.writeString(settings, """{"model":"m1"}""")
        Files.setPosixFilePermissions(settings, PosixFilePermissions.fromString("---------"))
        try {
            val outcome = runCatching {
                ClaudeConfigMaterializer(home, log = LogSink { }).materialize(spec(configDir))
            }
            assertTrue(outcome.isFailure, "an unreadable real settings.json must abort the materialize")
            assertTrue(
                !Files.exists(configDir.resolve("agents"), LinkOption.NOFOLLOW_LINKS),
                "the abort must land before any shared link is made",
            )
            assertTrue(
                Files.list(configDir).use { entries -> entries.allMatch { it == settings } },
                "nothing but the pre-existing settings.json may exist after the abort",
            )
        } finally {
            Files.setPosixFilePermissions(settings, PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test
    fun `a pre-existing saved model is not kept`(@TempDir home: Path) {
        Files.createDirectories(home.resolve(".claude").resolve("agents"))
        val configDir = Files.createDirectories(home.resolve(".claude-head"))
        val settings = configDir.resolve("settings.json")
        Files.writeString(settings, """{"model":{"id":"m1"}}""")
        val outcome = runCatching {
            ClaudeConfigMaterializer(home, log = LogSink { }).materialize(spec(configDir))
        }
        assertTrue(
            outcome.isSuccess,
            "materialize with a pre-existing settings.json must succeed: ${outcome.exceptionOrNull()}",
        )
        val written = Files.readString(settings).filterNot { it.isWhitespace() }
        // head-catalog: settings.json is written whole, and it carries no model choice.
        assertFalse(written.contains("\"model\""), "a saved model is not kept: $written")
    }

    private fun sharing(configDir: Path, share: Set<String>, isolate: Set<String> = emptySet()) =
        spec(configDir).copy(policy = ClaudePolicy(share = share, isolate = isolate))

    private fun settingsOf(configDir: Path): JsonObject =
        Json.parseToJsonElement(Files.readString(configDir.resolve("settings.json"))).jsonObject

    @Test
    fun `a head keeps the keys it sets itself while the keys it took from global follow global`(@TempDir home: Path) {
        val global = Files.createDirectories(home.resolve(".claude")).resolve("settings.json")
        Files.writeString(
            global,
            """{"outputStyle":"Explanatory","includeCoAuthoredBy":false,"cleanupPeriodDays":30}""",
        )
        val configDir = home.resolve(".claude-head")
        val materializer = ClaudeConfigMaterializer(home, log = LogSink { })
        materializer.materialize(sharing(configDir, setOf("settings")))
        // The head's session sets a key of its own and changes one it took from global.
        val head = settingsOf(configDir)
        val own = mapOf("alwaysThinkingEnabled" to JsonPrimitive(true), "includeCoAuthoredBy" to JsonPrimitive(true))
        val edited = JsonObject(head + own)
        Files.writeString(configDir.resolve("settings.json"), edited.toString())
        // The person changes one global key and removes another.
        Files.writeString(global, """{"outputStyle":"Learning","includeCoAuthoredBy":false}""")

        materializer.materialize(sharing(configDir, setOf("settings")))

        val settings = settingsOf(configDir)
        assertEquals(JsonPrimitive(true), settings["alwaysThinkingEnabled"], "the head's own key survives: $settings")
        assertEquals(JsonPrimitive(true), settings["includeCoAuthoredBy"], "the head's own value wins: $settings")
        assertEquals(JsonPrimitive("Learning"), settings["outputStyle"], "an inherited key follows global: $settings")
        assertNull(settings["cleanupPeriodDays"], "a key global dropped goes from the head: $settings")
        assertFalse(settings.containsKey("model"), "no model choice is written: $settings")
        assertTrue(settings["statusLine"].toString().contains("curl"), "splice's own keys are still written: $settings")
    }

    @Test
    fun `with no record yet a key equal to global's is inherited and any other is the head's`(@TempDir home: Path) {
        val global = Files.createDirectories(home.resolve(".claude")).resolve("settings.json")
        Files.writeString(global, """{"outputStyle":"Explanatory"}""")
        val configDir = Files.createDirectories(home.resolve(".claude-head"))
        Files.writeString(
            configDir.resolve("settings.json"),
            """{"outputStyle":"Explanatory","alwaysThinkingEnabled":true,"model":"m1"}""",
        )
        val materializer = ClaudeConfigMaterializer(home, log = LogSink { })
        materializer.materialize(sharing(configDir, setOf("settings")))
        Files.writeString(global, """{"outputStyle":"Learning"}""")

        materializer.materialize(sharing(configDir, setOf("settings")))

        val settings = settingsOf(configDir)
        assertEquals(JsonPrimitive("Learning"), settings["outputStyle"], "the equal key followed global: $settings")
        assertEquals(JsonPrimitive(true), settings["alwaysThinkingEnabled"], "the head's own key survives: $settings")
    }

    @Test
    fun `a head that isolates settings keeps its own file's keys`(@TempDir home: Path) {
        val global = Files.createDirectories(home.resolve(".claude")).resolve("settings.json")
        Files.writeString(global, """{"outputStyle":"Explanatory"}""")
        val configDir = Files.createDirectories(home.resolve(".claude-head"))
        Files.writeString(configDir.resolve("settings.json"), """{"alwaysThinkingEnabled":true}""")

        ClaudeConfigMaterializer(home, log = LogSink { })
            .materialize(sharing(configDir, share = setOf("settings"), isolate = setOf("settings")))

        val settings = settingsOf(configDir)
        assertEquals(JsonPrimitive(true), settings["alwaysThinkingEnabled"], "the head's own key survives: $settings")
        assertNull(settings["outputStyle"], "an isolated head takes nothing from global: $settings")
    }
}
