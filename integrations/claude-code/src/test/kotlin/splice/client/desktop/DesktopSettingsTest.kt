// NEW: desktop-door — what `splice desktop on` leaves in the person's own settings file, what `off` gives back, and
// what the terminal's `claude` lays over it meanwhile, read as the file Claude Code reads.
package splice.client.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DesktopSettingsTest {
    private val settings = DesktopSettings()
    private val door = "http://127.0.0.1:8100/desktop"
    private val write = DesktopWrite(
        door = door,
        key = "splice-desktop-k",
        window = 400_000,
        model = "claude-astra--astra-large",
        rows = listOf(DesktopRow("claude-astra--astra-large", "astra-large", "splice · claude-astra")),
    )

    private fun file(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `on writes the switch, the door, the key, the window and the picker, and leaves the person's own keys`() {
        val mine = file("""{"theme":"dark","model":"opus","env":{"FOO":"1"}}""")
        val on = settings.on(mine, write, null).file
        val env = on["env"]!!.jsonObject
        assertEquals("1", env[DESKTOP_USE_MANTLE]!!.jsonPrimitive.content)
        assertEquals(door, env[DESKTOP_MANTLE_URL]!!.jsonPrimitive.content)
        assertEquals("splice-desktop-k", env[DESKTOP_BEARER]!!.jsonPrimitive.content)
        assertEquals("400000", env[DESKTOP_WINDOW]!!.jsonPrimitive.content)
        assertEquals("0", env[DESKTOP_AUTO_MODE]!!.jsonPrimitive.content)
        assertEquals("claude-astra--astra-large", env[DESKTOP_MODEL]!!.jsonPrimitive.content)
        assertEquals("1", env["FOO"]!!.jsonPrimitive.content)
        assertEquals("dark", on["theme"]!!.jsonPrimitive.content)
        assertEquals("opus", on["model"]!!.jsonPrimitive.content)
        val picker = on[DESKTOP_PICKER]!!.jsonObject
        assertEquals(JsonPrimitive(true), picker["replaceBuiltInOptions"])
        val row = picker["options"]!!.jsonArray.single().jsonObject
        assertEquals("splice · claude-astra", row["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `off after on gives the person back the file they had`() {
        val mine = file("""{"theme":"dark","env":{"ANTHROPIC_MODEL":"sonnet"},"modelPicker":{"options":[]}}""")
        val on = settings.on(mine, write, null)
        assertEquals(mine, settings.off(on.file, on.replaced))
    }

    @Test
    fun `off after on removes a file that was not there before`() {
        val on = settings.on(null, write, null)
        assertNull(settings.off(on.file, on.replaced))
    }

    @Test
    fun `a second on keeps what the first replaced, so off still gives back the person's own`() {
        val mine = file("""{"env":{"ANTHROPIC_MODEL":"sonnet"}}""")
        val first = settings.on(mine, write, null)
        val second = settings.on(first.file, write.copy(model = "claude-astra--astra-small"), first.replaced)
        assertEquals(mine, settings.off(second.file, second.replaced))
    }

    @Test
    fun `a file pointed at another provider or another Mantle address is refused`() {
        assertNotNull(settings.refusal(file("""{"env":{"CLAUDE_CODE_USE_BEDROCK":"1"}}"""), door))
        assertNotNull(settings.refusal(file("""{"env":{"CLAUDE_CODE_USE_VERTEX":"true"}}"""), door))
        val elsewhere = """{"env":{"CLAUDE_CODE_USE_MANTLE":"1","ANTHROPIC_BEDROCK_MANTLE_BASE_URL":"https://x"}}"""
        assertNotNull(settings.refusal(file(elsewhere), door))
        assertNull(settings.refusal(file("""{"env":{"CLAUDE_CODE_USE_BEDROCK":"0"}}"""), door))
        assertNull(settings.refusal(settings.on(null, write, null).file, door))
    }

    @Test
    fun `the terminal's overlay blanks what on wrote, keeps the launch's own values and the person's picker`() {
        val mine = file("""{"env":{"ANTHROPIC_MODEL":"sonnet"},"modelPicker":{"options":[{"model":"opus"}]}}""")
        val on = settings.on(mine, write, null)
        val overlay = settings.cancel(on.replaced, mapOf(DESKTOP_WINDOW to "200000"))
        val env = overlay["env"]!!.jsonObject
        assertEquals("", env[DESKTOP_USE_MANTLE]!!.jsonPrimitive.content)
        assertEquals("", env[DESKTOP_BEARER]!!.jsonPrimitive.content)
        assertEquals("200000", env[DESKTOP_WINDOW]!!.jsonPrimitive.content)
        assertEquals("sonnet", env[DESKTOP_MODEL]!!.jsonPrimitive.content)
        val options = overlay[DESKTOP_PICKER]!!.jsonObject["options"]!!.jsonArray
        val rows = options.map { it.jsonObject["model"]!!.jsonPrimitive.content }
        assertEquals(listOf("opus"), rows)
        assertFalse(overlay.toString().contains("splice-desktop-k"))
    }

    @Test
    fun `with no picker of the person's own the overlay's picker has no rows`() {
        val on = settings.on(null, write, null)
        val picker = settings.cancel(on.replaced, emptyMap())[DESKTOP_PICKER]!!.jsonObject
        assertEquals(0, picker["options"]!!.jsonArray.size)
        assertEquals(JsonPrimitive(false), picker["replaceBuiltInOptions"])
    }

    @Test
    fun `a head with a folder of its own inherits none of the door, and none of a provider switch`() {
        val mine = file("""{"theme":"dark","env":{"FOO":"1"},"modelPicker":{"options":[{"model":"opus"}]}}""")
        val on = settings.on(mine, write.copy(key = "splice-desktop-abc"), null).file
        val picked = JsonObject(on + ("model" to JsonPrimitive("claude-astra--astra-large")))
        assertEquals(file("""{"theme":"dark","env":{"FOO":"1"}}"""), settings.inherited(picked))
        val bedrock = file("""{"env":{"CLAUDE_CODE_USE_BEDROCK":"1","FOO":"1"},"modelPicker":{"options":[]}}""")
        assertEquals(file("""{"env":{"FOO":"1"},"modelPicker":{"options":[]}}"""), settings.inherited(bedrock))
    }
}
