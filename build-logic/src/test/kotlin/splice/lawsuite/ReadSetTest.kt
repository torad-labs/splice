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

    // The pre-push and gate trees link node_modules to the checkout's. A trailing-slash ignore rule matches a directory only, so
    // the link read as an untracked file and every law task failed on it. The repository's own first ignore line is the rule under test.
    @Test
    fun `the repository's node_modules ignore rule covers a node_modules that is a symlink`() {
        repo()
        val rule = File("../.gitignore").takeIf { it.exists() } ?: File(".gitignore")
        file(".gitignore", rule.readLines().first { it.contains("node_modules") } + "\n")
        file("tools/tracked.sh")
        git("add", "--", ".gitignore", "tools/tracked.sh")
        val modules = Files.createDirectory(dir.resolve("real-modules"))
        Files.createSymbolicLink(dir.resolve("node_modules"), modules)

        val set = ReadSet.git(dir.toFile(), listOf("."))

        assertEquals(listOf(".gitignore", "tools/tracked.sh"), set, "the symlink is ignored, not an untracked file")
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
    fun `RED a glob row expanded by git skips an ignored dangling link under it, without throwing`() {
        repo()
        file(".gitignore", "tools/e2e/receipts/\n")
        file("tools/e2e/tracked.ts")
        file("tools/e2e/deep/also.ts")
        git("add", "--", ".gitignore", "tools/e2e")
        Files.createDirectories(dir.resolve("tools/e2e/receipts/run"))
        Files.createSymbolicLink(dir.resolve("tools/e2e/receipts/run/latest"), Path.of("/out/does-not-exist.log"))

        val set = ReadSet.matched(dir.toFile(), listOf("tools/e2e/**"))

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
        // odd<FF>.sh is not UTF-8; odd<EF BF BD>.sh is the replacement character a lossy decode would turn it into. Both
        // go straight into git's index: macOS's file system stores the first under another, valid name (odd%FF.sh), so
        // only the index holds the name git hands ReadSet byte for byte on every machine.
        val name = """$(printf '.dev/odd\377.sh')"""
        val replaced = """$(printf '.dev/odd\357\277\275.sh')"""
        val blob = """$(git hash-object -w --stdin </dev/null)"""
        val script = """git update-index --add --cacheinfo "100644,$blob,$name" && """ +
            """git update-index --add --cacheinfo "100644,$blob,$replaced""""
        val made = ProcessBuilder("sh", "-c", script)
            .directory(dir.toFile())
            .start()
            .waitFor()
        check(made == 0) { "could not create the fixture names" }

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

    // A ladder leg's inputs name the files whose change re-runs it, and since d95a81d53 (Oct 28) some of them name a kind of file the
    // repository must not hold: noPython's **/*.py, gateRules' **/baseline.xml. Such a glob matches nothing in a sound tree, so the full
    // gate refused both legs before they ran (Oct 30). A leg's set is the union of its globs; only a leg that names no file at all fails.
    @Test
    fun `a leg's glob for a file the repository must not hold matches nothing, and its other globs still count`() {
        repo()
        file("tools/gate/no-python.ts")
        git("add", "--", "tools")

        assertEquals(listOf("tools/gate/no-python.ts"), ReadSet.matched(dir.toFile(), listOf("**/*.py", "**/*.ts")))
    }

    @Test
    fun `a file two of a leg's globs match is in its set once`() {
        repo()
        file("package.json")
        file("tools/gate/ladder.json")
        git("add", "--", "package.json", "tools")

        assertEquals(
            listOf("package.json", "tools/gate/ladder.json"),
            ReadSet.matched(dir.toFile(), listOf("**/*.json", "package.json")),
        )
    }

    @Test
    fun `a new untracked file of the forbidden kind joins the leg's set, so it re-runs the leg`() {
        repo()
        file("tools/gate/no-python.ts")
        git("add", "--", "tools")
        file("scripts/new.py")

        assertEquals(
            listOf("scripts/new.py", "tools/gate/no-python.ts"),
            ReadSet.matched(dir.toFile(), listOf("**/*.py", "**/*.ts")),
        )
    }

    @Test
    fun `RED a leg whose every glob matches nothing fails, naming its globs`() {
        repo()
        file("tools/a.sh")
        git("add", "--", "tools")

        val thrown = assertThrows(IllegalStateException::class.java) {
            ReadSet.matched(dir.toFile(), listOf("**/*.py", "checks/**"))
        }

        assertTrue(thrown.message!!.contains("**/*.py") && thrown.message!!.contains("checks/**"), thrown.message)
    }
}
