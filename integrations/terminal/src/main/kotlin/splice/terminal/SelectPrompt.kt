// NEW: CW-3 — single-choice menu. Rewinds exactly the lines it drew so scrollback
// holds one final state, not one copy per keystroke.
package splice.terminal

import splice.core.terminal.BOLD
import splice.core.terminal.CYAN
import splice.core.terminal.DIM
import splice.core.terminal.RESET

/** One menu row: the [value] a choice returns, the [label] drawn, and an optional dim [hint]. */
public data class SelectOption<T>(
    public val value: T,
    public val label: String,
    public val hint: String? = null,
)

/** What a single-choice menu ended with: a value, or Escape / Ctrl-C. */
public sealed class SelectOutcome<out T> {
    public data class Chosen<T>(public val value: T) : SelectOutcome<T>()
    public data object Cancelled : SelectOutcome<Nothing>()
}

/** A single-choice menu; with no console it is the initial option, drawn nowhere. */
public class SelectPrompt(
    private val keys: KeyReader,
    private val terminal: TerminalMode,
    private val out: Appendable,
    private val hasConsole: ConsolePresence = ConsolePresence { System.console() != null },
) {
    public fun <T> ask(
        question: String,
        options: List<SelectOption<T>>,
        initialIndex: Int,
    ): SelectOutcome<T> {
        val start = initialIndex.coerceIn(0, options.lastIndex)
        if (!hasConsole()) return SelectOutcome.Chosen(options[start].value)
        return terminal.raw { runMenu(question, options, start) }
    }

    private fun <T> runMenu(
        question: String,
        options: List<SelectOption<T>>,
        initialIndex: Int,
    ): SelectOutcome<T> {
        var cursor = initialIndex
        var painted = 0
        var outcome: SelectOutcome<T> = SelectOutcome.Cancelled
        var done = false
        while (!done) {
            if (painted > 0) rewind(painted)
            painted = paint(question, options, cursor)
            val key = keys.read()
            cursor = wrapCursor(cursor, options.lastIndex, key)
            val decided = chosenOrCancel(key, options, cursor)
            if (decided != null) {
                outcome = decided
                done = true
            }
        }
        return outcome
    }

    private fun wrapCursor(cursor: Int, last: Int, key: Key): Int = when (key) {
        Key.Up -> if (cursor == 0) last else cursor - 1
        Key.Down -> if (cursor == last) 0 else cursor + 1
        else -> cursor
    }

    private fun <T> chosenOrCancel(
        key: Key,
        options: List<SelectOption<T>>,
        cursor: Int,
    ): SelectOutcome<T>? = when (key) {
        Key.Enter -> SelectOutcome.Chosen(options[cursor].value)
        Key.Escape, Key.CtrlC -> SelectOutcome.Cancelled
        else -> null
    }

    private fun <T> paint(
        question: String,
        options: List<SelectOption<T>>,
        cursor: Int,
    ): Int {
        out.append(BOLD).append(question).append(RESET).append('\n')
        for (i in options.indices) {
            paintRow(options[i], active = i == cursor)
        }
        return 1 + options.size
    }

    private fun <T> paintRow(option: SelectOption<T>, active: Boolean) {
        if (active) {
            out.append(CYAN).append(POINTER).append(RESET).append(' ').append(option.label)
        } else {
            out.append(INACTIVE_PAD).append(DIM).append(option.label).append(RESET)
        }
        val hint = option.hint
        if (hint != null) {
            out.append(HINT_GAP).append(DIM).append(hint).append(RESET)
        }
        out.append('\n')
    }

    private fun rewind(lines: Int) {
        repeat(lines) {
            out.append(CSI_CUU).append(CSI_EL)
        }
    }
}

private const val POINTER = "❯"
private const val INACTIVE_PAD = "  "
private const val HINT_GAP = "  "
private const val CSI_CUU = "\u001B[A"
private const val CSI_EL = "\u001B[2K"
