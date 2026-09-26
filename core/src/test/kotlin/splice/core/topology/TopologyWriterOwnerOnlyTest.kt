// NEW: V4-275 — a console edit writes splice.toml and its backup owner-only (0600) from the instant
// each exists, and keeps only the newest backups. Before, both were written at the umask (0644 under
// the usual 022), so an extra_headers secret sat in files other accounts could read, and every
// console edit left one more backup that nothing deleted. V4-284: the backups are splice's own, in
// its own directory, and only those; the one a write reports is never the one the cap deletes.
package splice.core.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelEntry
import splice.core.util.DirectoryListing
import splice.core.util.FilesListing
import splice.core.util.WallClock
import java.io.IOException
import java.io.UncheckedIOException
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

    /** splice's own backups directory (StatePaths.configBackupsDir in production). */
    private val backupDir: Path by lazy { tmp.resolve("backups") }

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

    private fun writer(now: Long, path: Path = file, listing: DirectoryListing = FilesListing): TopologyWriter {
        val table = mapOf(FILE to topology(8801), EDITED to topology(8802))
        return TopologyWriter(path, backupDir, TopologyParse(table::getValue), WallClock { now }, listing = listing)
    }

    private fun mode(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    private fun names(dir: Path): List<String> = Files.list(dir).use { paths ->
        paths.map { it.fileName.toString() }.sorted().toList()
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
        assertEquals("rwx------", mode(backup.parent), "splice's own backups directory")
    }

    @Test
    fun `the newest ten backups are kept, and the oldest goes when a new one is taken - V4-275`() {
        repeat(12) { second ->
            writer(NOW + second * 1_000L).write(topology(if (second % 2 == 0) 8802 else 8801))
        }

        val stamps = names(backupDir).map { it.removePrefix("splice.toml.bak-").substringBefore('-') }
        assertEquals((2..11).map { "20260918T1200%02dZ".format(it) }, stamps, "the ten newest, by the second taken")
    }

    // V4-279: a dotfiles-managed splice.toml is a link. The rename landed ON the link and replaced it with a
    // regular file, so the dotfiles copy silently stopped getting edits. The edit now lands on the target,
    // and the backup, which carries the header secrets too, goes to splice's own directory (V4-284).
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
        assertEquals(backupDir, backup.parent, "the backup is in splice's own directory")
        val dotfiles = Files.list(target.parent).use { paths -> paths.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("splice.toml"), dotfiles, "no backup and no temp file in the dotfiles directory")
        if (Files.getFileStore(tmp).supportsFileAttributeView("posix")) assertEquals("rw-------", mode(target))
    }

    // V4-284 (1): the cap deleted by name prefix, so an operator's `splice.toml.bak-2026-09-01` went at the
    // eleventh edit. Only the writer's own name shape is capped, wherever the copy sits.
    @Test
    fun `an operator's own copy survives any number of console edits - V4-284 (1)`() {
        val beside = tmp.resolve("splice.toml.bak-2026-09-01")
        Files.writeString(beside, FILE)
        val inside = Files.createDirectories(backupDir).resolve("splice.toml.bak-2026-09-01")
        Files.writeString(inside, FILE)

        repeat(12) { second -> writer(NOW + second * 1_000L).write(topology(if (second % 2 == 0) 8802 else 8801)) }

        assertTrue(Files.exists(beside), "the operator's own copy is theirs, and splice never deletes it")
        assertTrue(Files.exists(inside), "a name outside the writer's shape is not splice's, wherever it is")
        assertEquals(11, names(backupDir).size, "ten of splice's own, and the operator's")
    }

    @Test
    fun `the backup a write reports still exists after the write, whatever the clock says - V4-284 (2)`() {
        Files.createDirectories(backupDir)
        repeat(10) { i ->
            Files.writeString(backupDir.resolve("splice.toml.bak-20260919T0000%02dZ-%012x".format(i, i)), FILE)
        }

        val result = writer(NOW).write(topology(8802))

        val backup = checkNotNull((result as TopologyWriteResult.Written).backup)
        assertTrue(Files.exists(backup), "the write answered with $backup")
    }

    @Test
    fun `a config directory linked into a dotfiles repo gets no backup in that repo - V4-284 (3)`() {
        val repo = Files.createDirectories(tmp.resolve("dotfiles/splice"))
        Files.writeString(repo.resolve("splice.toml"), FILE)
        val config = Files.createSymbolicLink(tmp.resolve("config"), repo)

        val _ = writer(NOW, config.resolve("splice.toml")).write(topology(8802))

        assertEquals(EDITED, Files.readString(repo.resolve("splice.toml")))
        assertEquals(listOf("splice.toml"), names(repo), "the backup holds the header secrets: never in the repo")
    }

    @Test
    fun `a backup listing that fails partway never turns a landed write into a failure - V4-284 (4)`() {
        val failing = DirectoryListing { throw UncheckedIOException(IOException("an entry vanished mid-read")) }

        val result = writer(NOW, listing = failing).write(topology(8802))

        assertTrue(result is TopologyWriteResult.Written, "$result")
        assertEquals(EDITED, Files.readString(file))
    }

    @Test
    fun `a write whose link target cannot be written leaves no backup behind - V4-284 (7)`() {
        if (!Files.getFileStore(tmp).supportsFileAttributeView("posix")) return
        val readOnly = Files.createDirectories(tmp.resolve("readonly"))
        val target = readOnly.resolve("splice.toml")
        Files.writeString(target, FILE)
        val config = Files.createDirectories(tmp.resolve("config"))
        val link = Files.createSymbolicLink(config.resolve("splice.toml"), target)
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-x------"))
        val failed = try {
            (0 until 12).count { second ->
                runCatching { writer(NOW + second * 1_000L, link).write(topology(8802)) }.isFailure
            }
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwx------"))
        }

        assertEquals(12, failed, "the target could not be written")
        assertEquals(emptyList<String>(), names(backupDir), "a failed write leaves no backup")
        assertEquals(listOf("splice.toml"), names(tmp.resolve("config")), "and none beside the link")
    }
}
