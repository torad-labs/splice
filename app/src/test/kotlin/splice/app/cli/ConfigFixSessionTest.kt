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

    /** A session over the file's findings, or with [advisory] over the warnings boot serves past
     *  (`check-config --fix`), a finding a move would add counted too. */
    private fun session(
        file: Path,
        root: Path,
        replies: ArrayDeque<String>,
        said: MutableList<String>,
        advisory: Boolean = false,
    ) =
        ConfigFixSession(
            file,
            TerminalOutput { said += it },
            object : FixPrompter {
                override fun ask(question: String): String? = replies.removeFirstOrNull()

                override fun secret(question: String): String? = replies.removeFirstOrNull()

                override fun edit(file: Path, line: Int?): Boolean = false
            },
            { text ->
                when (val read = ConfigFindings.read(text, root)) {
                    is ConfigRead.Refused -> read.findings
                    is ConfigRead.Ready -> if (advisory) read.warnings else emptyList()
                }
            },
            { source -> Files.copy(source, root.resolve("backup.toml")) },
            advisory,
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

    /** The file boots, and says nothing it serves past: every warning was moved too. */
    private fun readsClean(file: Path, root: Path) {
        val read = ConfigFindings.read(Files.readString(file), root)
        assertTrue(read is ConfigRead.Ready, Files.readString(file))
        assertEquals(emptyList<Any>(), (read as ConfigRead.Ready).warnings, Files.readString(file))
    }

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

        val fixed = session(file, root, ArrayDeque(listOf("m")), mutableListOf(), advisory = true).run()

        val text = Files.readString(file)
        assertTrue(fixed, text)
        assertTrue(text.contains("selector_aliases = { \"old--\" = \"demo\", qz-- = \"demo\" }"), text)
        assertFalse(text.contains("[door.selector_aliases]"), text)
        assertTrue(text.contains("discovery_prefix = \"qz--\""), "the head keeps its own prefix: $text")
        readsClean(file, root)
    }

    /** The lines of [text] that assign [key], trimmed. */
    private fun assigning(text: String, key: String): List<String> =
        text.lines().map(String::trim).filter { it.substringBefore('=').trim() == key }

    /** The provider's own section of [text], its header down to the next table. */
    private fun providerSection(text: String): String =
        text.substringAfter("[providers.demo]\n").substringBefore("\n[")

    @Test
    fun `a head's standing prompt and its mode move to the provider together on the first move`(@TempDir root: Path) {
        val file = served(root, "", "system_prompt = \"Review as the demo team.\"\nsystem_prompt_mode = \"append\"\n")
        val said = mutableListOf<String>()

        val fixed = session(file, root, ArrayDeque(listOf("m")), said, advisory = true).run()

        val text = Files.readString(file)
        assertTrue(fixed, "one move clears both warnings: $said\n$text")
        val provider = providerSection(text)
        assertTrue(provider.contains("system_prompt = \"Review as the demo team.\""), text)
        assertTrue(provider.contains("system_prompt_mode = \"append\""), text)
        assertEquals(1, assigning(text, "system_prompt").size, "the head's prompt line is taken off: $text")
        assertEquals(1, assigning(text, "system_prompt_mode").size, "the head's mode line is taken off: $text")
        val offer = "[m] move system_prompt and system_prompt_mode to [providers.demo] together"
        assertTrue(said.any { it.contains(offer) }, "$said")
        assertTrue(said.contains("moving system_prompt and system_prompt_mode to [providers.demo]"), "$said")
        assertFalse(said.any { "Review as the demo team." in it }, "no value is printed: $said")
        readsClean(file, root)
    }

    @Test
    fun `a head's prompt file and its mode move to the provider together`(@TempDir root: Path) {
        val file = served(root, "", "system_prompt_mode = \"replace\"\nsystem_prompt_file = \"prompts/demo.md\"\n")
        val said = mutableListOf<String>()

        val fixed = session(file, root, ArrayDeque(listOf("m")), said, advisory = true).run()

        val text = Files.readString(file)
        assertTrue(fixed, "$said\n$text")
        val provider = providerSection(text)
        assertTrue(provider.contains("system_prompt_file = \"prompts/demo.md\""), text)
        assertTrue(provider.contains("system_prompt_mode = \"replace\""), text)
        assertEquals(1, assigning(text, "system_prompt_file").size, text)
        assertEquals(1, assigning(text, "system_prompt_mode").size, text)
        assertTrue(said.contains("moving system_prompt_file and system_prompt_mode to [providers.demo]"), "$said")
        readsClean(file, root)
    }

    @Test
    fun `a head's standing prompt alone moves as it always did`(@TempDir root: Path) {
        val file = served(root, "", "system_prompt = \"Review as the demo team.\"\n")
        val said = mutableListOf<String>()

        val fixed = session(file, root, ArrayDeque(listOf("m")), said, advisory = true).run()

        val text = Files.readString(file)
        assertTrue(fixed, "$said\n$text")
        assertTrue(providerSection(text).contains("system_prompt = \"Review as the demo team.\""), text)
        assertEquals(1, assigning(text, "system_prompt").size, text)
        assertTrue(said.any { it.contains("[m] move it to [providers.demo], where the gateway reads it") }, "$said")
        assertTrue(said.contains("moving system_prompt to [providers.demo]"), "$said")
        readsClean(file, root)
    }

    @Test
    fun `a head's prompt is never moved over a prompt its provider already says`(@TempDir root: Path) {
        val file = served(
            root,
            "system_prompt = \"The provider's own.\"\n",
            "system_prompt = \"Review as the demo team.\"\nsystem_prompt_mode = \"append\"\n",
        )
        val before = Files.readString(file)
        val said = mutableListOf<String>()

        val fixed = session(file, root, ArrayDeque(listOf("m", "s", "m", "s")), said, advisory = true).run()

        assertFalse(fixed, "$said")
        assertEquals(before, Files.readString(file), "nothing is moved over the provider's prompt")
        assertFalse(said.any { it.contains("[m]") }, "no move is offered: $said")
    }

    @Test
    fun `a prompt the heads on a provider disagree on is not moved for one head`(@TempDir root: Path) {
        val file = served(root, "", "system_prompt = \"Review as the demo team.\"\nsystem_prompt_mode = \"append\"\n")
        Files.writeString(
            file,
            Files.readString(file) + "\n[heads.two]\nprovider = \"demo\"\nport = 3102\n" +
                "discovery_prefix = \"claude-two--\"\npinned_model = \"m\"\nsystem_prompt = \"Answer as two.\"\n",
        )
        val before = Files.readString(file)
        val said = mutableListOf<String>()

        val fixed = session(file, root, ArrayDeque(listOf("m", "s", "m", "s")), said, advisory = true).run()

        assertFalse(fixed, "$said")
        assertEquals(before, Files.readString(file), "a move that would change the other head writes nothing")
        assertFalse(said.any { it.contains("[m] move system_prompt") }, "no move is offered: $said")
    }

    @Test
    fun `a prompt one head replaces with is not moved over a sibling that appends the same text`(@TempDir root: Path) {
        val file = served(root, "", "system_prompt = \"Same words.\"\nsystem_prompt_mode = \"replace\"\n")
        Files.writeString(
            file,
            Files.readString(file) + "\n[heads.two]\nprovider = \"demo\"\nport = 3102\n" +
                "discovery_prefix = \"claude-demo--\"\npinned_model = \"m\"\nsystem_prompt = \"Same words.\"\n",
        )
        val before = Files.readString(file)
        val said = mutableListOf<String>()

        val fixed = session(file, root, ArrayDeque(listOf("m", "s", "m", "s")), said, advisory = true).run()

        assertFalse(fixed, "$said")
        assertEquals(before, Files.readString(file), "head two keeps appending: nothing is written")
        assertFalse(said.any { it.contains("[m]") }, "no move is offered: $said")
        assertTrue(said.any { it.contains("another head on this provider is served another standing prompt") }, "$said")
    }

    @Test
    fun `a prompt every head on the provider is served the same way moves and leaves them all served as before`(
        @TempDir root: Path,
    ) {
        val prompt = "system_prompt = \"Same words.\"\nsystem_prompt_mode = \"replace\"\n"
        val file = served(root, "", prompt)
        Files.writeString(
            file,
            Files.readString(file) + "\n[heads.two]\nprovider = \"demo\"\nport = 3102\n" +
                "discovery_prefix = \"claude-demo--\"\npinned_model = \"m\"\n" + prompt,
        )
        val said = mutableListOf<String>()

        val fixed = session(file, root, ArrayDeque(listOf("m")), said, advisory = true).run()

        val text = Files.readString(file)
        assertTrue(fixed, "$said\n$text")
        val provider = providerSection(text)
        assertTrue(provider.contains("system_prompt = \"Same words.\""), text)
        assertTrue(provider.contains("system_prompt_mode = \"replace\""), text)
        assertTrue(text.substringAfter("[heads.two]").contains("system_prompt_mode = \"replace\""), text)
        readsClean(file, root)
    }

    /** [served] with `[providers.other]`, whose own multi-line prompt shows a `[providers.demo]` table as an example,
     *  written ahead of the real one. */
    private fun behindAnExample(root: Path, headExtra: String): Path {
        val file = served(root, "", headExtra)
        val other = "[providers.other]\ndialect = \"openai-chat\"\nbase_url = \"https://other.invalid\"\n" +
            "auth = { kind = \"api-key\", env = \"OTHER_KEY\" }\nsystem_prompt = \"\"\"\nWrite it like this:\n" +
            "[providers.demo]\nsystem_prompt = 'kept'\n\"\"\"\n\n[[providers.other.models]]\nid = \"o\"\n" +
            "context_window = 1000\n\n"
        Files.writeString(file, other + Files.readString(file))
        return file
    }

    @Test
    fun `a table header written inside another provider's multi-line prompt is not where a move lands`(
        @TempDir root: Path,
    ) {
        val file = behindAnExample(root, "system_prompt = \"Review as the demo team.\"\n")
        val said = mutableListOf<String>()

        val fixed = session(file, root, ArrayDeque(listOf("m")), said, advisory = true).run()

        val text = Files.readString(file)
        assertTrue(fixed, "$said\n$text")
        assertTrue(
            text.contains("Write it like this:\n[providers.demo]\nsystem_prompt = 'kept'\n\"\"\"\n"),
            "the other provider's prompt is unchanged: $text",
        )
        val real = "\n\n[providers.demo]\nsystem_prompt = \"Review as the demo team.\"\ndialect = "
        assertTrue(text.contains(real), "it lands under the real header: $text")
        readsClean(file, root)
    }

    @Test
    fun `a head's multi-line prompt moves whole and a line inside it is never taken for its mode`(
        @TempDir root: Path,
    ) {
        val fake = "system_prompt_mode = \"replace\"\n[providers.demo]\n"
        val literal = "system_prompt = '''\n$fake[not.a.table]\nC:\\'''\n"
        val basic = "system_prompt = \"\"\"\nSay \"this\".\n$fake[not.a.table]\nEnd.\"\"\"\n"
        val mode = "system_prompt_mode = \"append\" # appended on purpose\n"
        listOf(literal, basic).forEachIndexed { case, prompt ->
            val dir = Files.createDirectories(root.resolve("case$case"))
            val file = served(dir, "", prompt + mode)
            val said = mutableListOf<String>()

            val fixed = session(file, dir, ArrayDeque(listOf("m")), said, advisory = true).run()

            val text = Files.readString(file)
            assertTrue(fixed, "$said\n$text")
            assertTrue(text.startsWith("[providers.demo]\n$prompt$mode"), "both land under the real header: $text")
            val head = text.substringAfter("[heads.one]")
            assertFalse(head.contains("system_prompt"), "every line of the head's prompt is taken off: $text")
            readsClean(file, dir)
        }
    }

    /** A splice.toml whose provider is an inline table in `[providers]`, and whose head says [headExtra]. */
    private fun inlineProvider(root: Path, headExtra: String): Path = root.resolve("splice.toml").also {
        Files.writeString(
            it,
            "[providers]\ndemo = { dialect = \"openai-chat\", base_url = \"https://demo.invalid\", " +
                "auth = { kind = \"api-key\", env = \"DEMO_KEY\" }, " +
                "models = [{ id = \"m\", context_window = 1000 }] }\n\n[heads.one]\nprovider = \"demo\"\n" +
                "port = 3101\ndiscovery_prefix = \"claude-demo--\"\npinned_model = \"m\"\n" + headExtra,
        )
    }

    @Test
    fun `a one-line prompt moves into an inline provider table`(@TempDir root: Path) {
        val file = inlineProvider(root, "system_prompt = \"Review as the demo team.\"\n")

        val fixed = session(file, root, ArrayDeque(listOf("m")), mutableListOf(), advisory = true).run()

        val text = Files.readString(file)
        assertTrue(fixed, text)
        val inline = "models = [{ id = \"m\", context_window = 1000 }], system_prompt = \"Review as the demo team.\" }"
        assertTrue(text.contains(inline), text)
        readsClean(file, root)
    }

    @Test
    fun `no move is offered into an inline provider table for a value it cannot hold as written`(
        @TempDir root: Path,
    ) {
        val multiline = "system_prompt = \"\"\"\nReview\nas the team.\"\"\"\n"
        for (prompt in listOf(multiline, "system_prompt = \"Review.\" # why\n")) {
            val file = inlineProvider(root, prompt)
            val before = Files.readString(file)
            val said = mutableListOf<String>()

            val fixed = session(file, root, ArrayDeque(listOf("m", "s")), said, advisory = true).run()

            assertFalse(fixed, "$said")
            assertEquals(before, Files.readString(file), "nothing is written")
            assertFalse(said.any { it.contains("[m]") }, "no move is offered only to be refused: $said")
            assertFalse(said.any { it.contains("cannot be moved") }, "$said")
        }
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

        val fixed = session(file, root, ArrayDeque(listOf("m")), mutableListOf(), advisory = true).run()

        val text = Files.readString(file)
        assertTrue(fixed, text)
        assertTrue(text.contains("model_slots = { opus = \"m\", haiku = \"s\" }"), text)
        val slots = text.lines().count { it.trim().startsWith("slot = ") }
        assertEquals(2, slots, "the head keeps its rows and slots: $text")
        readsClean(file, root)
    }
}
