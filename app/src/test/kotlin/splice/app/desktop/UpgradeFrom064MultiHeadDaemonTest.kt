// Upgrade (Nov 24, 2026): a 0.6.4 home with several providers and several heads, two of them siblings on one
// provider, and a team's managed block in splice.toml, upgraded to this build. Its file boots with every head-held
// value a warning that names the key and never the value; one live daemon serves every head's own port and both
// doors (the terminal's /door and Desktop's legacy /desktop path) across every provider, each provider sent its own
// key; each head's command starts on its own pinned model; `check-config --fix` moves what the heads agree on to their
// provider, keeps the team's marker comments and everything between them byte for byte, and the rewritten file boots
// and serves the same, the sibling head served as before; and retiring and restoring the sibling puts its command and
// folder back exactly.
package splice.app.desktop

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.ConfigFixSession
import splice.app.cli.FixPrompter
import splice.core.terminal.TerminalOutput
import splice.topology.ConfigFindings
import splice.topology.ConfigRead
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** The keys a head held in 0.6.4 that its provider holds now. */
private val HELD =
    listOf("context_window", "model_slots", "system_prompt", "system_prompt_mode", "overrides.maxInflight")

class UpgradeFrom064MultiHeadDaemonTest {
    @TempDir
    lateinit var tmp: Path

    private val rig by lazy { UpgradeFrom064MultiHeadRig(tmp) }
    private val fixture get() = rig.fixture

    @AfterEach
    fun stop() = rig.stop()

    @Test
    fun `a 0 6 4 multi-head file boots with every head-held value a warning naming keys and never values`() {
        val read = ConfigFindings.read(fixture.toml(), tmp, tmp)

        assertTrue(read is ConfigRead.Ready, "a 0.6.4 file boots: $read")
        val warnings = (read as ConfigRead.Ready).warnings
        val team = listOf("claude-realinfer", SIBLING).flatMap { head ->
            (HELD + "discovery_prefix").map { "heads.$head.$it" }
        }
        val claudex = listOf("models", "context_window", "overrides.maxInflight").map { "heads.claudex.$it" }
        assertEquals((team + claudex).toSet(), warnings.map { it.path }.toSet())
        assertTrue(warnings.all { it.line != null }, "each names its line: $warnings")
        val values = listOf(MULTI_PROMPT, MULTI_WINDOW, "272000") + fixture.keys.values
        assertTrue(
            warnings.none { w -> values.any { it in w.text() || it in w.move?.value.orEmpty() } },
            "no value is said: $warnings",
        )
    }

    @Test
    fun `every head serves its own port, both doors route every provider, and each command starts on its own pin`() {
        rig.live(fixture.toml()) { serves() }
    }

    @Test
    fun `the fix session keeps the team's block byte for byte, and the rewritten file serves the same`() {
        val file = tmp.resolve("splice.toml").also { Files.writeString(it, fixture.toml()) }
        val before = Files.readString(file)
        val siblingBefore = rig.live(before) { rig.sibling() }

        val said = fix(file)

        val after = Files.readString(file)
        assertEquals(1, after.lines().count { it == BEGIN_MARK }, after)
        assertEquals(1, after.lines().count { it == END_MARK }, after)
        assertEquals(block(before).filterNot(::moved), block(after), "the block keeps every other byte: $said")
        assertEquals(before, Files.readString(tmp.resolve("backup.toml")), "the backup is the file as it was")
        val read = ConfigFindings.read(after, tmp, tmp)
        assertTrue(read is ConfigRead.Ready, "$read")
        val provider = TopologyLoader.parse(after).providers.getValue("realinfer")
        assertEquals(MULTI_WINDOW.toLong(), provider.contextWindow, "the agreed window is the provider's: $said")
        assertEquals("3", provider.overrides["maxInflight"], "and so is the agreed knob")
        assertEquals(MULTI_PROMPT, provider.systemPrompt, "and the agreed prompt")
        val siblingAfter = rig.live(after) { serves().let { rig.sibling() } }
        assertEquals(siblingBefore, siblingAfter, "the sibling is served as before")
    }

