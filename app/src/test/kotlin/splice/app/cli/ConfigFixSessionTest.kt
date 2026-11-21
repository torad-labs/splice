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

    private fun broken(root: Path, name: String = "splice.toml"): Path = root.resolve(name).also {
        Files.writeString(
            it,
            provider + """

            [heads.one]
            provider = "missing"
            port = 3101
            discovery_prefix = "claude-demo--"
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

                override fun secret(question: String): String? = replies.removeFirstOrNull()

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

    @Test
    fun `a linked splice toml is fixed at its target, and the link stays a link`(@TempDir root: Path) {
        val target = broken(root.resolve("real").also(Files::createDirectories), "linked.toml")
        val link = root.resolve("splice.toml")
        Files.createSymbolicLink(link, target)

        val fixed = session(link, root, ArrayDeque(listOf("v", "demo")), mutableListOf()).run()

        assertTrue(fixed)
        assertTrue(Files.isSymbolicLink(link), "the link is still a link")
        assertTrue(Files.readString(target).contains("provider = \"demo\""), "the target is what was fixed")
    }

    /** A provider with one declared model, then [extra] under the head `one`, which serves it. */
    private fun served(root: Path, providerExtra: String, headExtra: String): Path = root.resolve("splice.toml").also {
        Files.writeString(
            it,
            """
            [providers.demo]
            dialect = "openai-chat"
            base_url = "https://demo.invalid"
            auth = { kind = "api-key", env = "DEMO_KEY" }
            """.trimIndent() + "\n" + providerExtra + """

            [[providers.demo.models]]
            id = "m"
            context_window = 1000

            [[providers.demo.models]]
            id = "s"
            context_window = 1000

            [heads.one]
            provider = "demo"
            port = 3101
            discovery_prefix = "claude-demo--"
            pinned_model = "m"
            """.trimIndent() + "\n" + headExtra,
        )
    }

    private fun readsClean(file: Path, root: Path) =
        assertTrue(ConfigFindings.read(Files.readString(file), root) is ConfigRead.Ready, Files.readString(file))

    @Test
    fun `a move into a provider that already assigns its overrides inline adds the field to that table`(
        @TempDir root: Path,
    ) {
        val file = served(
            root,
            "overrides = { maxInflight = \"4\" } # kept\n",
            "[heads.one.overrides]\nupstreamTimeoutMs = \"600000\"\n",
        )

        val fixed = session(file, root, ArrayDeque(listOf("m")), mutableListOf()).run()

        val text = Files.readString(file)
        assertTrue(fixed, text)
        assertTrue(text.contains("overrides = { maxInflight = \"4\", upstreamTimeoutMs = \"600000\" } # kept"), text)
        assertFalse(text.contains("[providers.demo.overrides]"), "a second definition of the same table: $text")
        val left = text.lines().count { it.contains("upstreamTimeoutMs") }
        assertEquals(1, left, "the head's line is taken off: $text")
        readsClean(file, root)
    }

    @Test
    fun `a prefix alias moves into an inline selector_aliases and keeps the aliases already there`(
        @TempDir root: Path,
    ) {
        val file = served(root, "", "")
        Files.writeString(
            file,
            Files.readString(file).replace("claude-demo--", "qz--") +
                "\n[door]\nselector_aliases = { \"old--\" = \"demo\" }\n",
        )

        val fixed = session(file, root, ArrayDeque(listOf("m")), mutableListOf()).run()

        val text = Files.readString(file)
        assertTrue(fixed, text)
        assertTrue(text.contains("selector_aliases = { \"old--\" = \"demo\", qz-- = \"demo\" }"), text)
        assertFalse(text.contains("[door.selector_aliases]"), text)
        assertTrue(text.contains("discovery_prefix = \"qz--\""), "the head keeps its own prefix: $text")
        readsClean(file, root)
    }

    @Test
    fun `tiers named on the head's model rows move to the provider and the head keeps its allowlist`(
        @TempDir root: Path,
    ) {
        val file = served(
            root,
            "",
            "\n[[heads.one.models]]\nid = \"m\"\nslot = \"opus\"\n" +
                "\n[[heads.one.models]]\nid = \"s\"\nslot = \"haiku\"\n",
        )

        val fixed = session(file, root, ArrayDeque(listOf("m")), mutableListOf()).run()

        val text = Files.readString(file)
        assertTrue(fixed, text)
        assertTrue(text.contains("model_slots = { opus = \"m\", haiku = \"s\" }"), text)
        val slots = text.lines().count { it.trim().startsWith("slot = ") }
        assertEquals(2, slots, "the head keeps its rows and slots: $text")
        readsClean(file, root)
    }
}
