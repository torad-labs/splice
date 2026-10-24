// NEW: LAN share, round 11 of PR #419's review. The goal: when sharing cannot start, Marcos is told the real cause and a
// step that helps. A certificate file that will not load is moved away to mint a new one; a first start that cannot
// mint one at all names that, never a file that is not there. The real listener, turned on over a state folder where
// the share folder cannot be made: a file sits where it would go.
package splice.share

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.testing.TestPorts
import splice.core.topology.ShareConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class ShareCertificateRefusalTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `a first start that cannot mint a certificate says so, and never asks to move a file that is not there`() {
        val env = mapOf(
            "SPLICE_STATE_DIR" to tmp.resolve("state").toString(),
            "SPLICE_CONFIG" to tmp.resolve("splice.toml").toString(),
            "HOME" to tmp.toString(),
        )
        val paths = StatePaths(envReader = EnvReader { env[it] })
        Files.createDirectories(paths.stateDir)
        Files.writeString(paths.stateDir.resolve("share"), "not a folder")
        val listener = ShareListener(
            ShareStart(Topology(), paths, MgmtKey(paths), ConfigService(paths)) { },
            SharedCatalogs { null },
            SharedQuotas { null },
            paths,
            MachinesJoined { emptySet() },
            { },
        )
        try {
            val state = runBlocking { listener.follow(ShareConfig(enabled = true, port = TestPorts.reserve())) }

            val why = state.why.orEmpty()
            assertFalse(state.listening, "nothing to serve with")
            assertFalse(Files.exists(ShareState(paths).certificateFile), "no certificate file is there")
            assertTrue("could not be made" in why, "it says the certificate could not be made: $why")
            assertFalse("move it away" in why, "and asks nobody to move a file that is not there: $why")
        } finally {
            runBlocking { listener.stop() }
        }
    }
}
