// A fresh install chooses no vendor or head for the operator, while its one first-run write remains
// exclusive and owner-only.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class FirstRunTopologyTest {
    @Test
    fun `a first-run topology has no plan or head and is owner-only`(@TempDir root: Path) {
        val file = root.resolve("config/splice.toml")
        val loaded = TopologyLoader.loadOrMaterializeWithDigest(file)

        assertTrue(loaded.topology.heads.isEmpty(), "the starter never preselects a head")
        assertTrue(loaded.topology.providers.isEmpty(), "the starter never preselects a provider")
        assertTrue(Files.readString(file).contains("[daemon]"), "daemon settings remain available")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals(TopologyLoader.sha256Hex(Files.readAllBytes(file)), loaded.digest)
    }
}
