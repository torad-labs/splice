// NEW: V4-174 — the one addition the trace store made to ActivityDays: an owner-only mode whose
// directory is 0700 from creation and re-asserted on every append (a directory the operator
// recreated by hand is never left at the umask's default), plus `purge`, which deletes every day
// file whatever its age and names what went. The plain mode is pinned unchanged beside it.
package console.v4130

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.activity.ActivityDays
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

private const val DAY_MS = 86_400_000L
private const val DAY_ONE = 1_789_725_600_000L // 2026-09-18T10:00Z

class ActivityDaysOwnerOnlyTest {

    @TempDir
    lateinit var tmp: Path

    private fun posix(): Boolean = Files.getFileStore(tmp).supportsFileAttributeView("posix")

    private fun perms(dir: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))

    @Test
    fun `an owner-only store's directory is 0700 from the first append and re-asserted after a recreate`() {
        if (!posix()) return
        val dir = tmp.resolve("trace")
        val days = ActivityDays(dir, "kimi", retentionDays = 7, clock = WallClock { DAY_ONE }, ownerOnly = true)

        days.append("""{"n":1}""")
        assertTrue(AsyncFileIo.drain(), "the file lane drained")
        assertEquals("rwx------", perms(dir))
        assertEquals(listOf("""{"n":1}"""), Files.readAllLines(dir.resolve("kimi-2026-09-18.jsonl")))

        // The operator recreates the directory at the umask's default; the next append locks it again.
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        Files.createDirectories(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
        days.append("""{"n":2}""")
        assertTrue(AsyncFileIo.drain(), "the file lane drained")
        assertEquals("rwx------", perms(dir))
    }

    @Test
    fun `a plain store leaves the directory at the default the umask gives`() {
        if (!posix()) return
        val dir = tmp.resolve("activity")
        val days = ActivityDays(dir, "labels", retentionDays = 7, clock = WallClock { DAY_ONE })

        days.append("""{"n":1}""")
        assertTrue(AsyncFileIo.drain(), "the file lane drained")

        assertTrue(Files.getPosixFilePermissions(dir).contains(PosixFilePermission.OWNER_EXECUTE))
        if (!umaskIsStrict()) assertNotEquals("rwx------", perms(dir), "the plain mode did not lock the directory")
    }

    @Test
    fun `purge deletes every day file whatever its age, with the lock siblings, and names them`() {
        val dir = tmp.resolve("trace")
        var now = DAY_ONE
        val days = ActivityDays(dir, "kimi", retentionDays = 2, clock = WallClock { now }, ownerOnly = true)
        days.append("""{"d":1}""")
        now += DAY_MS
        days.append("""{"d":2}""")
        assertTrue(AsyncFileIo.drain(), "the file lane drained")
        assertEquals(2, days.lines().count())

        val gone = days.purge()

        assertEquals(
            listOf(dir.resolve("kimi-2026-09-18.jsonl"), dir.resolve("kimi-2026-09-19.jsonl")),
            gone,
        )
        assertEquals(0, days.lines().count())
        assertTrue(Files.list(dir).use { it.toList() }.isEmpty(), "the lock siblings went with the files")
        assertEquals(emptyList<Path>(), days.purge(), "a second purge has nothing to name")
    }

    /** A umask of 077 would make the plain directory 0700 too; the plain-mode cell must not fail there. */
    private fun umaskIsStrict(): Boolean {
        val probe = Files.createDirectory(tmp.resolve("umask-probe"))
        return perms(probe) == "rwx------"
    }
}
