package splice.client.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.transcript.SessionHistoryRoot
import splice.sessions.transcript.SessionHistoryScan
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

private const val BOTH = "3f2a9c1e-0000-4000-8000-000000000001"
private const val HISTORY_ONLY = "3f2a9c1e-0000-4000-8000-000000000002"
private const val TRANSCRIPT_ONLY = "3f2a9c1e-0000-4000-8000-000000000003"

class TranscriptHistoryIndexTest {
    @TempDir lateinit var home: Path

    @Test
    fun `an older nonempty head copy keeps the session resumable beside a newer empty copy`() {
        val earlier = home.resolve(".claude-earlier")
        val later = home.resolve(".claude-later")
        val full = earlier.resolve("projects/-work-atlas/$BOTH.jsonl")
        Files.createDirectories(full.parent)
        Files.writeString(full, """{"type":"user","cwd":"/work/atlas"}""" + "\n")
        Files.setLastModifiedTime(full, FileTime.fromMillis(1_000))
        val empty = later.resolve("projects/-work-atlas/$BOTH.jsonl")
        Files.createDirectories(empty.parent)
        Files.createFile(empty)
        Files.setLastModifiedTime(empty, FileTime.fromMillis(2_000))
        val scan = TranscriptHistoryIndex().scan(
            listOf(SessionHistoryRoot("earlier", earlier), SessionHistoryRoot("later", later)),
        )
        assertEquals(1, scan.sessions.size)
        assertTrue(scan.sessions.single().resumable, "the launch can still adopt the older nonempty copy")
    }

    @Test
    fun `history prompts and custom titles redact credential shapes before truncation`() {
        val plain = Files.createDirectories(home.resolve(".claude"))
        Files.writeString(
            plain.resolve("history.jsonl"),
            """{"sessionId":"$HISTORY_ONLY","display":"api_key=abcdefghijklmnopqrstuvwxyz123456",""" +
                """"project":"/work/atlas","timestamp":1}""" + "\n",
        )
        val project = Files.createDirectories(plain.resolve("projects/-work-atlas"))
        Files.writeString(
            project.resolve("$BOTH.jsonl"),
            """{"type":"custom-title","customTitle":"Bearer abcdefghijklmnopqrstuvwxyz123456"}""" + "\n",
        )
        val sessions = TranscriptHistoryIndex().scan(listOf(SessionHistoryRoot(null, plain))).sessions
        assertEquals("api_key=[redacted]", sessions.single { it.sessionId == HISTORY_ONLY }.name)
        assertEquals("Bearer [redacted]", sessions.single { it.sessionId == BOTH }.name)
    }

    @Test
    fun `a projects source that cannot be listed is an error, not an empty tree`() {
        val plain = Files.createDirectories(home.resolve(".claude"))
        Files.writeString(plain.resolve("projects"), "not a directory")
        val scan = TranscriptHistoryIndex().scan(listOf(SessionHistoryRoot(null, plain)))
        assertTrue(scan.sessions.isEmpty())
        assertTrue(scan.errors.any { "projects" in it }, scan.errors.toString())
    }

    @Test
    fun `a title near the beginning remains searchable after a large later transcript`() {
        val plain = home.resolve(".claude")
        val project = Files.createDirectories(plain.resolve("projects/-work-atlas"))
        Files.writeString(
            project.resolve("$BOTH.jsonl"),
            """{"type":"custom-title","customTitle":"Atlas parser"}""" + "\n" +
                """{"type":"attachment","content":"${"x".repeat(70_000)}"}""" + "\n",
        )
        val entry = TranscriptHistoryIndex().scan(listOf(SessionHistoryRoot(null, plain))).sessions.single()
        assertEquals("Atlas parser", entry.name)
    }

    @Test
    fun `every history id and every primary transcript receives one explicit disposition`() {
        val plain = home.resolve(".claude")
        Files.createDirectories(plain)
        Files.writeString(
            plain.resolve("history.jsonl"),
            listOf(
                """{"sessionId":"$BOTH","display":"First prompt","project":"/work/atlas","timestamp":1000}""",
                """{"sessionId":"$BOTH","display":"Later prompt","project":"/work/atlas","timestamp":3000}""",
                """{"sessionId":"$HISTORY_ONLY","display":"No transcript yet","project":"/work/other","timestamp":2000}""",
                """{not json""",
                """{"display":"orphan without a session id"}""",
            ).joinToString("\n", postfix = "\n"),
        )
        val project = Files.createDirectories(plain.resolve("projects/-work-atlas"))
        Files.writeString(
            project.resolve("$BOTH.jsonl"),
            """{"type":"custom-title","customTitle":"Atlas parser","sessionId":"$BOTH"}""" + "\n" +
                """{"type":"user","cwd":"/work/atlas","sessionId":"$BOTH"}""" + "\n",
        )
        Files.createFile(project.resolve("$TRANSCRIPT_ONLY.jsonl"))
        Files.writeString(project.resolve("summary.log.jsonl"), "{}\n")
        val nested = Files.createDirectories(project.resolve("$BOTH/subagents"))
        Files.writeString(nested.resolve("agent-1.jsonl"), "{}\n")
        val outside = Files.createDirectories(home.resolve("outside-projects"))
        Files.createFile(outside.resolve("3f2a9c1e-0000-4000-8000-000000000004.jsonl"))
        Files.createSymbolicLink(plain.resolve("projects/-outside"), outside)
        val alias = Files.createDirectories(home.resolve(".claude-linked"))
        Files.createSymbolicLink(alias.resolve("projects"), plain.resolve("projects"))

        val scan = TranscriptHistoryIndex().scan(
            listOf(SessionHistoryRoot(null, plain), SessionHistoryRoot("linked", alias)),
        )
        assertEquals(setOf(BOTH, HISTORY_ONLY, TRANSCRIPT_ONLY), scan.sessions.map { it.sessionId }.toSet())
        assertEquals(3, scan.sessions.size, "repeated history rows and symlinked trees never duplicate a session")
        val both = scan.sessions.single { it.sessionId == BOTH }
        assertTrue(both.hasHistory && both.hasTranscript)
        assertTrue(both.resumable)
        assertEquals("Atlas parser", both.name, "the transcript's named title outranks its last prompt")
        assertEquals("/work/atlas", both.project)
        assertEquals(3000L, both.updatedAt, "the newest history entry wins even when its file is old")
        val historyOnly = scan.sessions.single { it.sessionId == HISTORY_ONLY }
        assertTrue(historyOnly.hasHistory)
        assertFalse(historyOnly.hasTranscript)
        assertEmptyTranscriptDisposition(scan)
        assertSourceExclusions(scan)
        assertTrue(scan.errors.isEmpty(), scan.errors.toString())
    }

    private fun assertSourceExclusions(scan: SessionHistoryScan) {
        assertTrue(scan.skipped.getOrDefault("malformed history row", 0) > 0)
        assertEquals(1, scan.skipped["history row without session id"])
        assertTrue(scan.skipped.getOrDefault("non-session jsonl", 0) > 0)
        assertTrue(scan.skipped.getOrDefault("nested artifacts", 0) > 0)
        assertEquals(1, scan.skipped["project path outside tree"])
    }

    private fun assertEmptyTranscriptDisposition(scan: SessionHistoryScan) {
        val empty = scan.sessions.single { it.sessionId == TRANSCRIPT_ONLY }
        assertFalse(empty.hasHistory)
        assertTrue(empty.hasTranscript, "an empty primary file still belongs to the source denominator")
        assertFalse(empty.resumable, "the launch cannot continue an empty conversation")
    }
}
