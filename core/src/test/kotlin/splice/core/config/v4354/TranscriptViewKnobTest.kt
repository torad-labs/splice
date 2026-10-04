// V4-354: one global console switch governs whether the daemon reads a client's local transcript.
// Default on for already-written, redacted conversation only; exact request capture remains opt-in.
// PATCH applies live without a restart and persists, so every browser sees the same off state.
package splice.core.config.v4354

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import java.nio.file.Path

class TranscriptViewKnobTest {
    @Test
    fun `the transcript view defaults on and a patch turns it off now and after restart`(@TempDir dir: Path) {
        val paths = StatePaths(baseOverride = dir.resolve("state"))
        val service = ConfigService(paths, envReader = { null })
        assertTrue(service.getConfig().transcriptView)

        val written = service.patch(mapOf("transcriptView" to false))
        assertEquals(false, written.applied["transcriptView"])
        assertFalse("transcriptView" in written.restartRequired, "the route must read the new value live")
        assertEquals(null, written.notPersisted)
        assertFalse(service.getConfig().transcriptView)

        val restarted = ConfigService(paths, envReader = { null })
        assertFalse(restarted.getConfig().transcriptView, "one browser's switch stays off for every later browser")
    }
}
