// Claude Desktop's Code tab as a front of the door (0.6.4): it keeps no model of its own, and a session starts on the
// model `claude` starts on. What a person sees of that: a Desktop turned on by an older splice, which saved a default
// model, is still on at the window it was told, and `splice desktop on --model` is refused, with nothing changed, in
// words that say where the model is named.
package splice.app.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.login.DoorLogin
import splice.core.config.FileHeldKey
import java.nio.file.Files
import java.nio.file.Path

class DesktopFollowsClaudeTest {
    private fun lane(dir: Path) = ClaudeLane(
        ClaudeLaneRecords(dir.resolve("claude-lane.json")) {},
        FileHeldKey(dir.resolve("terminal-key"), "splice-terminal-", "claude", "none", log = {}),
        DesktopHeads { emptyList() },
        "http://127.0.0.1:3096/door",
        ClaudeHeadHere { false },
        ClaudeLogin(DoorLogin(dir.resolve("claude"), dir.resolve("login"))) {},
    )

    @Test
    fun `a Desktop an older splice turned on with a default model is still on at its window`(@TempDir dir: Path) {
        val file = dir.resolve("desktop.json")
        Files.writeString(file, """{"on":true,"default_model":"gpt-6-astra","window":400000}""")
        val record = DesktopRecords(file) {}.get()
        assertTrue(record.on)
        assertEquals(400_000L, record.window)
    }

    @Test
    fun `on with a model is refused, saying where the model a session starts on is named`(@TempDir dir: Path) {
        val records = DesktopRecords(dir.resolve("desktop.json")) {}
        val settings = DesktopSettingsFile(dir.resolve("settings.json"), dir.resolve("no-status"))
        val key = DesktopKey(dir.resolve("desktop-key")) {}
        val switch = DesktopSwitch(records, key, settings, lane(dir), { false }, {})
        val refused = runBlocking { switch.on("gpt-6-astra") } as DesktopAct.Refused
        assertEquals(400, refused.status)
        assertTrue(refused.why.contains("splice claude door --model"), refused.why)
        assertTrue(refused.why.contains("nothing was changed"), refused.why)
        assertFalse(records.get().on)
    }
}
