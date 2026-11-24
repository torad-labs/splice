package splice.app.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.topology.ConfigFindings
import splice.topology.ConfigRead
import java.nio.file.Files
import java.nio.file.Path

/** The fix session repairs a splice.toml finding by finding, backs it up first, and never boots on a finding. */
class ConfigFixSessionTest : ConfigFixRig() {
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

    @Test
    fun `a prompt one head appends unsaid and its sibling says append moves one key at a time`(@TempDir root: Path) {
        val file = served(root, "", "system_prompt = \"Same words.\"\n")
        Files.writeString(
            file,
            Files.readString(file) + "\n[heads.two]\nprovider = \"demo\"\nport = 3102\n" +
                "discovery_prefix = \"claude-demo--\"\npinned_model = \"m\"\nsystem_prompt = \"Same words.\"\n" +
                "system_prompt_mode = \"append\"\n",
        )
        val said = mutableListOf<String>()

        val fixed = session(file, root, ArrayDeque(listOf("m", "m")), said, advisory = true).run()

        val text = Files.readString(file)
        assertTrue(fixed, "each move is offered and leaves the gateway served the same prompt: $said\n$text")
        assertFalse(said.any { it.contains("serves the provider with none") }, "$said")
        val provider = providerSection(text)
        assertTrue(provider.contains("system_prompt = \"Same words.\""), text)
        assertTrue(provider.contains("system_prompt_mode = \"append\""), text)
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

        val fixed = session(file, root, ArrayDeque(listOf("m")), mutableListOf(), advisory = true).run()

        val text = Files.readString(file)
        assertTrue(fixed, text)
        assertTrue(text.contains("model_slots = { opus = \"m\", haiku = \"s\" }"), text)
        val slots = text.lines().count { it.trim().startsWith("slot = ") }
        assertEquals(2, slots, "the head keeps its rows and slots: $text")
        readsClean(file, root)
    }

    /** Two heads on one provider that say different windows: neither value can move without changing how the other
     *  head is served. */
    private fun disagreeing(root: Path): Path {
        val file = served(root, "", "context_window = 800\n")
        Files.writeString(
            file,
            Files.readString(file) + "\n[heads.two]\nprovider = \"demo\"\nport = 3102\n" +
                "discovery_prefix = \"claude-demo--\"\npinned_model = \"m\"\ncontext_window = 600\n",
        )
        return file
    }

    /** The window each head is served, by head key. */
    private fun windows(file: Path, root: Path): Map<String, Long?> {
        val ready = ConfigFindings.read(Files.readString(file), root) as ConfigRead.Ready
        val demo = ready.topology.providers.getValue("demo")
        return ready.topology.heads.mapValues { (_, head) -> head.servedOn(demo).contextWindow }
    }

    @Test
    fun `heads on one provider that say different windows are a warning with no move, and each is served its own`(
        @TempDir root: Path,
    ) {
        val file = disagreeing(root)

        val read = ConfigFindings.read(Files.readString(file), root) as ConfigRead.Ready
        val held = read.warnings.filter { it.path.endsWith(".context_window") }

        assertEquals(setOf("heads.one.context_window", "heads.two.context_window"), held.map { it.path }.toSet())
        assertTrue(held.all { it.move == null }, "no move is offered: $held")
        assertTrue(held.all { "say different values" in it.message }, "each says why: $held")
        assertEquals(mapOf("one" to 800L, "two" to 600L), windows(file, root))
    }

    @Test
    fun `a fix session over disagreeing heads writes nothing for them unless the person chooses to`(
        @TempDir root: Path,
    ) {
        val file = disagreeing(root)
        val before = Files.readString(file)
        val said = mutableListOf<String>()

        val fixed = session(file, root, ArrayDeque(listOf("m", "", "s", "m", "", "s")), said, advisory = true).run()

        assertFalse(fixed, "$said")
        assertEquals(before, Files.readString(file), "no answer on offer was given: nothing is written")
        assertFalse(said.any { it.contains("[m]") && it.contains("move") }, "no move is offered: $said")
        assertFalse(Files.exists(root.resolve("backup.toml")), "nothing changed, so nothing was backed up")
        assertEquals(mapOf("one" to 800L, "two" to 600L), windows(file, root))
    }

    @Test
    fun `an answer not on offer is told the keys and the warning is not shown again`(@TempDir root: Path) {
        val file = disagreeing(root)
        val said = mutableListOf<String>()

        session(file, root, ArrayDeque(listOf("m", "", "s", "q")), said, advisory = true).run()

        assertEquals(1, said.count { it.contains("Fixing: heads.one.context_window") }, "$said")
        val hints = said.filter { it.contains("not on offer here; the keys are [d] [v] [e] [s] [q]") }
        assertEquals(2, hints.size, "m and a bare Enter are each answered with the keys: $said")
    }

    @Test
    fun `end of input over a warning with no move quits and writes nothing`(@TempDir root: Path) {
        val file = disagreeing(root)
        val before = Files.readString(file)

        val fixed = session(file, root, ArrayDeque(listOf("m")), mutableListOf(), advisory = true).run()

        assertFalse(fixed)
        assertEquals(before, Files.readString(file))
    }

    @Test
    fun `removing one disagreeing head's window is a choice, and it leaves the other head's as it was`(
        @TempDir root: Path,
    ) {
        val file = disagreeing(root)
        val said = mutableListOf<String>()

        session(file, root, ArrayDeque(listOf("s", "d")), said, advisory = true).run()

        val text = Files.readString(file)
        assertFalse(text.contains("context_window = 600"), text)
        assertTrue(text.contains("context_window = 800"), text)
        assertEquals(1, windows(file, root).values.count { it == 800L }, "head one is still served its own")
    }
}
