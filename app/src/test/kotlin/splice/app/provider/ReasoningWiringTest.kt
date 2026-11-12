// A head's reasoning settings follow the operator's PATCH for display, replay and summary, and keep the effort the
// head started with: effort is part of the prompt-cache key, so it stays restart-only.
package splice.app.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.turn.ReasoningDisplay
import java.nio.file.Path

class ReasoningWiringTest {

    @Test
    fun `a patch moves display, replay and summary on the next reading and leaves effort where it started`(
        @TempDir tmp: Path,
    ) {
        val service = ConfigService(StatePaths(baseOverride = tmp.resolve("state")))
        service.patch(mapOf("effort" to "high", "showReasoning" to "off", "replayReasoning" to "false"))
        val settings = ReasoningWiring.settingsOf(service.getConfig("h"))
        assertEquals(ReasoningDisplay.OFF, settings.now().display)

        service.patch(
            mapOf(
                "effort" to "low",
                "showReasoning" to "thinking",
                "replayReasoning" to "true",
                "summary" to "concise",
            ),
        )

        val now = settings.now()
        assertEquals(ReasoningDisplay.THINKING, now.display)
        assertEquals(true, now.replay)
        assertEquals("concise", now.summary)
        assertEquals("high", now.effort, "effort is not live")
    }
}
