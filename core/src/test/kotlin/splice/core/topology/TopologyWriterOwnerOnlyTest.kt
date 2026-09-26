// NEW: V4-275 — a console edit writes splice.toml and its backup owner-only (0600) from the instant
// each exists, and keeps only the newest backups. Before, both were written at the umask (0644 under
// the usual 022), so an extra_headers secret sat in files other accounts could read, and every
// console edit left one more backup that nothing deleted.
package splice.core.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelEntry
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** 2026-09-18T12:00:00Z. */
private const val NOW = 1_789_732_800_000L
private const val FILE = "[heads.ex]\nport = 8801 # the head\n"
private const val EDITED = "[heads.ex]\nport = 8802 # the head\n"

class TopologyWriterOwnerOnlyTest {

    @TempDir
    lateinit var tmp: Path

    private val file by lazy { tmp.resolve("splice.toml").also { Files.writeString(it, FILE) } }

    private fun topology(port: Int): Topology = Topology(
        providers = mapOf(
            "ex" to ProviderConfig(
                Dialect.OPENAI_CHAT,
                "https://api.example.com/v1",
                AuthConfig("api-key", env = "EX_KEY"),
                models = listOf(ModelEntry("m1", contextWindow = 128_000L)),
            ),
        ),
        heads = mapOf("ex" to HeadConfig("ex", port, "ex/", "m1")),
    )

    private fun writer(now: Long, path: Path = file): TopologyWriter {
        val table = mapOf(FILE to topology(8801), EDITED to topology(8802))
        return TopologyWriter(path, TopologyParse { text -> table.getValue(text) }, WallClock { now })
    }

    private fun mode(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    private fun backups(): List<String> = Files.list(tmp).use { paths ->
        paths.map { it.fileName.toString() }.filter { it.startsWith("splice.toml.bak-") }.sorted().toList()
    }

    @Test
    fun `a console edit writes splice toml and its backup owner-only, whatever the old file's mode - V4-275`() {
        if (!Files.getFileStore(tmp).supportsFileAttributeView("posix")) return
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))

        val result = writer(NOW).write(topology(8802))

        val backup = checkNotNull((result as TopologyWriteResult.Written).backup)
        assertEquals(EDITED, Files.readString(file))
        assertEquals("rw-------", mode(file), "splice.toml after the edit")
        assertEquals("rw-------", mode(backup), "the backup of the old bytes")
    }

    @Test
    fun `the newest ten backups are kept, and the oldest goes when a new one is taken - V4-275`() {
        repeat(12) { second ->
            writer(NOW + second * 1_000L).write(topology(if (second % 2 == 0) 8802 else 8801))
        }

        val stamps = backups().map { it.removePrefix("splice.toml.bak-").substringBefore('-') }
        assertEquals((2..11).map { "20260918T1200%02dZ".format(it) }, stamps, "the ten newest, by the second taken")
    }

    // V4-279: a dotfiles-managed splice.toml is a link. The rename landed ON the link and replaced it with a
    // regular file, so the dotfiles copy silently stopped getting edits. The edit now lands on the target,
    // and the backup, which carries the header secrets too, stays beside the link, out of the dotfiles repo.
    @Test
    fun `a console edit through a linked splice toml keeps the link and edits its target - V4-279`() {
        val target = Files.createDirectories(tmp.resolve("dotfiles")).resolve("splice.toml")
        Files.writeString(target, FILE)
        val link = Files.createDirectories(tmp.resolve("config")).resolve("splice.toml")
        Files.createSymbolicLink(link, target)

        val result = writer(NOW, link).write(topology(8802))

        val backup = checkNotNull((result as TopologyWriteResult.Written).backup)
        assertTrue(Files.isSymbolicLink(link), "splice.toml is still the operator's link")
        assertEquals(EDITED, Files.readString(target), "the edit landed in the link's target")
        assertEquals(tmp.resolve("config"), backup.parent, "the backup sits beside the link")
        val dotfiles = Files.list(target.parent).use { paths -> paths.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("splice.toml"), dotfiles, "no backup and no temp file in the dotfiles directory")
        if (Files.getFileStore(tmp).supportsFileAttributeView("posix")) assertEquals("rw-------", mode(target))
    }
}
