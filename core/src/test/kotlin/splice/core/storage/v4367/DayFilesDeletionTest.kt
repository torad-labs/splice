package splice.core.storage.v4367

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.storage.ActivityDays
import splice.core.storage.DayFileStores
import splice.core.storage.DayFiles
import splice.core.storage.DayInventory
import splice.core.storage.DayPurge
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.LocalDate

class DayFilesDeletionTest {
    @Test
    fun `purge removes orphaned day siblings but not another store or a symlink target`(@TempDir root: Path) {
        val dir = Files.createDirectory(root.resolve("activity"))
        val outside = root.resolve("outside.jsonl")
        Files.writeString(outside, "outside bytes")
        val rolled = dir.resolve("edges-2026-09-27.jsonl.1")
        val orphanLock = dir.resolve("edges-2026-09-26.jsonl.lock")
        val linked = dir.resolve("edges-2026-09-28.jsonl")
        val other = dir.resolve("activity-2026-09-27.jsonl")
        val planted = dir.resolve("leave-me.txt")
        Files.writeString(rolled, """{"id":"older"}""" + "\n")
        Files.writeString(orphanLock, "")
        Files.createSymbolicLink(linked, outside)
        Files.writeString(other, "other store")
        Files.writeString(planted, "unrelated")

        val result = DayFiles(dir, "edges").purge()

        assertEquals(3, (result as DayPurge.Listed).deleted.size)
        assertTrue(result.failed.isEmpty())
        assertFalse(Files.exists(rolled, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(orphanLock, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(linked, LinkOption.NOFOLLOW_LINKS))
        assertEquals("outside bytes", Files.readString(outside))
        assertEquals("other store", Files.readString(other))
        assertEquals("unrelated", Files.readString(planted))
    }

    @Test
    fun `inventory counts empty and rolled-only days without following a symlink`(@TempDir root: Path) {
        val dir = Files.createDirectory(root.resolve("activity"))
        Files.writeString(dir.resolve("edges-2026-09-26.jsonl"), "")
        val rolled = """{"id":"older"}""" + "\n"
        Files.writeString(dir.resolve("edges-2026-09-27.jsonl.1"), rolled)
        val outside = root.resolve("private.jsonl")
        Files.writeString(outside, "outside bytes never counted")
        Files.createSymbolicLink(dir.resolve("edges-2026-09-28.jsonl"), outside)
        Files.writeString(dir.resolve("edges-2026-09-25.jsonl.lock"), "")

        assertEquals(
            DayInventory(
                days = 2,
                rows = 1L,
                bytes = rolled.toByteArray().size.toLong(),
                oldest = LocalDate.parse("2026-09-26"),
                agesOut = LocalDate.parse("2026-10-04"),
            ),
            DayFiles(dir, "edges").inventory(retentionDays = 7),
        )
    }

    @Test
    fun `a directory disguised as a day file is refused and never deleted`(@TempDir root: Path) {
        val dir = Files.createDirectory(root.resolve("activity"))
        val disguised = Files.createDirectory(dir.resolve("edges-2026-09-27.jsonl"))
        val child = Files.writeString(disguised.resolve("keep.txt"), "operator data")
        assertThrows(IOException::class.java) { DayFiles(dir, "edges").inventory(retentionDays = 7) }
        assertTrue(DayFiles(dir, "edges").purge() is DayPurge.Unlisted)
        assertEquals("operator data", Files.readString(child))
    }

    @Test
    fun `store census includes days whose only remaining file is a sibling`(@TempDir root: Path) {
        val dir = Files.createDirectory(root.resolve("activity"))
        Files.writeString(dir.resolve("edges-2026-09-27.jsonl.1"), """{"id":"older"}""")
        Files.writeString(dir.resolve("activity-2026-09-26.jsonl.lock"), "")
        assertEquals(setOf("edges", "activity"), DayFileStores(dir).prefixes())
    }

    @Test
    fun `private trace delete leaves its marker and directory owner-only`(@TempDir root: Path) {
        val dir = root.resolve("trace")
        val at = Instant.parse("2026-09-27T10:00:00Z").toEpochMilli()
        val days = ActivityDays(dir, "local", retentionDays = 7, clock = WallClock { at }, ownerOnly = true)
        days.append("""{"id":"private"}""")
        val files = DayFiles(dir, "local", ownerOnly = true)
        assertEquals(1L, files.deleteKept(retentionDays = 7).rows)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
        assertEquals(
            "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("local.deleted"))),
        )
    }

    @Test
    fun `a delete marker lasts until a new row reaches the file lane`(@TempDir root: Path) {
        val dir = Files.createDirectory(root.resolve("activity"))
        val at = Instant.parse("2026-09-27T10:00:00Z").toEpochMilli()
        val days = ActivityDays(dir, "edges", retentionDays = 7, clock = WallClock { at })
        val files = DayFiles(dir, "edges")
        days.append("""{"id":"old"}""")
        val removed = files.deleteKept(retentionDays = 7)
        assertEquals(1, removed.days)
        assertEquals(1L, removed.rows)
        assertTrue(files.deleted())
        assertEquals(0L, files.inventory(retentionDays = 7).rows)

        days.append("""{"id":"new"}""")
        assertTrue(AsyncFileIo.drain(), "the new row reached disk")
        assertFalse(files.deleted())
        assertEquals(1L, files.inventory(retentionDays = 7).rows)
    }
}
