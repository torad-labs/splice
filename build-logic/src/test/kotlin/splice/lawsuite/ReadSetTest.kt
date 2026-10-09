// The red-green proof for the read set a law task fingerprints and its test reads. Each case builds a real git repository on a
// @TempDir, because the set is git's own answer: tracked, or untracked and not ignored.
package splice.lawsuite

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class ReadSetTest {
    @TempDir
    lateinit var dir: Path

    private fun git(vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t") + args)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .also {
                it.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                it.environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }
            .start()
        val out = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "git ${args.toList()} failed: $out" }
    }

    private fun file(relative: String, text: String = "x"): File {
        val file = dir.resolve(relative).toFile()
        file.parentFile.mkdirs()
        file.writeText(text)
        return file
    }

    private fun repo() = git("init", "-q")

    @Test
    fun `a tracked file and an untracked unignored file are in the set, an ignored one is not`() {
        repo()
        file(".gitignore", "tools/ignored/\n")
        file("tools/tracked.sh")
        git("add", "--", ".gitignore", "tools/tracked.sh")
        file("tools/new-untracked.sh")
        file("tools/ignored/junk.sh")

        assertEquals(listOf("tools/new-untracked.sh", "tools/tracked.sh"), ReadSet.git(dir.toFile(), listOf("tools")))
    }

    @Test
    fun `RED a tracked file inside an ignored directory is still in the set`() {
        repo()
        file(".gitignore", "tools/ignored/\n")
        file("tools/ignored/forced.sh")
        file("tools/other.sh")
        git("add", "-f", "--", "tools/ignored/forced.sh", "tools/other.sh")

        assertTrue("tools/ignored/forced.sh" in ReadSet.git(dir.toFile(), listOf("tools")))
    }

    @Test
    fun `RED an ignored dangling link lets the set compute while a tracked file beside it is still in it`() {
        repo()
        file(".gitignore", "tools/e2e/receipts/\n")
        file("tools/e2e/tracked.ts")
        git("add", "--", ".gitignore", "tools/e2e/tracked.ts")
        Files.createDirectories(dir.resolve("tools/e2e/receipts/run"))
        Files.createSymbolicLink(dir.resolve("tools/e2e/receipts/run/latest"), Path.of("/out/does-not-exist.log"))

        val set = ReadSet.git(dir.toFile(), listOf("tools"))

        assertEquals(listOf("tools/e2e/tracked.ts"), set)
    }

    @Test
    fun `a tracked file deleted from the worktree is subtracted by git's own deleted answer`() {
        repo()
        file("tools/kept.sh")
        val gone = file("tools/gone.sh")
        git("add", "--", "tools")
        gone.delete()

        assertEquals(listOf("tools/kept.sh"), ReadSet.git(dir.toFile(), listOf("tools")))
    }

    @Test
    fun `RED a tracked path that is missing and that git does not call deleted throws, naming it`() {
        repo()
        file("tools/kept.sh")
        git("add", "--", "tools")
        Files.createSymbolicLink(dir.resolve("tools/dangling.sh"), Path.of("/out/does-not-exist.sh"))
        git("add", "--", "tools/dangling.sh")

        val thrown = assertThrows(IllegalStateException::class.java) { ReadSet.git(dir.toFile(), listOf("tools")) }

        assertTrue(thrown.message!!.contains("tools/dangling.sh"), thrown.message)
    }

    @Test
    fun `RED a root whose every file is deleted throws, naming the root`() {
        repo()
        file("tools/kept.sh")
        val only = file("docs/only.md")
        git("add", "--", "tools", "docs")
        only.delete()

        val thrown = assertThrows(
            IllegalStateException::class.java,
        ) { ReadSet.git(dir.toFile(), listOf("tools", "docs")) }

        assertTrue(thrown.message!!.contains("docs"), thrown.message)
    }

    @Test
    fun `RED a glob row is expanded by git, so an ignored dangling link under its directory neither throws nor enters the set`() {
        repo()
        file(".gitignore", "tools/e2e/receipts/\n")
        file("tools/e2e/tracked.ts")
        file("tools/e2e/deep/also.ts")
        git("add", "--", ".gitignore", "tools/e2e")
        Files.createDirectories(dir.resolve("tools/e2e/receipts/run"))
        Files.createSymbolicLink(dir.resolve("tools/e2e/receipts/run/latest"), Path.of("/out/does-not-exist.log"))

        val set = ReadSet.globbed(dir.toFile(), listOf("tools/e2e/**"))

        assertEquals(listOf("tools/e2e/deep/also.ts", "tools/e2e/tracked.ts"), set)
    }

    @Test
    fun `RED a filename holding a newline is in the set whole and survives the list file's encoding`() {
        repo()
        file("tools/odd\nname.sh")
        file("tools/plain.sh")
        git("add", "--", "tools")

        val set = ReadSet.git(dir.toFile(), listOf("tools"))

        assertTrue("tools/odd\nname.sh" in set, set.toString())
        assertEquals(set, ReadSet.encode(set).split('\u0000').filter { it.isNotEmpty() })
    }

    @Test
    fun `RED two tracked names that differ by an invalid byte do not collapse into one lossy name`() {
        repo()
        Files.createDirectories(dir.resolve(".dev"))
        // odd<FF>.sh is not UTF-8; odd<EF BF BD>.sh is the replacement character a lossy decode would turn it into.
        val script = """printf x > "$(printf '.dev/odd\377.sh')"; printf x > "$(printf '.dev/odd\357\277\275.sh')""""
        val made = ProcessBuilder("sh", "-c", script)
            .directory(dir.toFile())
            .start()
            .waitFor()
        check(made == 0) { "could not create the fixture names" }
        git("add", "--", ".dev")

        val thrown = assertThrows(IllegalStateException::class.java) { ReadSet.git(dir.toFile(), listOf(".dev")) }

        assertTrue(thrown.message!!.contains("6f6464ff2e7368"), thrown.message)
    }

    @Test
    fun `RED the writer refuses an empty name and a repeated one, as the reader does`() {
        assertThrows(IllegalStateException::class.java) { ReadSet.encode(listOf("a.sh", "")) }
        val thrown = assertThrows(IllegalStateException::class.java) { ReadSet.encode(listOf("a.sh", "b.sh", "a.sh")) }

        assertTrue(thrown.message!!.contains("a.sh"), thrown.message)
    }

    @Test
    fun `RED a root named twice names its files twice and throws`() {
        repo()
        file("tools/a.sh")
        git("add", "--", "tools")

        val thrown = assertThrows(
            IllegalStateException::class.java,
        ) { ReadSet.git(dir.toFile(), listOf("tools", "tools/a.sh")) }

        assertTrue(thrown.message!!.contains("tools/a.sh"), thrown.message)
    }

    @Test
    fun `a root that lists nothing fails by name`() {
        repo()
        file("tools/a.sh")
        git("add", "--", "tools")

        val thrown = assertThrows(
            IllegalStateException::class.java,
        ) { ReadSet.git(dir.toFile(), listOf("tools", "checks")) }

        assertTrue(thrown.message!!.contains("checks"))
    }
}
