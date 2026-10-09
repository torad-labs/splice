// NEW: V4-34 — splice add-model writes through SelectPrompt and MultiSelectPrompt.
// Cancel and a non-TTY empty selection leave the seeded file byte-identical.
//
// V4-356: the first-run starter intentionally has no head. These add-model tests instead append
// the shipped OpenRouter profile through AddProfiles.toml, then turn its tier declarations into
// a legacy allowlist so the editor's comment, bracket and byte-preservation cases stay exercised.
// The denominator is the profile the operator can choose, never a duplicate hand-written roster.
package splice.configuration.add

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.DiscoveredModel
import splice.terminal.KeyReader
import splice.terminal.MultiSelectPrompt
import splice.terminal.SelectPrompt
import splice.terminal.SttyCommand
import splice.terminal.SttyResult
import splice.terminal.TerminalMode
import splice.topology.TopologyLoader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class AddModelsTest {

    @Test
    fun `an existing OpenRouter allowlist keeps its declared head roster`(@TempDir dir: Path) {
        // These legacy explicit-allowlist cases must keep exercising the roster editor.
        val path = seed(dir)
        val head = requireNotNull(TopologyLoader.loadOrMaterialize(path).heads["openrouter"])
        val roster = requireNotNull(head.models) { "profile head declares no models = [...] roster" }
        assertFalse(LUNA in roster.map { it.id }, "seed already rosters the model the test adds")
    }

    @Test
    fun `fresh discovery heads offer no already declared models`(@TempDir dir: Path) {
        val path = dir.resolve("splice.toml")
        val profiles = AddProfiles()
        val profile = requireNotNull(profiles.find("openrouter"))
        Files.writeString(path, profiles.toml(profile, "openrouter", OPENROUTER_PORT))
        val before = Files.readString(path)
        assertTrue(AddModelOffers().of(TopologyLoader.parse(before)).single().remaining.isEmpty())
        assertFalse(addFirstRemaining(path))
        assertEquals(before, Files.readString(path))
    }

    @Test
    fun `adding a missing provider row preserves unrestricted discovery and independent tiers`(@TempDir dir: Path) {
        val path = dir.resolve("splice.toml")
        val profiles = AddProfiles()
        val profile = requireNotNull(profiles.find("openrouter"))
        val emitted = profiles.toml(profile, "openrouter", OPENROUTER_PORT)
        Files.writeString(path, withoutProviderRow(emitted, LUNA))
        val original = TopologyLoader.parse(Files.readString(path)).heads.getValue("openrouter")
        assertTrue(addFirstRemaining(path))
        val topology = TopologyLoader.loadOrMaterialize(path)
        val head = topology.heads.getValue("openrouter")
        assertEquals(null, head.models)
        assertEquals(original.modelSlots, head.modelSlots)
        val discovered = DiscoveredModel("fixture/extra-model", contextWindow = 64_000)
        val catalog = topology.providers.getValue(head.provider).catalogFor(head, discovered = listOf(discovered))
        assertTrue(catalog.contains(LUNA))
        assertTrue(catalog.contains(discovered.id))
        assertEquals(profile.models.first { it.id == LUNA }.rates, catalog.models.first { it.id == LUNA }.rates)
    }

    @Test
    fun `cancel at the head picker leaves the file byte-identical`(@TempDir dir: Path) {
        val path = seed(dir)
        val before = Files.readString(path)
        val wrote = verb(select = Keyboard(byteArrayOf(ESC), tty = true)).add(path)
        assertEquals(AddModelsResult.NothingWritten, wrote)
        assertEquals(before, Files.readString(path))
    }

    @Test
    fun `non-TTY empty selection writes nothing`(@TempDir dir: Path) {
        val path = seed(dir)
        val before = Files.readString(path)
        val wrote = verb().add(path)
        assertEquals(AddModelsResult.NothingWritten, wrote)
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
        assertEquals(once, (unchanged as RosterEdit.Edited).text)
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
        val roster = rosterOf((added as RosterEdit.Edited).text)
        assertTrue(roster.contains(LUNA), "added id missing from the quoted head's roster")
    }

    // ---- V4-83 finding (2): fail closed — nothing is written that cannot be re-parsed ---------

    @Test
    fun `a composition the loader rejects leaves the file byte-identical`(@TempDir dir: Path) {
        val path = seed(dir)
        val before = Files.readString(path)
        val refused = verb(
            multi = Keyboard(byteArrayOf(SPACE, ENTER), tty = true),
            roster = { _, _, _ -> RosterEdit.Edited("models = [ this is not toml") },
        ).add(path)
        assertTrue(
            (refused as AddModelsResult.Refused).sentence.contains("does not parse"),
            "refusal does not name the reason",
        )
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
        assertEquals("GPT-6 Luna", row.label)
        assertEquals(1_050_000L, row.contextWindow)
    }

    /** V4-434, RED before: a curated row `splice add-model` had to emit (the provider table lacked it) came
     *  out with no card, so a turn on it read "no rate card" although the profile prices the model. */
    @Test
    fun `an id the provider table lacks is emitted with its rate card - V4-434`(@TempDir dir: Path) {
        val path = seedWith(dir) { withoutProviderRow(it, LUNA) }
        assertTrue(addFirstRemaining(path), "add-model wrote nothing")
        val provider = requireNotNull(TopologyLoader.loadOrMaterialize(path).providers["openrouter"])
        val row = requireNotNull(provider.models.firstOrNull { it.id == LUNA }) { "emitted row never parsed back" }
        val curated = requireNotNull(AddProfiles().find("openrouter")).models.first { it.id == LUNA }.rates
        assertEquals(requireNotNull(curated) { "the profile ships $LUNA with no card" }, row.rates)
    }

    // ---- V4-220: the file is read again before the rename -----------------------------------

    /** RED before V4-220: the verb read splice.toml before its prompts and renamed its composition over
     *  whatever the file held after them, so an edit made while the picker was open was lost. */
    @Test
    fun `an edit made while the picker is open is kept and the add refuses`(@TempDir dir: Path) {
        val path = seed(dir)
        val edit = "\n# edited while add-model was open\n"
        val keys = object : InputStream() {
            private val picks = ByteArrayInputStream(byteArrayOf(SPACE, ENTER))
            private var edited = false

            override fun read(): Int {
                if (!edited) Files.writeString(path, edit, StandardOpenOption.APPEND).also { edited = true }
                return picks.read()
            }
        }
        val verb = verb(multi = Keyboard(keys, tty = true))

        val refused = verb.add(path) as AddModelsResult.Refused

        assertTrue(refused.sentence.contains("changed while add-model was open"), refused.sentence)
        val after = Files.readString(path)
        assertTrue(after.endsWith(edit), "the edit made during the prompt was overwritten")
        assertFalse(LUNA in rosterOf(after), "the refused add still reached the roster")
    }

    /** The emitted OpenRouter profile with one named surgery, never a hand-written topology. */
    private fun seedWith(dir: Path, edit: (String) -> String): Path {
        val path = seed(dir)
        Files.writeString(path, edit(Files.readString(path)))
        return path
    }

    /** [line] inserted as the last line of the head's `models = [ ... ]` array. */
    private fun intoRoster(text: String, line: String): String {
        val close = text.indexOf("\n]", text.indexOf(ROSTER_OPEN))
        require(close > 0) { "the configured profile roster no longer ends with a bracket on its own line" }
        return text.substring(0, close) + "\n" + line + text.substring(close)
    }

    /** The emitted profile with one `[[providers.openrouter.models]]` block cut out. */
    private fun withoutProviderRow(text: String, id: String): String {
        val lines = text.lines()
        val row = lines.indexOfFirst { it == "id = \"$id\"" }
        require(row > 0 && lines[row - 1] == PROVIDER_ROW) { "the profile provider block shape changed" }
        return (lines.subList(0, row - 1) + lines.subList(row + PROVIDER_ROW_LINES, lines.size)).joinToString("\n")
    }

    /** The ids inside the head's `models = [ ... ]` array, read off the text. */
    private fun rosterOf(text: String): List<String> =
        text.substringAfter(ROSTER_OPEN).substringBefore("\n]")
            .split("\n")
            .mapNotNull { Regex("id = \"([^\"]*)\"").find(it)?.groupValues?.get(1) }

    /** The first id the emitted profile's head roster does not yet carry. */
    private fun addFirstRemaining(path: Path): Boolean =
        verb(multi = Keyboard(byteArrayOf(SPACE, ENTER), tty = true)).add(path) == AddModelsResult.Written

    /** The file with the `models = [ ... ]` array of `[heads.openrouter]` cut out. */
    private fun outsideRoster(text: String): String =
        text.substringBefore(ROSTER_OPEN) + text.substringAfter(ROSTER_OPEN).substringAfter("]")

    /** An existing explicit allowlist, derived from the shipped profile's tier declarations.
     *  Fresh profiles instead use model_slots; those unrestricted heads are tested separately. */
    private fun seed(dir: Path): Path {
        val path = dir.resolve("splice.toml")
        TopologyLoader.loadOrMaterialize(path)
        val profiles = AddProfiles()
        val profile = requireNotNull(profiles.find("openrouter"))
        val emitted = profiles.toml(profile, "openrouter", OPENROUTER_PORT)
        val oneLine = emitted.lineSequence().first { it.startsWith("model_slots =") }
        val entries = profile.models.flatMap { model ->
            model.slots.map { slot -> "  { id = \"${model.id}\", slot = \"$slot\" }," }
        }
        val multiline = (listOf(ROSTER_OPEN) + entries + "]").joinToString("\n")
        Files.writeString(path, Files.readString(path) + emitted.replace(oneLine, multiline))
        return path
    }

    /** What one prompt reads from: the bytes the operator types, and whether a terminal is attached. */
    private class Keyboard(private val input: InputStream, val tty: Boolean = false) {
        constructor(keys: ByteArray, tty: Boolean = false) : this(ByteArrayInputStream(keys), tty)

        fun reader() = KeyReader(input)
    }

    private fun verb(
        select: Keyboard = Keyboard(byteArrayOf()),
        multi: Keyboard = Keyboard(byteArrayOf()),
        roster: RosterEditor = RosterEditor(HeadModelArray()::withAdded),
    ): AddModelVerb = AddModelVerb(
        select = SelectPrompt(
            keys = select.reader(),
            terminal = idle(select.tty),
            out = StringBuilder(),
            hasConsole = { select.tty },
        ),
        multi = MultiSelectPrompt(
            keys = multi.reader(),
            terminal = idle(multi.tty),
            out = StringBuilder(),
            hasConsole = { multi.tty },
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

// why: the configured fixture needs a valid, inert head port; no socket is bound in these tests.
private const val OPENROUTER_PORT = 3101
private const val SONNET = "anthropic/claude-sonnet-5"
private const val LUNA = "openai/gpt-6-luna"
private const val ROSTER_OPEN = "models = ["
private const val HEADER = "[heads.openrouter]"
private const val PROVIDER_ROW = "[[providers.openrouter.models]]"

// why: the `id =` line, label, context_window and the rates card, since every openrouter row is priced (V4-434)
private const val PROVIDER_ROW_LINES = 4
private const val FIRST_ROW = "  { id = \"anthropic/claude-sonnet-5\", slot = \"sonnet\" },"

/** The selected profile's four slotted rows, plus the one this test adds. */
private const val SLOTS = 5
