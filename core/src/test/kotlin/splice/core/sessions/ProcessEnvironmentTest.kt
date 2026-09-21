// A registered session joins a head only when splice LAUNCHED it (SPLICE=1 beside a local
// ANTHROPIC_BASE_URL); a hand-started claude pointed at a head port is not splice's launch.
package splice.core.sessions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProcessEnvironmentTest {

    private fun environ(root: Path, pid: Long, vararg entries: String) {
        val dir = Files.createDirectories(root.resolve(pid.toString()))
        Files.write(dir.resolve("environ"), entries.joinToString("\u0000").toByteArray())
    }

    /** A credential entry whose bytes are NOT valid UTF-8: decoding the block as one string would
     *  fail (Files.readString throws MalformedInputException), so a resolved port proves the
     *  foreign entry was skipped unread and never decoded. Also: a key that is only a prefix of a
     *  wanted one, and a wanted key at the very end without a trailing NUL. */
    @Test
    fun `a foreign entry is never decoded and prefix look-alikes are not keys`(@TempDir root: Path) {
        val dir = Files.createDirectories(root.resolve("21"))
        val junk = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0xc0.toByte())
        val secret = "API_KEY=".toByteArray() + junk + "!".toByteArray()
        val block = secret + byteArrayOf(0) + "SPLICEX=1".toByteArray() + byteArrayOf(0) +
            "SPLIC=1".toByteArray() + byteArrayOf(0) + "SPLICE=1".toByteArray() + byteArrayOf(0) +
            "ANTHROPIC_BASE_URL=http://127.0.0.1:3101".toByteArray()
        Files.write(dir.resolve("environ"), block)
        assertEquals(3101, ProcessEnvironment(root).spliceHeadPort(21))
        assertNull(ProcessEnvironment(root).spliceHeadPort(22), "no such process reads as no environment")
    }

    @Test
    fun `only a splice-launched process against a local head joins`(@TempDir root: Path) {
        environ(root, 11, "SPLICE=1", "ANTHROPIC_BASE_URL=http://127.0.0.1:3099", "SECRET_TOKEN=never-read")
        environ(root, 12, "ANTHROPIC_BASE_URL=http://127.0.0.1:3099")
        environ(root, 13, "SPLICE=1", "ANTHROPIC_BASE_URL=https://api.example.com")
        environ(root, 14, "SPLICE=0", "ANTHROPIC_BASE_URL=http://127.0.0.1:3099")
        val env = ProcessEnvironment(root)
        assertEquals(3099, env.spliceHeadPort(11))
        assertNull(env.spliceHeadPort(12), "no SPLICE marker: a manual launch, not a splice one")
        assertNull(env.spliceHeadPort(13), "not a local head")
        assertNull(env.spliceHeadPort(14))
        assertNull(env.spliceHeadPort(99), "no such process reads as no environment")
    }
}
