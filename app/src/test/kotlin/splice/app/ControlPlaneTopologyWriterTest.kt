// The control plane hands the console a topology writer over the file the daemon booted from,
// reading it with the real loader.
package splice.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.SilentHeadProbes
import splice.app.daemon.BootedTopology
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import java.nio.file.Files
import java.nio.file.Path

private const val FILE = "[daemon]\neffort = \"high\"\n"

class ControlPlaneTopologyWriterTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `the control plane wires a writer over the booted file`() {
        val file = tmp.resolve("splice.toml").also { Files.writeString(it, FILE) }
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val plane = ControlPlane(
            DaemonEnvironment(paths, ConfigService(paths), MgmtKey(paths), { }),
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
                    probes = SilentHeadProbes,
                )
            },
        ) { "the control plane did not bind" }
        try {
            val writer = checkNotNull(srv.ports.topology) { "the control plane must offer a topology writer" }
            assertEquals(file, writer.path, "the writer edits the file the daemon booted from")
            assertEquals("{\"effort\":\"high\"}", writer.current()["daemon"].toString(), "read by the real loader")
        } finally {
            srv.stop()
            plane.cancelProbes()
        }
    }
}
