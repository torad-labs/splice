// NEW: Oct 11, 2026 — the user decides how much splice keeps: with the copy switch off a move to another model
// still happens, but no original is kept, and the switch is read at each move.
package splice.client.resume

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.resume.originals.CopyingOriginals
import splice.client.resume.originals.OriginalsHeld
import splice.client.resume.originals.TranscriptOriginals
import splice.core.config.StatePaths
import java.nio.file.Files
import java.nio.file.Path

internal class TranscriptCopiesSwitchTest {
    @TempDir lateinit var tmp: Path

    private val paths get() = StatePaths(baseOverride = tmp.resolve("state"))
    private val row =
        """{"type":"assistant","message":{"model":"synthetic-old","content":[{"type":"text","text":"original"}]}}"""

    private fun moved(id: String, copying: CopyingOriginals): Int {
        val live = tmp.resolve("project/$id.jsonl")
        Files.createDirectories(live.parent)
        Files.writeString(live, row + "\n")
        val rewrite = TranscriptModelRewrite(originals = TranscriptOriginals(paths, keeping = copying))
        return rewrite.rewrite(live, "synthetic-new", listOf("synthetic-new"))
    }

    @Test
    fun `with the switch off a session still moves and no copy is kept, and back on the next move keeps one`() {
        var on = false
        val copying = CopyingOriginals { on }

        assertEquals(1, moved("aaaa", copying), "the move is not refused")
        assertEquals(OriginalsHeld(0, 0), TranscriptOriginals(paths).heldBefore(Long.MAX_VALUE))

        on = true
        assertEquals(1, moved("bbbb", copying))
        val held = TranscriptOriginals(paths).heldBefore(Long.MAX_VALUE).files
        assertTrue(held > 0, "switched on, the next move keeps its original")
    }
}
