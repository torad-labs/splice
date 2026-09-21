// NEW: CW-8 — every prompt widget is silent and defaulting with no console.
package splice.app.cli.prompt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class NonTtyParityTest {

    @Test
    fun `every prompt widget is silent and defaulting when there is no console`() {
        val declared = classesOnDisk()
        val stale = (EXCLUDED.keys - declared).sorted()
        assertEquals(emptyList<String>(), stale, "exclusion names missing from disk=$stale")
        val widgets = declared - EXCLUDED.keys
        val raw = CountingStty()
        val idle = idleTerminal(raw)
        val seen = mutableSetOf<String>()
        for (name in widgets) {
            seen += name
            checkWidget(name, idle, raw)
        }
        assertRawZero(raw)
        val missing = (widgets - seen).sorted()
        val unexpected = (seen - widgets).sorted()
        assertEquals(widgets, seen, "prompt widgets missing=$missing unexpected=$unexpected")
    }

    private fun checkWidget(name: String, idle: TerminalMode, raw: CountingStty) {
        if (!checkRender(name, idle) && !checkSeam(name, idle)) {
            error("unexercised widget $name")
        }
        check(raw.calls == 0) { "$name entered raw mode" }
    }

    private fun checkRender(name: String, idle: TerminalMode): Boolean {
        when (name) {
            "NoteBox" -> checkNoteBox()
            "Spinner" -> checkSpinner()
            "SelectPrompt" -> checkSelect(idle)
            "MultiSelectPrompt" -> checkMulti(idle)
            "WizardFrame" -> checkFrame()
            else -> return false
        }
        return true
    }

    private fun checkSeam(name: String, idle: TerminalMode): Boolean {
        when (name) {
            "TerminalMode" -> assertEquals(7, idle.raw { 7 })
            "KeyReader" -> assertEquals(Key.Escape, KeyReader(ByteArrayInputStream(byteArrayOf())).read())
            else -> return false
        }
        return true
    }

    private fun checkNoteBox() {
        val out = capture { NoteBox(it).render("note", listOf("hello")) }
        assertTrue(out.isNotEmpty())
        assertNoCursor("NoteBox", out)
    }

    private fun checkSpinner() {
        val out = capture { buf ->
            Spinner(out = buf, tty = false, scheduler = PulseScheduler { AutoCloseable { } }).run {
                start("working")
                stop("done")
            }
        }
        assertEquals("done\n", out)
        assertNoCursor("Spinner", out)
    }

    private fun checkSelect(idle: TerminalMode) {
        val out = StringBuilder()
        val picked = SelectPrompt(
            keys = KeyReader(ByteArrayInputStream(byteArrayOf())),
            terminal = idle,
            out = out,
            hasConsole = { false },
        ).ask("pick", OPTIONS, initialIndex = 1)
        assertEquals(SelectOutcome.Chosen("b"), picked)
        assertEquals("", out.toString())
    }

    private fun checkMulti(idle: TerminalMode) {
        val out = StringBuilder()
        val chosen = MultiSelectPrompt(
            keys = KeyReader(ByteArrayInputStream(byteArrayOf())),
            terminal = idle,
            out = out,
            hasConsole = { false },
        ).ask("pick", OPTIONS, initiallySelected = setOf("a"), minimum = 1)
        assertEquals(MultiSelectOutcome.Chosen(listOf("a")), chosen)
        assertEquals("", out.toString())
    }

    private fun checkFrame() {
        val out = capture { buf ->
            val frame = WizardFrame(buf)
            frame.intro("splice setup")
            frame.step("one")
            frame.note("n", listOf("x"))
            frame.outro("ready")
        }
        assertTrue(out.isNotEmpty())
        assertNoCursor("WizardFrame", out)
    }

    private fun capture(block: (StringBuilder) -> Unit): String {
        val buf = StringBuilder()
        block(buf)
        return buf.toString()
    }

    private fun assertNoCursor(name: String, text: String) {
        val hit = CURSOR.find(text) ?: return
        throw AssertionError("$name wrote cursor control: ${visible(hit.value)}")
    }

    private fun visible(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                '\r' -> append("\\r")
                '\u001B' -> append("ESC")
                else -> append(ch)
            }
        }
    }

    private fun assertRawZero(raw: CountingStty) {
        assertEquals(0, raw.calls)
    }

    private fun idleTerminal(raw: CountingStty): TerminalMode = TerminalMode(
        stty = raw,
        hasConsole = { false },
        addHook = {},
        removeHook = {},
    )

    private fun classesOnDisk(): Set<String> {
        val candidates = listOf(
            Path.of("src/main/kotlin/splice/app/cli/prompt"),
            Path.of("app/src/main/kotlin/splice/app/cli/prompt"),
            Path.of("app/src/main/kotlin/splice/app/cli/prompt"),
        )
        val dir = candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("prompt package missing from disk")
        val names = mutableSetOf<String>()
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".kt") }.forEach { path ->
                DECLARED.findAll(Files.readString(path)).forEach { names += it.groupValues[1] }
            }
        }
        check(names.isNotEmpty()) { "scanned zero classes under $dir" }
        return names
    }

    private class CountingStty : SttyCommand {
        var calls = 0
        override fun run(args: List<String>): SttyResult {
            calls += 1
            return if (args.getOrNull(1) == "-g") SttyResult(0, "SAVED") else SttyResult(0, "")
        }
    }
}

