// NEW: desktop-door: the person's own settings file once an older splice's door is taken out of it, what in it would
// send the Code tab past the door, and what a head with a folder of its own inherits, read as the file Claude Code
// reads. The Desktop values come from Desktop's own store, and the person sets them there.
//
// Changed in 0.6.1: `splice desktop on` no longer writes the file, so the tests of what it wrote there (its env, its
// picker, a second `on`) and of the terminal's overlay over it are gone; the files below are written as 0.6.0's `on`
// left them.
package splice.client.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DesktopSettingsTest {
    private val settings = DesktopSettings()
    private val selectors = DoorSelectors { it.startsWith("claude-astra--") }

    private fun file(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    // What 0.6.0's `on` wrote into a file whose env held FOO and whose picker listed opus.
    private val doorEnv = """"CLAUDE_CODE_USE_MANTLE":"1",
        "ANTHROPIC_BEDROCK_MANTLE_BASE_URL":"http://127.0.0.1:8100/desktop",
        "AWS_BEARER_TOKEN_BEDROCK":"splice-desktop-abc","CLAUDE_CODE_MAX_CONTEXT_TOKENS":"400000",
        "CLAUDE_CODE_AUTO_MODE_SERVER":"0","ANTHROPIC_MODEL":"claude-astra--astra-large""""
    private val doorPicker =
        """"modelPicker":{"replaceBuiltInOptions":true,"options":[{"model":"claude-astra--astra-large"}]}"""
    private val replaced = file(
        """{"file_existed":true,"env_existed":true,"env":{"CLAUDE_CODE_USE_MANTLE":null,
        "ANTHROPIC_BEDROCK_MANTLE_BASE_URL":null,"AWS_BEARER_TOKEN_BEDROCK":null,
        "CLAUDE_CODE_MAX_CONTEXT_TOKENS":null,"CLAUDE_CODE_AUTO_MODE_SERVER":null,"ANTHROPIC_MODEL":"sonnet"},
        "modelPicker":{"options":[{"model":"opus"}]}}""",
    )

    @Test
    fun `taking an older door out gives the person back the file they had`() {
        val left = file("""{"theme":"dark","env":{"FOO":"1",$doorEnv},$doorPicker}""")
        val mine = file(
            """{"theme":"dark","env":{"FOO":"1","ANTHROPIC_MODEL":"sonnet"},
            "modelPicker":{"options":[{"model":"opus"}]}}""",
        )
        assertEquals(mine, settings.withoutDoor(left, replaced, selectors))
    }

    @Test
    fun `a model the Code tab picked from the door is taken out with it`() {
        val left = file("""{"model":"claude-astra--astra-large","env":{$doorEnv},$doorPicker}""")
        val out = settings.withoutDoor(left, replaced, selectors)!!
        assertNull(out["model"])
    }

    @Test
    fun `a door selector an older off left in model is taken out, and nothing else changes`() {
        val left = file("""{"theme":"dark","model":"claude-astra--astra-large","env":{"FOO":"1"}}""")
        assertEquals(file("""{"theme":"dark","env":{"FOO":"1"}}"""), settings.withoutDoor(left, null, selectors))
        val mine = file("""{"theme":"dark","model":"opus","env":{"FOO":"1"}}""")
        assertEquals(mine, settings.withoutDoor(mine, null, selectors))
    }

    @Test
    fun `a file the older door made is removed with it`() {
        val made = file(
            """{"file_existed":false,"env_existed":false,"env":{"ANTHROPIC_MODEL":null},"modelPicker":null}""",
        )
        assertNull(settings.withoutDoor(file("""{"env":{$doorEnv},$doorPicker}"""), made, selectors))
    }

    @Test
    fun `an older door is found at any address, and Mantle with no desktop key is not one`() {
        val moved = doorEnv.replace("8100", "9200")
        assertEquals(true, settings.holdsDoor(file("""{"env":{$moved}}""")))
        val mantle = """{"env":{"CLAUDE_CODE_USE_MANTLE":"1","AWS_BEARER_TOKEN_BEDROCK":"theirs"}}"""
        assertEquals(false, settings.holdsDoor(file(mantle)))
    }

    @Test
    fun `a provider switch of the person's own would send the Code tab past the door`() {
        assertNotNull(settings.refusal(file("""{"env":{"CLAUDE_CODE_USE_BEDROCK":"1"}}""")))
        assertNotNull(settings.refusal(file("""{"env":{"CLAUDE_CODE_USE_VERTEX":"true"}}""")))
        val elsewhere = """{"env":{"CLAUDE_CODE_USE_MANTLE":"1","ANTHROPIC_BEDROCK_MANTLE_BASE_URL":"https://x"}}"""
        assertNotNull(settings.refusal(file(elsewhere)))
        assertNull(settings.refusal(file("""{"env":{"CLAUDE_CODE_USE_BEDROCK":"0"}}""")))
        assertNull(settings.refusal(file("""{"env":{$doorEnv}}""")))
    }

    @Test
    fun `a head with a folder of its own inherits none of an older door, and none of a provider switch`() {
        val left = file(
            """{"theme":"dark","model":"claude-astra--astra-large","env":{"FOO":"1",$doorEnv},$doorPicker}""",
        )
        assertEquals(file("""{"theme":"dark","env":{"FOO":"1"}}"""), settings.inherited(left))
        val bedrock = file("""{"env":{"CLAUDE_CODE_USE_BEDROCK":"1","FOO":"1"},"modelPicker":{"options":[]}}""")
        assertEquals(file("""{"env":{"FOO":"1"},"modelPicker":{"options":[]}}"""), settings.inherited(bedrock))
    }

    @Test
    fun `Desktop is given Mantle, the door, the key, the window and auto mode off, and names them to remove`() {
        val environment = DesktopEnvironment()
        val env = environment.of("http://127.0.0.1:8100/desktop", "splice-desktop-k", 400_000)
        assertEquals(
            listOf(
                "CLAUDE_CODE_USE_MANTLE" to "1",
                "ANTHROPIC_BEDROCK_MANTLE_BASE_URL" to "http://127.0.0.1:8100/desktop",
                "AWS_BEARER_TOKEN_BEDROCK" to "splice-desktop-k",
                "CLAUDE_CODE_MAX_CONTEXT_TOKENS" to "400000",
                "CLAUDE_CODE_AUTO_MODE_SERVER" to "0",
            ),
            env,
        )
        assertEquals(env.map { it.first }, environment.names)
    }
}
