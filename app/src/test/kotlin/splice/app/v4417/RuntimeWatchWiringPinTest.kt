// NEW: V4-417 — the wiring pin for the runtime-reach readings. They reach /health and the heads route only
// through the `probes` the daemon passes ControlPlane.start, and the signals ControlPlane builds from them, so
// nothing in the compiler connects the daemon's watch to those readers. Delete a line and everything still
// builds, and every local head reads OK beside a silent runtime again, which is the defect. The assertions are
// on the SOURCE: no runtime observation tells "the daemon passed the probes" from "something passed a stub".
package splice.app.v4417

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val DAEMON = "app/src/main/kotlin/splice/app/Daemon.kt"

class RuntimeWatchWiringPinTest {

    private data class Pin(val file: String, val line: String, val harm: String)

    @Test
    fun `the daemon starts the watch and hands its readings to the control plane, which the health body reads`() {
        listOf(
            Pin(
                DAEMON,
                "headProbes.startRuntimeWatch(topology, controlPlane.probeScope, log)",
                "nothing would ever probe a runtime",
            ),
            Pin(
                DAEMON,
                "probes = headProbes,",
                "the control plane would never receive the watch's readings",
            ),
            Pin(
                "app/src/main/kotlin/splice/app/ControlPlane.kt",
                "RuntimeNotAnswering { probes.runtimeNotAnswering() }",
                "/health and /api/heads would read no runtime answer",
            ),
        ).forEach { pin ->
            assertTrue(source(pin.file).contains(pin.line), "${pin.file} must contain `${pin.line}`, or ${pin.harm}")
        }
    }

    /** Found by walking up from the working directory: under Gradle the cwd is the module dir and from
     *  an IDE it is the repo root, so neither is assumed. */
    private fun source(relative: String): String {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve(relative)
            if (Files.exists(candidate)) return Files.readString(candidate)
            dir = dir.parent
        }
        error("$relative not found above ${Paths.get("").toAbsolutePath()}")
    }
}
