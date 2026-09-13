import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.compaction.CompactionConfig
import splice.core.compaction.CompactionInstructions
import splice.core.compaction.CompactionModelConfig
import splice.core.compaction.CompactionProjectConfig
import splice.core.compaction.CompactionScope
import java.nio.file.Files
import java.nio.file.Path
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
