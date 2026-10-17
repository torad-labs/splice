package splice.core.compaction

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors

class CompactionInstructionsTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `project-model then project then model then global precedence replaces rather than layers`() {
        val root = tmp.resolve("repo")
        val resolver = CompactionInstructions(
            CompactionConfig(
                instructions = "global",
                model = listOf(CompactionModelConfig("astra", instructions = "model")),
                project = listOf(
                    CompactionProjectConfig(root.toString(), instructions = "project"),
                    CompactionProjectConfig(root.toString(), "astra", instructions = "project-model"),
                ),
            ),
            tmp,
        )

        assertEquals("project-model", resolver.resolve("astra", root.resolve("src")).text)
        assertEquals("project", resolver.resolve("other", root.resolve("src")).text)
        assertEquals("model", resolver.resolve("astra", tmp.resolve("elsewhere")).text)
        assertEquals("global", resolver.resolve("other", tmp.resolve("elsewhere")).text)
    }

    @Test
    fun `a project path may start with a tilde or be relative to the topology directory`() {
        val home = Paths.get(System.getProperty("user.home"))
        val resolver = CompactionInstructions(
            CompactionConfig(
                project = listOf(
                    CompactionProjectConfig("~/compaction-tilde-test", instructions = "home"),
                    CompactionProjectConfig("relative-project", instructions = "relative"),
                ),
            ),
            tmp,
        )
        assertEquals("home", resolver.resolve("astra", home.resolve("compaction-tilde-test/src")).text)
        assertEquals("relative", resolver.resolve("astra", tmp.resolve("relative-project")).text)
        assertNull(resolver.resolve("astra", tmp).text, "the topology directory itself matches neither")
    }

    @Test
    fun `longest matching project path wins within a scope`() {
        val root = tmp.resolve("repo")
        val nested = root.resolve("service")
        val resolver = CompactionInstructions(
            CompactionConfig(
                project = listOf(
                    CompactionProjectConfig(root.toString(), instructions = "root"),
                    CompactionProjectConfig(nested.toString(), instructions = "nested"),
                ),
            ),
            tmp,
        )

        val resolved = resolver.resolve("astra", nested.resolve("src"))
        assertEquals("nested", resolved.text)
        assertEquals("project:$nested", resolved.source)
    }

    @Test
    fun `missing text at a matching scope inherits the next configured scope`() {
        val root = tmp.resolve("repo")
        val resolver = CompactionInstructions(
            CompactionConfig(
                instructions = "global",
                model = listOf(CompactionModelConfig("astra", instructions = "model")),
                project = listOf(CompactionProjectConfig(root.toString(), "astra")),
            ),
            tmp,
        )

        assertEquals("model", resolver.resolve("astra", root).text)
    }

    @Test
    fun `empty most-specific text opts out of inherited instructions`() {
        val root = tmp.resolve("repo")
        val resolver = CompactionInstructions(
            CompactionConfig(
                instructions = "global",
                model = listOf(CompactionModelConfig("astra", instructions = "model")),
                project = listOf(CompactionProjectConfig(root.toString(), "astra", instructions = "")),
            ),
            tmp,
        )

        val resolved = resolver.resolve("astra", root)
        assertEquals("", resolved.text)
        assertNull(resolved.tailText)
        assertEquals(CompactionScope.PROJECT_MODEL, resolved.scope)
    }

    @Test
    fun `absent configuration preserves the client default`() {
        val resolved = CompactionInstructions(configDir = tmp).resolve("astra", tmp)
        assertNull(resolved.text)
        assertEquals(CompactionScope.CLIENT, resolved.scope)
        assertEquals("client", resolved.source)
    }

    @Test
    fun `relative file is loaded from the topology directory`() {
        Files.writeString(tmp.resolve("compact.txt"), "retain evidence")
        val resolver = CompactionInstructions(
            CompactionConfig(file = "compact.txt"),
            tmp,
        )

        val resolved = resolver.resolve("astra", tmp)
        assertEquals("retain evidence", resolved.text)
        assertTrue(resolved.source.endsWith("file:${tmp.resolve("compact.txt")}"), resolved.source)
    }

    /** `file =` text is read again when the file changes, so an edit is live at the next
     *  compaction without a restart; a file that becomes unreadable disables the rule as at boot. */
    @Test
    fun `an edited instructions file is picked up on the next resolve without a restart - review 2026-09-14`() {
        val file = tmp.resolve("live.txt")
        Files.writeString(file, "first")
        val reads = mutableListOf<Path>()
        val resolver = CompactionInstructions(
            CompactionConfig(file = "live.txt"),
            tmp,
            readFile = { path ->
                reads.add(path)
                Files.readString(path)
            },
        )
        assertEquals("first", resolver.resolve("astra", null).text)
        assertEquals("first", resolver.resolve("astra", null).text)
        assertEquals(1, reads.size, "unchanged file: no re-read")
        Files.writeString(file, "second")
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000))
        assertEquals("second", resolver.resolve("astra", null).text)
        assertEquals(2, reads.size)
        Files.delete(file)
        assertNull(resolver.resolve("astra", null).text, "gone: disabled, as it would have been at boot")
    }

    /** Claude Code records getcwd (symlinks resolved); a project configured through a symlink must
     *  still match (review 2026-09-14). */
    @Test
    fun `a project configured through a symlink matches the physical cwd Claude Code records`() {
        val physical = Files.createDirectories(tmp.resolve("data").resolve("proj"))
        Files.createDirectories(physical.resolve("src"))
        val link = Files.createSymbolicLink(tmp.resolve("proj-link"), physical)
        val resolver = CompactionInstructions(
            CompactionConfig(
                instructions = "global",
                project = listOf(CompactionProjectConfig(link.toString(), instructions = "project")),
            ),
            tmp,
        )
        assertEquals("project", resolver.resolve("astra", physical.resolve("src")).text)
        assertEquals("project", resolver.resolve("astra", link.resolve("src")).text)
        assertEquals("global", resolver.resolve("astra", tmp.resolve("elsewhere")).text)
    }

    @Test
    fun `an unreadable selected file degrades to the untouched client request`() {
        val logs = mutableListOf<String>()
        val resolver = CompactionInstructions(
            CompactionConfig(
                instructions = "global",
                model = listOf(CompactionModelConfig("astra", file = "missing.txt")),
            ),
            tmp,
            readFile = { throw java.nio.file.NoSuchFileException(it.toString()) },
            log = logs::add,
        )

        val resolved = resolver.resolve("astra", tmp)
        assertNull(resolved.text)
        assertTrue(resolved.source.endsWith("unreadable"), resolved.source)
        assertEquals(1, logs.size)
    }

    @Test
    fun `concurrent project and model resolutions do not leak across a model switch`() {
        val one = tmp.resolve("one")
        val two = tmp.resolve("two")
        val resolver = CompactionInstructions(
            CompactionConfig(
                instructions = "global",
                model = listOf(CompactionModelConfig("astra", instructions = "astra")),
                project = listOf(
                    CompactionProjectConfig(one.toString(), "astra", instructions = "one-astra"),
                    CompactionProjectConfig(two.toString(), "sol", instructions = "two-sol"),
                ),
            ),
            tmp,
        )
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = (0 until 100).map { index ->
                pool.submit<String?> {
                    if (index % 2 == 0) resolver.resolve("astra", one).text else resolver.resolve("sol", two).text
                }
            }.map { it.get() }

            assertEquals(50, results.count { it == "one-astra" })
            assertEquals(50, results.count { it == "two-sol" })
            assertEquals("astra", resolver.resolve("astra", two).text)
            assertEquals("global", resolver.resolve("sol", one).text)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `ambiguous sources are rejected at the config boundary`() {
        assertThrows(IllegalArgumentException::class.java) {
            CompactionInstructions(
                CompactionConfig(instructions = "inline", file = "also.txt"),
                tmp,
            )
        }
    }
}
