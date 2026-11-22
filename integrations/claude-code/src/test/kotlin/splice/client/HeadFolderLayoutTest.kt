package splice.client

// head-catalog (gw-retire): what a launch lays in a head's own Claude folder is what retiring takes, so a folder a real
// materialize left reads as splice's in full, and only the person's own file and Claude Code's session data are not.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.login.TokenCaptureSpec
import splice.client.resume.ResumeHookTarget
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class HeadFolderLayoutTest {
    private val policy = ClaudePolicy(
        share = setOf("settings", "mcps", "agents", "commands", "skills", "hooks", "plugins", "CLAUDE.md", "sessions"),
        isolate = emptySet(),
    )

    @Test
    fun `everything a launch lays is splice's, and the person's file and the sessions are not`(@TempDir home: Path) {
        val global = home.resolve(".claude")
        Files.createDirectories(global.resolve("agents"))
        Files.createDirectories(global.resolve("sessions"))
        Files.createDirectories(global.resolve("commands"))
        global.resolve("commands/team.md").writeText("# team")
        global.resolve("CLAUDE.md").writeText("rules")
        global.resolve("settings.json").writeText("""{"theme":"dark"}""")
        home.resolve(".claude.json").writeText("""{"theme":"dark"}""")
        val folder = home.resolve(".claude-head")
        val signIn = MaterializeSignIn(
            loginCommand = "claude-head login",
            tokenCapture = TokenCaptureSpec("HEAD_API_KEY", "hk-[A-Za-z0-9]{20,}", "Head"),
        )
        ClaudeConfigMaterializer(home, resumeHook = ResumeHookTarget(PORT) { home.resolve("header") })
            .materialize(MaterializeSpec(folder, policy, "statusline", signIn))
        folder.resolve("my-notes.md").writeText("mine")
        Files.createDirectories(folder.resolve("projects/-work"))

        val contents = HeadFolderLayout.contents(folder, home)

        assertEquals(listOf(folder.resolve("my-notes.md")), contents.others)
        val laid = contents.laid.map { folder.relativize(it.path).toString() }.toSet()
        for (name in listOf("settings.json", "commands/login.md", "commands/team.md", "commands", "agents")) {
            assertTrue(name in laid, "$name is splice's, got $laid")
        }
        assertTrue(laid.none { it.startsWith("projects") || it.startsWith("sessions") }, "sessions stay: $laid")
        assertEquals(folder.resolve(".claude.json"), contents.state)
    }

    @Test
    fun `the person's own Claude folder is never a head's`(@TempDir home: Path) {
        val global = Files.createDirectories(home.resolve(".claude"))
        global.resolve("settings.json").writeText("{}")

        assertEquals(HeadFolderContents(emptyList(), null, emptyList()), HeadFolderLayout.contents(global, home))
    }

    private companion object {
        const val PORT = 3096
    }
}
