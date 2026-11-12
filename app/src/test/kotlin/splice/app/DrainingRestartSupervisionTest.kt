package splice.app

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.console.DrainingRestartAdapter
import splice.app.console.INVOCATION_ID
import splice.core.util.EnvReader

class DrainingRestartSupervisionTest {

    @Test
    fun `the supervision probe answers from INVOCATION_ID and both ways`() {
        // The rig running this test was started by Gradle, not by a systemd unit, so the uninjected
        // read would pin whatever started the JVM and the supervised arm would be untestable. Both
        // arms are answered directly instead.
        assertTrue(
            DrainingRestartAdapter(EnvReader { if (it == INVOCATION_ID) "8c82ca38" else null })(),
            "a process systemd started is supervised — that is what INVOCATION_ID means",
        )
        assertTrue(
            !DrainingRestartAdapter(EnvReader { null })(),
            "a hand-started daemon is supervised by nothing, and the route must refuse it",
        )
        assertTrue(
            !DrainingRestartAdapter(EnvReader { if (it == INVOCATION_ID) "  " else null })(),
            "a blank value is not evidence of systemd: presence must be a real value, not an empty one",
        )
    }
}