    @Test
    fun `retiring the sibling and restoring it puts its command and folder back exactly`() {
        val bin = Files.createDirectories(rig.home.resolve(".local/bin"))
        val link = Files.createSymbolicLink(bin.resolve(SIBLING), tmp.resolve("splice-launch"))
        val folder = rig.home.resolve(".claude-$SIBLING")
        rig.live(fixture.toml()) {
            rig.launch(SIBLING)
            Files.writeString(folder.resolve("notes.md"), "mine")
            val before = snapshot(folder) + ("link" to Files.readSymbolicLink(link).toString())

            val retired = rig.act("retire")
            assertTrue(retired.getValue("ok").jsonPrimitive.boolean, "$retired")
            assertFalse(Files.exists(link, NOFOLLOW_LINKS), "the command is put away")
            assertFalse(Files.exists(folder.resolve("settings.json")), "and splice's settings with it")
            assertEquals("mine", Files.readString(folder.resolve("notes.md")), "the person's file stays")
            assertEquals(setOf(SIBLING), rig.retired())
            val main = fixture.heads.single { it.key == "claude-realinfer" }
            assertEquals(main.says, rig.turn("/door", main, rig.terminal()), "the other head on the provider serves")

            val restored = rig.act("restore")
            assertTrue(restored.getValue("ok").jsonPrimitive.boolean, "$restored")
            assertEquals(before, snapshot(folder) + ("link" to Files.readSymbolicLink(link).toString()))
            assertEquals(emptySet<String>(), rig.retired())
        }
    }

    /** Every head's own port, both doors over every provider, the team's tiers and prompt on the door, and each
     *  command's own starting model. */
    private suspend fun serves() {
        for (head in fixture.heads) {
            val port = fixture.portOf(head)
            assertEquals(200, rig.health(port), head.key)
            assertEquals(head.says, rig.turn("http://127.0.0.1:$port", head), "${head.key} on its own port")
            for (door in listOf("/door", "/desktop")) {
                assertEquals(head.says, rig.turn(door, head, rig.terminal()), "${head.key} through $door")
            }
            assertEquals(head.listed, rig.launch(head.key).first, "${head.key} starts on its own pin")
        }
        val main = fixture.heads.single { it.key == "claude-realinfer" }
        for ((tier, upstream) in listOf("claude-opus-5-5" to "glm-5.3", "claude-haiku-4-5" to "glm-5.3-flash")) {
            assertEquals(main.says, rig.turn("/door", main, rig.terminal(), tier, upstream), tier)
            assertTrue(MULTI_PROMPT in fixture.realinfer.turns.last().body, "the team's prompt rides $tier")
        }
    }

    /** `check-config --fix` over [file], answering m to each warning that offers a move and s to the rest; what it
     *  said. */
    private fun fix(file: Path): List<String> {
        val said = CopyOnWriteArrayList<String>()
        var offered = false
        val prompter = object : FixPrompter {
            override fun ask(question: String): String = if (offered) "m" else "s"
            override fun secret(question: String): String? = null
            override fun edit(file: Path, line: Int?): Boolean = false
        }
        val output = TerminalOutput { line ->
            said += line
            if ("Fixing: " in line) offered = false
            if (line.trimStart().startsWith("[m] ")) offered = true
        }
        ConfigFixSession(
            file,
            output,
            prompter,
            { text ->
                when (val read = ConfigFindings.read(text, tmp, tmp)) {
                    is ConfigRead.Refused -> read.findings
                    is ConfigRead.Ready -> read.warnings
                }
            },
            { source -> Files.copy(source, tmp.resolve("backup.toml")) },
            advisory = true,
        ).run()
        return said
    }

    /** The lines from the team's begin marker to its end marker. */
    private fun block(text: String): List<String> = text.lines().let { lines ->
        lines.subList(lines.indexOf(BEGIN_MARK), lines.indexOf(END_MARK) + 1)
    }

    /** A line of the block a move takes to the provider. */
    private fun moved(line: String): Boolean = HELD.any { line.startsWith("${it.substringAfter('.')} = ") }

    /** Every entry under [dir]: a link by its target, a file by its text, a dir as such. */
    private fun snapshot(dir: Path): Map<String, String> = Files.walk(dir).use { walk ->
        walk.filter { it != dir }.toList().associate { path ->
            dir.relativize(path).toString() to when {
                Files.isSymbolicLink(path) -> "link ${Files.readSymbolicLink(path)}"
                Files.isDirectory(path) -> "dir"
                else -> "file ${Files.readString(path)}"
            }
        }
    }
}
