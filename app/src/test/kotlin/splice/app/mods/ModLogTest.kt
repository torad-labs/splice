package splice.app.mods

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.mods.run.ModDrops
import splice.app.mods.run.ModLog
import splice.core.util.LogSink
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path

class ModLogTest {
    @TempDir lateinit var root: Path

    @Test
    fun `new mod messages recreate a removed log file`() {
        val path = root.resolve("record.log")
        val log = ModLog(path, ModDrops(), WallClock { 0 }, LogSink {})
        log.write("before removal")
        Files.move(path, root.resolve("old.log"))
        log.write("the file is missing")
        log.write("after removal")
        assertEquals("after removal\n", Files.readString(path))
    }
}
