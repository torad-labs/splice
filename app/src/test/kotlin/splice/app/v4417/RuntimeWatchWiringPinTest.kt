// NEW: V4-417 — the wiring pin for the runtime-reach port, for the reason V4-127's pin exists: the port
// arrives by ASSIGNMENT after ControlServer is constructed (its constructor is at the width ceiling), so
// nothing in the compiler connects the daemon's watch to /health and the heads route. Delete a line and
// everything still builds, and every local head reads OK beside a silent runtime again, which is the
// defect. The assertions are on the SOURCE for the reason V4-136 gave: the property is public and
// settable, so no runtime observation tells "the daemon set it" from "something set it".
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
    fun `the daemon starts the watch and assigns its answer to the server, which the payloads read`() {
        listOf(
            Pin(
                DAEMON,
                "headProbes.startRuntimeWatch(topology, controlPlane.probeScope, log)",
                "nothing would ever probe a runtime",
            ),
            Pin(
                DAEMON,
                "srv.ports.runtimeNotAnswering = RuntimeNotAnswering { headProbes.runtimeNotAnswering() }",
                "/health and /api/heads would never carry a silent runtime",
            ),
            Pin(
                "app/src/main/kotlin/splice/app/control/ControlServer.kt",
                "ports.runtimeNotAnswering?.invoke()",
                "the payloads would read a port nothing feeds",
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
