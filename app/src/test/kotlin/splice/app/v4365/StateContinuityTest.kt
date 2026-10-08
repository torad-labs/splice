package splice.app.v4365

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.ControlPlane
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import java.nio.file.Path

class StateContinuityTest {
    @Test
    fun `the unsupervised successor reads the same management key from the same state directory`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp.resolve("chosen-state"))
        val mgmtKey = MgmtKey(paths)
        val before = mgmtKey.get()
        val plane = ControlPlane(
            statePaths = paths,
            config = ConfigService(paths),
            mgmtKey = mgmtKey,
            log = {},
            shutdownDaemon = {},
        )
        val successor = plane.successorStateDir()
        assertEquals(paths.stateDir, successor, "SPLICE_STATE_DIR points at the state directory, not its root")
        assertEquals(before, MgmtKey(StatePaths(baseOverride = successor)).get())
    }
}
