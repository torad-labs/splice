// NEW: V4-128 — the daemon wires ONE topology writer into the control server: over the file the daemon
// booted from, judged by TopologyLoader.parse, the loader it booted with. The property is assigned after
// construction (the constructor sits at the width ratchet), so the compiler cannot see a lost line and
// the routes would answer their named 503 forever; this pin is what makes that a red.
//
// EXPECTED-RED until the committer applies V4-128's ConsoleWiring line (ConsoleWiring is orchestrator-
// applied under the CONTROLSERVER RECONCILED law, so this row writes the pin and the commit records red
// before and green after). It fails on `srv.ports.topology` being null.
package splice.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.DashboardPage
import splice.app.control.TurnPathStalled
import splice.app.daemon.BootedTopology
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import java.nio.file.Files
import java.nio.file.Path

private const val FILE = "[daemon]\neffort = \"high\"\n"

class TopologyWiringTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `the control plane wires a writer over the booted file (EXPECTED-RED until ConsoleWiring is applied)`() {
        val file = tmp.resolve("splice.toml").also { Files.writeString(it, FILE) }
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val plane = ControlPlane(
            paths,
            ConfigService(paths),
            MgmtKey(paths),
            DashboardPage { "<!doctype html>" },
            { },
            { },
            BootedTopology(path = file),
        )
        val srv = checkNotNull(
            runBlocking {
                plane.start(
                    controlPort = 0, // OS-assigned at bind: no leased port to lose before the bind
                    heads = emptyMap(),
                    failedHeads = { 0 },
                    headCount = 0,
                    turnPathStalled = TurnPathStalled { emptyList() },
                )
            },
        ) { "the control plane did not bind" }
        try {
            val writer = checkNotNull(srv.ports.topology) { "ConsoleWiring must assign srv.ports.topology" }
            assertEquals(file, writer.path, "the writer edits the file the daemon booted from")
            assertEquals("{\"effort\":\"high\"}", writer.current()["daemon"].toString(), "read by the real loader")
        } finally {
            srv.stop()
            plane.cancelProbes()
        }
    }
}
