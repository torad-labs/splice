// NEW: V4-132 — the wiring pin for the console's new accounts port, in the shape
// splice.app.ConsoleWiringPinTest already established: the assignment happens by plain
// property mutation after construction (ConsolePorts.accounts is settable, never a constructor
// arg — the width ratchet's ceiling), so nothing in the type system connects the daemon to it.
// Delete the assignment and every /api/auth/{head}/login, /login/{id}, DELETE/PATCH
// .../accounts/{label} route keeps compiling and keeps answering its named 5xx forever. The
// assertion is on the SOURCE for the same reason ConsoleWiringPinTest's is: the property is
// public and settable from anywhere, so no runtime observation tells "ConsoleWiring set it" apart
// from "something else did".
package splice.app

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class ConsoleWiringAccountsPinTest {

    @Test
    fun `the control plane wires the console's accounts port`() {
        assertTrue(
            consoleWiringSource().contains("srv.ports.accounts = ConsoleAccountsImpl()"),
            "ConsoleWiring must assign `srv.ports.accounts`, or the console's login/accounts " +
                "routes answer their unwired 5xx forever, whatever the daemon's real state is",
        )
    }

    private fun consoleWiringSource(): String = source("app/src/main/kotlin/splice/app/ConsoleWiring.kt")

    /** Found by walking up from the working directory: under Gradle the cwd is the module dir and
     *  from an IDE it is the repo root, so neither is assumed. */
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
