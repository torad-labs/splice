package splice.app.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import splice.topology.ConfigFindings
import splice.topology.ConfigRead
import java.nio.file.Files
import java.nio.file.Path

/** The fix session repairs a splice.toml finding by finding, backs it up first, and never boots on a finding. */
class ConfigFixSessionTest {
    private val provider = """
        [providers.demo]
        dialect = "openai-chat"
        base_url = "https://demo.invalid"
        auth = { kind = "api-key", env = "DEMO_KEY" }
    """.trimIndent()

    private fun broken(root: Path): Path = root.resolve("splice.toml").also {
        Files.writeString(
            it,
            provider + """

            [heads.one]
            provider = "missing"
            port = 3101
            discovery_prefix = "claude-one--"
            pinned_model = "m"
            """.trimIndent() + "\n",
        )
    }

    private fun session(file: Path, root: Path, replies: ArrayDeque<String>, said: MutableList<String>) =
        ConfigFixSession(
            file,
            TerminalOutput { said += it },
            object : FixPrompter {
                override fun ask(question: String): String? = replies.removeFirstOrNull()

                override fun edit(file: Path, line: Int?): Boolean = false
            },
            { text -> (ConfigFindings.read(text, root) as? ConfigRead.Refused)?.findings.orEmpty() },
            { source -> Files.copy(source, root.resolve("backup.toml")) },
        )

    @Test
    fun `setting a value fixes the finding after a backup and the session ends with none left`(@TempDir root: Path) {
        val file = broken(root)
        val said = mutableListOf<String>()

        val fixed = session(file, root, ArrayDeque(listOf("v", "demo")), said).run()

        assertTrue(fixed, said.toString())
        assertTrue(Files.readString(file).contains("provider = \"demo\""))
        val backup = Files.readString(root.resolve("backup.toml"))
        assertTrue(backup.contains("\"missing\""), "the backup holds the old file")
        assertTrue(said.any { it.contains("backed up") }, said.toString())
    }

    @Test
    fun `skipping every finding ends the session without changing the file`(@TempDir root: Path) {
        val file = broken(root)
        val before = Files.readString(file)
        val said = mutableListOf<String>()

        val fixed = session(file, root, ArrayDeque(listOf("s")), said).run()

        assertFalse(fixed)
        assertEquals(before, Files.readString(file))
        assertTrue(said.any { it.contains("left as they are") }, said.toString())
    }

    @Test
    fun `quitting leaves the file untouched and the session reports it did not fix`(@TempDir root: Path) {
        val file = broken(root)
        val before = Files.readString(file)

        val fixed = session(file, root, ArrayDeque(listOf("q")), mutableListOf()).run()

        assertFalse(fixed)
        assertEquals(before, Files.readString(file))
    }

    @Test
    fun `off a terminal the refusal lists every finding and no session is offered`(@TempDir root: Path) {
        val file = broken(root)
        val second = """
            [heads.two]
            provider = "demo"
            port = 3101
            discovery_prefix = "claude-two--"
            pinned_model = "m"
        """.trimIndent()
        Files.writeString(file, Files.readString(file) + second + "\n")
        val offer = ConfigFixOffer(
            env = { key -> if (key == "SPLICE_CONFIG") file.toString() else null },
            interactive = false,
            backups = root.resolve("backups"),
        )

        val refusal = offer.refusalOf(IllegalArgumentException("any verb failure"))

        val said = refusal?.message.orEmpty()
        assertTrue(said.contains("heads.one.provider") && said.contains("heads.two.port"), said)
        assertFalse(offer.offer(), "no terminal, no session: the verb exits non-zero and nothing changed")
    }
}
