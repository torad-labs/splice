// NEW: Oct 10, 2026 — a history cut takes the transcript copies of sessions last used before it, aged by the live
// transcript, and only those.
package splice.client.resume

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.resume.originals.OriginalsHeld
import splice.client.resume.originals.TranscriptOriginals
import splice.core.config.StatePaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

internal class TranscriptOriginalAgeTest {
    @TempDir lateinit var tmp: Path

    private val paths get() = StatePaths(baseOverride = tmp.resolve("state"))
    private val row =
        """{"type":"assistant","message":{"model":"synthetic-old","content":[{"type":"text","text":"original"}]}}"""

    /** A session whose copy was kept by a real rewrite, and whose live transcript was last written at [usedMs]. */
    private fun session(id: String, usedMs: Long): Path {
        val live = tmp.resolve("project/$id.jsonl")
        Files.createDirectories(live.parent)
        Files.writeString(live, row + "\n")
        val nested = tmp.resolve("project/$id/subagents/agent.jsonl")
        Files.createDirectories(nested.parent)
        Files.writeString(nested, row + "\n")
        val rewrite = TranscriptModelRewrite(originals = TranscriptOriginals(paths))
        rewrite.rewrite(live, "synthetic-new", listOf("synthetic-new"))
        Files.setLastModifiedTime(live, FileTime.fromMillis(usedMs))
        return live
    }

    private fun copy(id: String): Path = paths.transcriptOriginalsDir.resolve("project/$id.jsonl")

    @Test
    fun `a cut counts then removes the copies of sessions last used before it, and keeps the live ones`() {
        session("aaaa", usedMs = 1_000)
        session("bbbb", usedMs = 9_000)
        val originals = TranscriptOriginals(paths)

        val held = originals.heldBefore(5_000)
        assertEquals(3, held.files, "the old session's copy, its source marker and its nested file, never the other's")
        assertTrue(held.bytes > 0)

        assertEquals(held, originals.deleteBefore(5_000), "it frees exactly what it counted")
        assertFalse(Files.exists(copy("aaaa")))
        assertFalse(Files.exists(paths.transcriptOriginalsDir.resolve("project/aaaa")))
        assertTrue(Files.exists(copy("bbbb")))
        assertEquals(OriginalsHeld(0, 0), originals.heldBefore(5_000))
    }

    @Test
    fun `a store that was never written holds nothing`() {
        assertEquals(OriginalsHeld(0, 0), TranscriptOriginals(paths).heldBefore(Long.MAX_VALUE))
        assertEquals(OriginalsHeld(0, 0), TranscriptOriginals(paths).deleteBefore(Long.MAX_VALUE))
    }
}
