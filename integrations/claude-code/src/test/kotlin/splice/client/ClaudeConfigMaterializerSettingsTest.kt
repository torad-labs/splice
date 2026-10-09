package splice.client

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class ClaudeConfigMaterializerSettingsTest {

    private val optionsCache = buildJsonObject { put("cache", "codex-models") }

    private fun spec(configDir: Path) = MaterializeSpec(
        configDir = configDir,
        policy = ClaudePolicy(share = setOf("agents"), isolate = emptySet()),
        availableModelIds = listOf("m1"),
        defaultModel = "m1",
        modelOptionsCache = optionsCache,
        statuslineCommand = "curl",
        signIn = MaterializeSignIn(
            loginCommand = "claudex login",
            signInLabel = "Codex",
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
    fun `a non-primitive saved model falls back to the default instead of aborting`(@TempDir home: Path) {
        Files.createDirectories(home.resolve(".claude").resolve("agents"))
        val configDir = Files.createDirectories(home.resolve(".claude-head"))
        val settings = configDir.resolve("settings.json")
        Files.writeString(settings, """{"model":{"id":"m1"}}""")
        val outcome = runCatching {
            ClaudeConfigMaterializer(home, log = LogSink { }).materialize(spec(configDir))
        }
        assertTrue(
            outcome.isSuccess,
            "a non-primitive saved model must not throw out of materialize: ${outcome.exceptionOrNull()}",
        )
        val written = Files.readString(settings).filterNot { it.isWhitespace() }
        assertTrue(written.contains("\"model\":\"m1\""), "an unusable saved model falls back to the default: $written")
    }
}
