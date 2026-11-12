package splice.core.testing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LawReadSetTest {

    private fun write(root: Path, relative: String, text: String = "x"): Path {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        return file
    }

    /** The list file as the build writes it: every path ends in a NUL, so no filename can split it. */
    private fun declare(root: Path, vararg paths: String): Path =
        Files.writeString(root.resolve("law-read-set.txt"), paths.joinToString("") { "$it\u0000" })

    @Test
    fun `a declared file is listed and read`(@TempDir root: Path) {
        val file = write(root, ".dev/resolver.sh", "resolve_state_dir() {")
        val set = LawReadSet(root, declare(root, ".dev/resolver.sh"))

        assertEquals(listOf(file), set.files())
        assertEquals("resolve_state_dir() {", set.readText(file))
    }

    @Test
    fun `RED a read of a file the build did not declare throws, naming it`(@TempDir root: Path) {
        write(root, ".dev/declared.sh")
        val undeclared = write(root, "tools/gate/node_modules/pkg/resolver.sh")
        val set = LawReadSet(root, declare(root, ".dev/declared.sh"))

        val thrown = assertThrows(IllegalStateException::class.java) { set.readText(undeclared) }

        assertTrue(thrown.message.orEmpty().contains("tools/gate/node_modules/pkg/resolver.sh"))
        assertEquals(listOf("tools/gate/node_modules/pkg/resolver.sh"), UndeclaredReads.drain())
        assertEquals(emptyList<String>(), UndeclaredReads.drain(), "drain consumes the record once")
    }

    @Test
    fun `the listed files are exactly the declared ones, so the reader walks nothing else`(@TempDir root: Path) {
        val kept = write(root, ".dev/kept.sh")
        write(root, ".dev/stray-untracked-and-undeclared.sh")
        val set = LawReadSet(root, declare(root, ".dev/kept.sh"))

        assertEquals(listOf(kept), set.files())
    }

    @Test
    fun `RED a path with a newline in its name survives the list file whole`(@TempDir root: Path) {
        val odd = write(root, "tools/odd\nname.sh")
        val set = LawReadSet(root, declare(root, "tools/odd\nname.sh"))

        assertEquals(listOf(odd), set.files())
        assertEquals("x", set.readText(odd))
    }

    @Test
    fun `RED a listed path that does not exist throws, naming it, and is never filtered out`(@TempDir root: Path) {
        write(root, ".dev/kept.sh")
        val set = LawReadSet(root, declare(root, ".dev/kept.sh", ".dev/gone.sh"))

        val thrown = assertThrows(IllegalStateException::class.java) { set.files() }

        assertTrue(thrown.message.orEmpty().contains(".dev/gone.sh"))
    }

    @Test
    fun `RED a path through a link and dot-dot that opens an undeclared file is refused`(@TempDir root: Path) {
        val outside = Files.createDirectories(root.resolve("outside/sub"))
        Files.writeString(root.resolve("outside/declared.txt"), "outside")
        val repo = Files.createDirectories(root.resolve("repo"))
        Files.writeString(repo.resolve("declared.txt"), "inside")
        Files.createSymbolicLink(repo.resolve("link"), outside)
        val set = LawReadSet(repo, declare(repo, "declared.txt"))

        val throughLink = repo.resolve("link/../declared.txt")
        val thrown = assertThrows(IllegalStateException::class.java) { set.readText(throughLink) }

        assertTrue(thrown.message.orEmpty().contains("outside/declared.txt"), thrown.message)
        assertEquals("inside", set.readText(repo.resolve("declared.txt")))
        UndeclaredReads.drain()
    }

    /** The list file from raw bytes, as a hostile or damaged writer could leave it. */
    private fun raw(root: Path, vararg bytes: Int): Path =
        Files.write(root.resolve("raw-read-set.txt"), ByteArray(bytes.size) { bytes[it].toByte() })

    @Test
    fun `RED a name that is not UTF-8 throws with its bytes`(@TempDir root: Path) {
        // "a\xff" then "a\uFFFD" (EF BF BD): a lossy decode turns both into the same string and a set drops one.
        val list = raw(root, 0x61, 0xff, 0, 0x61, 0xef, 0xbf, 0xbd, 0)

        val thrown = assertThrows(IllegalStateException::class.java) { LawReadSet(root, list) }

        assertTrue(thrown.message.orEmpty().contains("61ff"), thrown.message)
    }

    @Test
    fun `RED an empty field in the middle of the list throws`(@TempDir root: Path) {
        val list = raw(root, 0x61, 0, 0, 0x62, 0)

        val thrown = assertThrows(IllegalStateException::class.java) { LawReadSet(root, list) }

        assertTrue(thrown.message.orEmpty().contains("empty field"), thrown.message)
    }

    @Test
    fun `RED a name listed twice throws, naming it`(@TempDir root: Path) {
        val list = declare(root, ".dev/a.sh", ".dev/a.sh")

        val thrown = assertThrows(IllegalStateException::class.java) { LawReadSet(root, list) }

        assertTrue(thrown.message.orEmpty().contains(".dev/a.sh"), thrown.message)
    }

    @Test
    fun `RED a list that does not end with a NUL throws`(@TempDir root: Path) {
        val list = raw(root, 0x61, 0, 0x62)

        val thrown = assertThrows(IllegalStateException::class.java) { LawReadSet(root, list) }

        assertTrue(thrown.message.orEmpty().contains("does not end"), thrown.message)
    }
}
