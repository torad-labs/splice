// NEW: V4-444 (heap) — locating a transcript costs file operations in proportion to the sessions asked about, never to
// the project directories on disk. The installed daemon listed every one of 5,393 project directories and statted a file
// in each, for every session row on every Sessions poll; a session with no transcript walked them all every time. These
// count every listing and stat through the locator's file seam over a real tree, and hold that a transcript Claude Code
// creates after a miss is still found, in an existing project directory or a new one.
package splice.client.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

private const val PROJECT_DIRS = 400

/** Every operation the locator asks of the disk, counted, and done on the real disk. */
private class CountingFiles : TranscriptFiles {
    var listings = 0
    var stats = 0

    fun reset() {
        listings = 0
        stats = 0
    }

    override fun directories(dir: Path): List<Path> {
        listings += 1
        val found = DiskTranscriptFiles.directories(dir)
        stats += found.size // each entry is statted to tell a directory from a file
        return found
    }

    override fun isFile(path: Path): Boolean {
        stats += 1
        return DiskTranscriptFiles.isFile(path)
    }

    override fun modified(path: Path): Long? {
        stats += 1
        return DiskTranscriptFiles.modified(path)
    }

    override fun realPath(dir: Path): Path {
        stats += 1
        return DiskTranscriptFiles.realPath(dir)
    }
}

class TranscriptLocatorTest {

    @TempDir
    lateinit var home: Path

    private val files = CountingFiles()
    private val locator = TranscriptLocator(files)

    /** The roots a session row asks with: a head's own config tree, then the vanilla one. */
    private fun roots(): List<Path> = listOf(home.resolve(".claude-head"), home.resolve(".claude"))

    private fun projects(): Path = home.resolve(".claude").resolve("projects")

    /** Claude Code's project directory name for [cwd]: every character that is not a letter or digit as '-'. */
    private fun slug(cwd: String): String = cwd.replace(Regex("[^A-Za-z0-9]"), "-")

    private fun transcript(cwd: String, id: String): Path =
        Files.createDirectories(projects().resolve(slug(cwd))).resolve("$id.jsonl")
            .also { Files.writeString(it, "{}\n") }

    /** A projects tree of [PROJECT_DIRS] directories, eight sessions with a transcript and one without. */
    private fun tree(): Map<String, String> {
        repeat(PROJECT_DIRS) { Files.createDirectories(projects().resolve("-home-op-project-$it")) }
        Files.createDirectories(home.resolve(".claude-head").resolve("projects"))
        val sessions = (1..8).associate { "session-$it" to "/home/op/work/repo-$it" }
        sessions.forEach { (id, cwd) -> transcript(cwd, id) }
        return sessions + ("session-none" to "/home/op/work/telegram")
    }

    private fun poll(sessions: Map<String, String>): Map<String, Path?> =
        sessions.mapValues { (id, cwd) -> locator.locate(roots(), id, cwd) }

    @Test
    fun `a sessions poll costs stats in proportion to its rows, and lists no directory once each session is known`() {
        val sessions = tree()
        val first = poll(sessions)
        assertEquals(8, first.values.count { it != null }, "the eight transcripts are found")
        files.reset()
        val second = poll(sessions)
        assertEquals(first, second, "the same files")
        val counted = "listings ${files.listings}, stats ${files.stats} " +
            "for ${sessions.size} rows over $PROJECT_DIRS dirs"
        assertEquals(0, files.listings, counted)
        assertTrue(files.stats <= sessions.size * 6, counted)
    }

    @Test
    fun `a session's own project directory is found without a walk, even the first time`() {
        val sessions = tree()
        files.reset()
        val cwd = "/home/op/work/repo-3"
        val own = projects().resolve(slug(cwd)).resolve("session-3.jsonl")
        assertEquals(own, locator.locate(roots(), "session-3", cwd))
        assertEquals(0, files.listings, "stats ${files.stats}")
    }

    @Test
    fun `a transcript created after a miss is found, in an existing project directory or a new one`() {
        tree()
        val cwd = "/home/op/work/repo-1"
        assertNull(locator.locate(roots(), "session-late", cwd), "no transcript yet")
        assertNull(locator.locate(roots(), "session-late", cwd), "still none")
        val late = transcript(cwd, "session-late")
        assertEquals(late, locator.locate(roots(), "session-late", cwd), "created in the session's existing dir")

        val fresh = "/home/op/new/place"
        assertNull(locator.locate(roots(), "session-new", fresh))
        val created = transcript(fresh, "session-new")
        assertEquals(created, locator.locate(roots(), "session-new", fresh), "created in a dir that did not exist")
    }

    @Test
    fun `a remembered miss is not walked again until a projects tree or the session's own directory moves`() {
        val sessions = tree()
        poll(sessions)
        files.reset()
        assertNull(locator.locate(roots(), "session-none", sessions.getValue("session-none")))
        assertEquals(0, files.listings, "stats ${files.stats}")
    }

    @Test
    fun `a transcript known from one lookup is checked without a listing by a lookup that names no directory`() {
        val sessions = tree()
        poll(sessions)
        files.reset()
        val known = projects().resolve(slug("/home/op/work/repo-2")).resolve("session-2.jsonl")
        assertEquals(known, locator.locate(roots(), "session-2"))
        assertEquals(0, files.listings)
        assertEquals(3, files.stats, "the file, and the head's own tree ahead of it and the session's directory there")
    }

    @Test
    fun `a copy that appears in a tree ahead of a remembered one wins, as the walk would choose it`() {
        val sessions = tree()
        val cwd = sessions.getValue("session-5")
        val vanilla = locator.locate(roots(), "session-5", cwd)
        val headTree = home.resolve(".claude-head").resolve("projects").resolve(slug(cwd))
        val own = Files.createDirectories(headTree).resolve("session-5.jsonl").also { Files.writeString(it, "{}\n") }
        assertEquals(projects().resolve(slug(cwd)).resolve("session-5.jsonl"), vanilla)
        assertEquals(own, locator.locate(roots(), "session-5", cwd), "the head's own copy is ahead of the vanilla one")
        assertEquals(own, locator.locate(roots(), "session-5"), "and a lookup naming no directory agrees")
    }

    @Test
    fun `a transcript a lookup naming no directory found behind another tree is found again, so a copy ahead wins`() {
        val sessions = tree()
        val cwd = sessions.getValue("session-6")
        val ahead = Files.createDirectories(home.resolve(".claude-head").resolve("projects").resolve(slug(cwd)))
        assertEquals(projects().resolve(slug(cwd)).resolve("session-6.jsonl"), locator.locate(roots(), "session-6"))
        val own = ahead.resolve("session-6.jsonl").also { Files.writeString(it, "{}\n") }
        assertEquals(own, locator.locate(roots(), "session-6"), "a copy in a project directory that already existed")
    }

    @Test
    fun `a remembered transcript that is gone is forgotten and looked for again`() {
        val sessions = tree()
        val found = poll(sessions).getValue("session-4")!!
        Files.delete(found)
        assertNull(locator.locate(roots(), "session-4", sessions.getValue("session-4")))
    }
}
