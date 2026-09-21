// NEW: fallbackEffort rungs-null is the no-vendor-ladder path (trimmed token rides).
package splice.dialect.anthropic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.util.LogSink
import java.util.concurrent.atomic.AtomicBoolean

class PassthroughEffortLadderTest {

    @Test
    fun `null rungs pass the trimmed config effort through`() {
        val ladder = PassthroughEffortLadder()
        val warned = AtomicBoolean(false)
        val log = LogSink { }
        assertEquals("custom", ladder.fallbackEffort("Custom", "tag", warned, log, rungs = null))
        assertEquals("max", ladder.fallbackEffort(null, "tag", warned, log, rungs = null))
        assertEquals(false, warned.get())
    }
}
