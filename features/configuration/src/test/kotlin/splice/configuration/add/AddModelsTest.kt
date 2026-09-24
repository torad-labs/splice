// NEW: V4-34 — splice add-model writes through SelectPrompt and MultiSelectPrompt.
// Cancel and a non-TTY empty selection leave the seeded file byte-identical.
//
// REDO 2026-09-17: the seed is now the REAL starter TopologyLoader materializes, not a hand-written
// stub. The old stub declared no `models = [...]` line on the head, so Topology.modelsFor fell
// through to the whole provider table and the provider-only write looked like it worked. On the
// shipped starter the head DOES declare a roster, modelsFor returns it verbatim
// (Topology.kt:206), and an id added to the provider table alone never reaches /v1/models.
//
// V4-83 (/code-review 2026-09-17 findings 2, 5, 8): every seed below is the REAL starter with one
// documented surgery applied to it — a commented-out roster entry, a comment carrying a `]`, a
// trailing comment on the table header, one provider row cut out — so the denominator stays the
// shipped file rather than a stub that agrees with the test. The starter's provider table already
// carries all ten catalog ids, which is why the `[[providers.KEY.models]]` emitter needed the
// trimmed seed to be executed at all.
package splice.configuration.add

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.terminal.KeyReader
import splice.terminal.MultiSelectPrompt
import splice.terminal.SelectPrompt
import splice.terminal.SttyCommand
import splice.terminal.SttyResult
import splice.terminal.TerminalMode
import splice.topology.TopologyLoader
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class AddModelsTest {

    @Test
    fun `the seeded starter declares a head model roster`(@TempDir dir: Path) {
        // The denominator this whole class rests on: if the starter ever stops declaring
        // `models = [...]`, the roster assertions below would pass vacuously.
        val path = seed(dir)
        val head = requireNotNull(TopologyLoader.loadOrMaterialize(path).heads["openrouter"])
        val roster = requireNotNull(head.models) { "starter head declares no models = [...] roster" }
        assertFalse(LUNA in roster.map { it.id }, "seed already rosters the model the test adds")
    }

    @Test
    fun `cancel at the head picker leaves the file byte-identical`(@TempDir dir: Path) {
        val path = seed(dir)
        val before = Files.readString(path)
        val wrote = verb(selectKeys = byteArrayOf(ESC), selectTty = true).add(path)
        assertFalse(wrote)
        assertEquals(before, Files.readString(path))
    }

    @Test
    fun `non-TTY empty selection writes nothing`(@TempDir dir: Path) {
        val path = seed(dir)
        val before = Files.readString(path)
        val wrote = verb(selectTty = false, multiTty = false).add(path)
        assertFalse(wrote)
        assertEquals(before, Files.readString(path))
    }

    @Test
    fun `choosing a remaining model puts it on the head model surface`(@TempDir dir: Path) {
        val path = seed(dir)
        assertTrue(addFirstRemaining(path), "add-model wrote nothing")
        val topology = TopologyLoader.loadOrMaterialize(path)
        val head = requireNotNull(topology.heads["openrouter"]) { "head missing after add-model" }
        val provider = requireNotNull(topology.providers["openrouter"]) { "provider missing after add-model" }
        assertTrue(LUNA in requireNotNull(head.models).map { it.id }, "added id missing from the head roster")
        assertTrue(LUNA in provider.models.map { it.id }, "added id missing from provider models")
        // catalogFor is the /v1/models surface: it is the only path that reaches modelsFor.
        val catalog = provider.catalogFor(head)
        assertEquals(SONNET, catalog.pinnedModel)
        assertTrue(catalog.models.any { it.id == LUNA }, "added id never reaches /v1/models")
    }

    @Test
    fun `everything outside the head roster survives the add byte-for-byte`(@TempDir dir: Path) {
        val path = seed(dir)
        val before = Files.readString(path)
        assertTrue(addFirstRemaining(path))
        val after = Files.readString(path)
        assertEquals(outsideRoster(before), outsideRoster(after))
    }

    @Test
    fun `adding a model already on the roster is a no-op`(@TempDir dir: Path) {
        val path = seed(dir)
        assertTrue(addFirstRemaining(path))
        val once = Files.readString(path)
        // Re-offering the same id: the roster already names it, so the array is left alone.
        val topology = TopologyLoader.loadOrMaterialize(path)
        val head = requireNotNull(topology.heads["openrouter"])
        val unchanged = HeadModelArray().withAdded(once, "openrouter", listOf(LUNA))
        assertEquals(once, unchanged)
        assertEquals(1, requireNotNull(head.models).count { it.id == LUNA })
    }

    // ---- V4-83 finding (2): structure is read off the mask, never the raw text ----------------

    @Test
    fun `a commented-out roster entry does not make the add a silent no-op`(@TempDir dir: Path) {
        // Raw-text scanning found this id inside the comment, called the roster complete and wrote
        // the file back unchanged — the operator's pick vanished with nothing red.
        val path = seedWith(dir) { intoRoster(it, "  # { id = \"$LUNA\" },") }
        assertTrue(addFirstRemaining(path), "add-model wrote nothing")
        val head = requireNotNull(TopologyLoader.loadOrMaterialize(path).heads["openrouter"])
        assertTrue(LUNA in requireNotNull(head.models).map { it.id }, "the commented-out entry hid the add")
        assertTrue("# { id = \"$LUNA\" }," in Files.readString(path), "the operator's comment was rewritten")
    }

    @Test
    fun `a comment carrying a bracket cannot splice the file mid-array`(@TempDir dir: Path) {
        // A stray `]` in a comment on a row that is NOT the last one: raw bracket counting closed
        // the array there, so the insert landed inside the comment, the real `]` became the array's
        // close, and every row after it was spliced out to the top level — a file ATOMIC_MOVE then
        // put over a working splice.toml.
        val path = seedWith(dir) { it.replace(FIRST_ROW, "$FIRST_ROW  # default for /v1/models]") }
        assertTrue(addFirstRemaining(path), "add-model wrote nothing")
        val head = requireNotNull(TopologyLoader.loadOrMaterialize(path).heads["openrouter"])
        assertTrue(LUNA in requireNotNull(head.models).map { it.id }, "added id missing from the head roster")
        assertEquals(SLOTS, requireNotNull(head.models).size, "rows were spliced out of the roster")
        assertTrue("# default for /v1/models]" in Files.readString(path), "the operator's comment was rewritten")
    }

    // ---- V4-83 finding (5): the header spellings TOML allows ----------------------------------

    @Test
    fun `a trailing comment on the head header is still editable`(@TempDir dir: Path) {
        val path = seedWith(dir) { it.replace(HEADER, "$HEADER  # primary head") }
        assertTrue(addFirstRemaining(path), "add-model wrote nothing")
        val head = requireNotNull(TopologyLoader.loadOrMaterialize(path).heads["openrouter"])
        assertTrue(LUNA in requireNotNull(head.models).map { it.id }, "added id missing from the head roster")
        assertTrue("$HEADER  # primary head" in Files.readString(path), "the header comment was rewritten")
    }

    @Test
    fun `a quoted head key is still editable`(@TempDir dir: Path) {
        // At the array editor, not through the verb: the refusal this proves gone was thrown by
        // arrayStart's $-anchored regex, which never saw the key was quoted.
        val quoted = Files.readString(seed(dir)).replace(HEADER, """[heads."openrouter"]""")
        val added = HeadModelArray().withAdded(quoted, "openrouter", listOf(LUNA))
        assertTrue(rosterOf(added).contains(LUNA), "added id missing from the quoted head's roster")
    }

    // ---- V4-83 finding (2): fail closed — nothing is written that cannot be re-parsed ---------

    @Test
    fun `a composition the loader rejects leaves the file byte-identical`(@TempDir dir: Path) {
        val path = seed(dir)
        val before = Files.readString(path)
        val refused = assertThrows(AddRefused::class.java) {
            verb(
                selectTty = false,
                multiTty = true,
                multiKeys = byteArrayOf(SPACE, ENTER),
                roster = { _, _, _ -> "models = [ this is not toml" },
            ).add(path)
        }
        assertTrue(refused.message.orEmpty().contains("does not parse"), "refusal does not name the reason")
        assertEquals(before, Files.readString(path))
        // Not even the temp file: the re-parse runs before it is created.
        assertEquals(listOf("splice.toml"), dir.toFile().list()?.sorted())
    }

    // ---- V4-83 finding (8): the provider-row emitter is actually executed --------------------

    @Test
    fun `an id the provider table lacks is emitted as a row and parses back`(@TempDir dir: Path) {
        val path = seedWith(dir) { withoutProviderRow(it, LUNA) }
        val seeded = requireNotNull(TopologyLoader.loadOrMaterialize(path).providers["openrouter"])
        assertFalse(LUNA in seeded.models.map { it.id }, "the trimmed seed still carries the row")
        assertTrue(addFirstRemaining(path), "add-model wrote nothing")
        val provider = requireNotNull(TopologyLoader.loadOrMaterialize(path).providers["openrouter"])
        val row = requireNotNull(provider.models.firstOrNull { it.id == LUNA }) { "emitted row never parsed back" }
        assertEquals("GPT-5.6 Luna", row.label)
        assertEquals(1_050_000L, row.contextWindow)
    }

    /** The real starter with [edit] applied — the seed is always the shipped file plus one named
     *  surgery, so no assertion here rests on a hand-written topology. */
    private fun seedWith(dir: Path, edit: (String) -> String): Path {
        val path = seed(dir)
        Files.writeString(path, edit(Files.readString(path)))
        return path
    }

    /** [line] inserted as the last line of the head's `models = [ ... ]` array. */
    private fun intoRoster(text: String, line: String): String {
        val close = text.indexOf("\n]", text.indexOf(ROSTER_OPEN))
        require(close > 0) { "the starter roster no longer ends with a bracket on its own line" }
        return text.substring(0, close) + "\n" + line + text.substring(close)
    }

    /** The starter with one `[[providers.openrouter.models]]` block cut out. */
    private fun withoutProviderRow(text: String, id: String): String {
        val lines = text.lines()
        val row = lines.indexOfFirst { it == "id = \"$id\"" }
        require(row > 0 && lines[row - 1] == PROVIDER_ROW) { "the starter provider block shape changed" }
        return (lines.subList(0, row - 1) + lines.subList(row + PROVIDER_ROW_LINES, lines.size)).joinToString("\n")
    }

    /** The ids inside the head's `models = [ ... ]` array, read off the text. */
    private fun rosterOf(text: String): List<String> =
        text.substringAfter(ROSTER_OPEN).substringBefore("\n]")
            .split("\n")
            .mapNotNull { Regex("id = \"([^\"]*)\"").find(it)?.groupValues?.get(1) }

    /** The first id the starter's head roster does not already carry — `openai/gpt-5.6-luna`. */
    private fun addFirstRemaining(path: Path): Boolean = verb(
        selectTty = false,
        multiTty = true,
        multiKeys = byteArrayOf(SPACE, ENTER),
    ).add(path)

    /** The file with the `models = [ ... ]` array of `[heads.openrouter]` cut out. */
    private fun outsideRoster(text: String): String =
        text.substringBefore(ROSTER_OPEN) + text.substringAfter(ROSTER_OPEN).substringAfter("]")

    /** The operator's REAL shape: whatever TopologyLoader writes on a first run. */
    private fun seed(dir: Path): Path {
        val path = dir.resolve("splice.toml")
        TopologyLoader.loadOrMaterialize(path)
        return path
    }

    private fun verb(
        selectKeys: ByteArray = byteArrayOf(),
        multiKeys: ByteArray = byteArrayOf(),
        selectTty: Boolean = false,
        multiTty: Boolean = false,
        roster: RosterEditor = RosterEditor(HeadModelArray()::withAdded),
    ): AddModelVerb = AddModelVerb(
        select = SelectPrompt(
            keys = KeyReader(ByteArrayInputStream(selectKeys)),
            terminal = idle(selectTty),
            out = StringBuilder(),
            hasConsole = { selectTty },
        ),
        multi = MultiSelectPrompt(
            keys = KeyReader(ByteArrayInputStream(multiKeys)),
            terminal = idle(multiTty),
            out = StringBuilder(),
            hasConsole = { multiTty },
        ),
        roster = roster,
    )

    private fun idle(tty: Boolean): TerminalMode = TerminalMode(
        stty = SttyCommand { args ->
            if (args.getOrNull(1) == "-g") SttyResult(0, "SAVED") else SttyResult(0, "")
        },
        hasConsole = { tty },
        addHook = {},
        removeHook = {},
    )
}

private const val ESC: Byte = 27
private const val SPACE: Byte = 32
private const val ENTER: Byte = 13
private const val SONNET = "anthropic/claude-sonnet-5"
private const val LUNA = "openai/gpt-5.6-luna"
private const val ROSTER_OPEN = "models = ["
private const val HEADER = "[heads.openrouter]"
private const val PROVIDER_ROW = "[[providers.openrouter.models]]"
private const val PROVIDER_ROW_LINES = 3
private const val FIRST_ROW = "  { id = \"anthropic/claude-sonnet-5\", slot = \"sonnet\" },"

/** The starter's four slotted rows, plus the one this test adds. */
private const val SLOTS = 5
