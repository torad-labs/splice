package splice.app.head

// head-catalog (gw-retire): retiring a head leaves its folder without splice's entries and its backup holding them,
// restoring puts the folder back byte for byte, and a file the person changed since is named and never written over.
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.LaidKind
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class HeadBackupTest {
    @Test
    fun `retiring moves splice's entries out and restoring puts them back exactly`(@TempDir root: Path) {
        val folder = seeded(root)
        val before = snapshot(folder)
        val backup = HeadBackup(root.resolve("backups"))

        val done = backup.retire("astra", "claude-astra", plan(folder), lane = null)

        assertFalse(Files.exists(folder.resolve("settings.json")))
        assertFalse(Files.exists(folder.resolve("commands"), NOFOLLOW_LINKS))
        val state = Json.parseToJsonElement(folder.resolve(".claude.json").readText())
        assertEquals("""{"theme":"dark"}""", state.toString())
        assertEquals("mine", folder.resolve("my-notes.md").readText())
        assertEquals(emptyList<String>(), done.stuck)

        val manifest = requireNotNull(backup.manifest(done.backup))
        assertEquals(emptyList<String>(), backup.conflicts(manifest))
        backup.restore(done.backup, manifest)

        assertEquals(before, snapshot(folder))
        assertTrue(Files.exists(done.backup.resolve("manifest.json")), "the backup stays")
    }

    @Test
    fun `a file changed since retiring is named, and restore writes over none of it`(@TempDir root: Path) {
        val folder = seeded(root)
        val backup = HeadBackup(root.resolve("backups"))
        val done = backup.retire("astra", "claude-astra", plan(folder), lane = null)
        folder.resolve("settings.json").writeText("""{"mine":true}""")

        val manifest = requireNotNull(backup.manifest(done.backup))

        assertEquals(listOf(folder.resolve("settings.json").toString()), backup.conflicts(manifest))
        assertEquals("""{"mine":true}""", folder.resolve("settings.json").readText())
    }

    private fun seeded(root: Path): Path {
        val folder = Files.createDirectories(root.resolve(".claude-astra"))
        val global = Files.createDirectories(root.resolve(".claude/commands"))
        folder.resolve("settings.json").writeText("""{"availableModels":["a"],"env":{"X":"1"}}""")
        folder.resolve(".claude.json").writeText("""{"theme":"dark","additionalModelOptionsCache":[]}""")
        folder.resolve("my-notes.md").writeText("mine")
        Files.createDirectories(folder.resolve("commands"))
        folder.resolve("commands/login.md").writeText("login")
        Files.createSymbolicLink(folder.resolve("commands/team.md"), global.resolve("team.md"))
        return folder
    }

    private fun plan(folder: Path) = RetirePlan(
        folder,
        link = null,
        laid = listOf(
            folder.resolve("settings.json") to LaidKind.FILE,
            folder.resolve("commands/login.md") to LaidKind.FILE,
            folder.resolve("commands/team.md") to LaidKind.LINK,
            folder.resolve("commands") to LaidKind.DIR,
        ),
        state = folder.resolve(".claude.json"),
    )

    /** Every entry under [dir]: a link by its target, a file by its text, a dir as such. */
    private fun snapshot(dir: Path): Map<String, String> = Files.walk(dir).use { walk ->
        walk.filter { it != dir }.toList().associate { path ->
            dir.relativize(path).toString() to when {
                Files.isSymbolicLink(path) -> "link ${Files.readSymbolicLink(path)}"
                Files.isDirectory(path) -> "dir"
                else -> "file ${path.readText()}"
            }
        }
    }
}
