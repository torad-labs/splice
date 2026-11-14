// `[daemon] reserved_peer_names` (docs/peers.md): the names an install reserves are peer names. An entry no peer could
// register under refuses boot, because it would leave the name meant open to any peer while the file looks configured.
package splice.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ReservedPeerNamesConfigTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `peer names an install reserves boot`() {
        val read = ConfigFindings.read("[daemon]\nreserved_peer_names = [\"inbox\", \"pager-2\"]\n", directory)
        assertTrue(read is ConfigRead.Ready, read.toString())
    }

    @Test
    fun `an entry that is no peer name refuses boot, naming the key and not the entry`() {
        val read = ConfigFindings.read("[daemon]\nreserved_peer_names = [\"inbox\", \"Private-Inbox\"]\n", directory)
        val findings = assertInstanceOf(ConfigRead.Refused::class.java, read).findings
        assertEquals(listOf("daemon.reserved_peer_names"), findings.map { it.path })
        assertFalse(findings.toString().contains("Private-Inbox"), "a finding never repeats a value")
    }
}