private val OPTIONS = listOf(
    SelectOption("a", "Alpha", "1"),
    SelectOption("b", "Beta", "2"),
    SelectOption("c", "Gamma", "3"),
)

private val DECLARED = Regex(
    """(?:fun\s+interface|data\s+class|data\s+object|sealed\s+class|open\s+class|""" +
        """abstract\s+class|class|object|interface)\s+(\w+)""",
)

private val EXCLUDED = mapOf(
    "Key" to "data carrier — sealed key hierarchy",
    "Up" to "data carrier — Key variant",
    "Down" to "data carrier — Key variant",
    "Left" to "data carrier — Key variant",
    "Right" to "data carrier — Key variant",
    "Enter" to "data carrier — Key variant",
    "Space" to "data carrier — Key variant",
    "Escape" to "data carrier — Key variant",
    "CtrlC" to "data carrier — Key variant",
    "Backspace" to "data carrier — Key variant",
    "Char" to "data carrier — Key variant",
    "SelectOption" to "data carrier — menu row",
    "SelectOutcome" to "data carrier — sealed select result",
    "Chosen" to "data carrier — Chosen result",
    "Cancelled" to "data carrier — Cancelled result",
    "MultiSelectOutcome" to "data carrier — sealed multi-select result",
    "SttyResult" to "data carrier — stty exit plus stdout",
    "SttyCommand" to "seam interface — injected stty runner",
    "PulseScheduler" to "seam interface — injected spinner timer",
    "ConfirmPrompt" to "seam interface — injected y/n",
    "UnixStty" to "seam — production stty runner, not a widget",
    "TimerPulseScheduler" to "seam — spinner timer, not a widget",
    "WizardCancelled" to "exception type — cancel outcome",
    // The named seams (kt-no-lambda-seam, 2026-09-16). Each is an injected ROLE, exercised through
    // the widget that takes it rather than on its own: ConsolePresence is what every check below
    // sets to false, and RawBlock is what TerminalMode.raw runs.
    "ConsolePresence" to "seam interface — injected console presence",
    "ShutdownHookAdd" to "seam interface — injected shutdown-hook registration",
    "ShutdownHookRemove" to "seam interface — injected shutdown-hook removal",
    "RawBlock" to "seam interface — the body TerminalMode.raw brackets",
    "PulseTick" to "seam interface — one spinner tick",
)

private val CURSOR = Regex("""\r|\u001B\[[0-9;]*[ABCDJK]""")
