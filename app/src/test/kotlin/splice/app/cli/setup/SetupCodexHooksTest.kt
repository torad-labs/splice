// Oct 28, 2026 (Marcos: "add the setup offer to write codex hooks"). A Codex thread joins splice peer messaging only
// through hooks in its Codex home's hooks.json, which on this machine were written by hand: they redirected splice's
// stdout to stderr, so no message could reach the thread through them. `splice setup` offers to write them. A yes adds
// `splice peer codex hook` to the five events it answers, replaces the hand-written splice attach and detach entries,
// keeps every other hook as it was, and keeps a copy of the file it changed. Once the hook is there, setup asks no more.
package splice.app.cli.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.terminal.ConfirmPrompt
import splice.terminal.WizardFrame
import java.nio.file.Files
import java.nio.file.Path

private const val OTHER = "/usr/local/bin/hookd-client --lifecycle Stop"
private const val OLD_ATTACH =
    "sh -c 'id=\$(jq -r \".session_id // empty\"); splice peer codex attach \"\$id\" --codex-home /h 1>&2 || exit 1'"
private const val OLD_DETACH = "sh -c 'id=\$(jq -r \".session_id // empty\"); splice peer codex detach \"\$id\" 1>&2'"
private val EVENTS = listOf("SessionStart", "UserPromptSubmit", "PostToolUse", "Stop", "SessionEnd")

class SetupCodexHooksTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a yes writes the hook for each event, replaces splice's own old hooks, keeps the rest, and asks no more`(
        @TempDir dir: Path,
    ) {
        val home = Files.createDirectories(dir.resolve("codex"))
        val hooks = home.resolve("hooks.json")
        Files.writeString(hooks, before())
        val env = mapOf("HOME" to dir.toString(), "CODEX_HOME" to home.toString())
        val questions = mutableListOf<String>()
        val offer = offer(env, questions, yes = true)

        val answer = offer.ask(watched = true)
        assertEquals(true, answer)
        assertTrue(questions.single().contains(hooks.toString()), questions.single())
        offer.save(answer)

        val after = json.parseToJsonElement(Files.readString(hooks)).jsonObject["hooks"] as JsonObject
        val bin = dir.resolve(".local/bin/splice")
        EVENTS.forEach { event ->
            val commands = commands(after, event)
            assertEquals(1, commands.count { it == "'$bin' peer codex hook" }, "$event: $commands")
            assertTrue(commands.none { it.contains("peer codex attach") || it.contains("peer codex detach") }, event)
        }
        assertEquals(listOf(OTHER), commands(after, "Stop").filter { it == OTHER }, "another tool's hook stays")
        assertEquals(listOf("PreToolUse"), (after.keys - EVENTS.toSet()).toList(), "an event splice does not use stays")
        val kept = Files.list(home).use { files -> files.filter { it != hooks }.toList() }
        assertEquals(before(), Files.readString(kept.single()), "the file as it was is kept beside it")

        assertNull(offer(env, mutableListOf(), yes = true).ask(watched = true), "the hook is there: nothing to ask")
    }

    @Test
    fun `a no, no one watching, or no Codex home writes nothing`(@TempDir dir: Path) {
        val home = Files.createDirectories(dir.resolve("codex"))
        val env = mapOf("HOME" to dir.toString(), "CODEX_HOME" to home.toString())
        val declined = offer(env, mutableListOf(), yes = false)
        declined.save(declined.ask(watched = true))
        assertNull(offer(env, mutableListOf(), yes = true).ask(watched = false))
        assertNull(offer(mapOf("HOME" to dir.resolve("nobody").toString()), mutableListOf(), yes = true).ask(true))
        assertEquals(emptyList<Path>(), Files.list(home).use { it.toList() })
    }

    private fun offer(env: Map<String, String>, questions: MutableList<String>, yes: Boolean): SetupCodexHooks {
        val frame = WizardFrame(StringBuilder(), ConfirmPrompt { question, _ -> yes.also { questions += question } })
        return SetupCodexHooks(frame, EnvReader { env[it] })
    }

    private fun commands(hooks: JsonObject, event: String): List<String> =
        (hooks[event] as? JsonArray ?: JsonArray(emptyList())).flatMap { group ->
            (group.jsonObject["hooks"] as JsonArray).map { JsonScalars.str(it.jsonObject, "command").orEmpty() }
        }

    private fun before(): String = """
        {
          "hooks": {
            "PreToolUse": [{"hooks": [{"type": "command", "command": "/usr/local/bin/hookd-client", "timeout": 600}]}],
            "SessionStart": [{"hooks": [{"type": "command", "command": ${quoted(OLD_ATTACH)}, "timeout": 30}]}],
            "UserPromptSubmit": [{"hooks": [{"type": "command", "command": ${quoted(OLD_ATTACH)}, "timeout": 30}]}],
            "Stop": [{"hooks": [{"type": "command", "command": "$OTHER", "timeout": 600}]}],
            "SessionEnd": [{"hooks": [{"type": "command", "command": ${quoted(OLD_DETACH)}, "timeout": 30}]}]
          }
        }
    """.trimIndent()

    private fun quoted(text: String): String = Json.encodeToString(text)
}
