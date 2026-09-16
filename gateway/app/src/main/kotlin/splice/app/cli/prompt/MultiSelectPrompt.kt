// NEW: CW-4 — multi-choice picker derived from SelectPrompt. Same layout, navigation
// and redraw; Space toggles, Enter below minimum repaints a dim reason and keeps reading.
package splice.app.cli.prompt

import splice.app.cli.BOLD
import splice.app.cli.CYAN
import splice.app.cli.DIM
import splice.app.cli.GREEN
import splice.app.cli.RESET

internal sealed class MultiSelectOutcome<out T> {
    internal data class Chosen<T>(val values: List<T>) : MultiSelectOutcome<T>()
    internal data object Cancelled : MultiSelectOutcome<Nothing>()
}

internal class MultiSelectPrompt(
    private val keys: KeyReader,
    private val terminal: TerminalMode,
    private val out: Appendable,
    private val hasConsole: () -> Boolean = { System.console() != null },
) {
    fun <T> ask(
        question: String,
        options: List<SelectOption<T>>,
        initiallySelected: Set<T>,
        minimum: Int,
    ): MultiSelectOutcome<T> {
        if (!hasConsole()) {
            return if (initiallySelected.size < minimum) {
                MultiSelectOutcome.Cancelled
            } else {
                MultiSelectOutcome.Chosen(initiallySelected.toList())
            }
        }
        return terminal.raw { runMenu(question, options, initiallySelected, minimum) }
    }

    private fun <T> runMenu(
        question: String,
        options: List<SelectOption<T>>,
        initiallySelected: Set<T>,
        minimum: Int,
    ): MultiSelectOutcome<T> {
        var cursor = 0
        val selected = initiallySelected.toMutableSet()
        var painted = 0
        var notice: String? = null
        var outcome: MultiSelectOutcome<T> = MultiSelectOutcome.Cancelled
        var done = false
        while (!done) {
            if (painted > 0) rewind(painted)
            painted = paint(question, options, cursor, selected, notice)
            val key = keys.read()
            cursor = wrapCursor(cursor, options.lastIndex, key)
            when (key) {
                Key.Space -> notice = toggle(selected, options[cursor].value, minimum, notice)
                Key.Enter -> {
                    val confirmed = confirm(selected, options, minimum)
                    if (confirmed == null) {
                        notice = "select at least $minimum"
                    } else {
                        outcome = confirmed
                        done = true
                    }
                }
                Key.Escape, Key.CtrlC -> {
                    outcome = MultiSelectOutcome.Cancelled
                    done = true
                }
                else -> Unit
            }
        }
        return outcome
    }

    private fun wrapCursor(cursor: Int, last: Int, key: Key): Int = when (key) {
        Key.Up -> if (cursor == 0) last else cursor - 1
        Key.Down -> if (cursor == last) 0 else cursor + 1
        else -> cursor
    }

    private fun <T> toggle(
        selected: MutableSet<T>,
        value: T,
        minimum: Int,
        notice: String?,
    ): String? {
        if (value in selected) selected.remove(value) else selected.add(value)
        return if (selected.size >= minimum) null else notice
    }

    private fun <T> confirm(
        selected: Set<T>,
        options: List<SelectOption<T>>,
        minimum: Int,
    ): MultiSelectOutcome<T>? {
        if (selected.size < minimum) return null
        return MultiSelectOutcome.Chosen(options.filter { it.value in selected }.map { it.value })
    }

    private fun <T> paint(
        question: String,
        options: List<SelectOption<T>>,
        cursor: Int,
        selected: Set<T>,
        notice: String?,
    ): Int {
        out.append(BOLD).append(question).append(RESET).append('\n')
        for (i in options.indices) {
            paintRow(options[i], active = i == cursor, on = options[i].value in selected)
        }
        var lines = 1 + options.size
        if (notice != null) {
            out.append(DIM).append(notice).append(RESET).append('\n')
            lines += 1
        }
        return lines
    }

    private fun <T> paintRow(option: SelectOption<T>, active: Boolean, on: Boolean) {
        if (active) {
            out.append(CYAN).append(POINTER).append(RESET).append(' ')
        } else {
            out.append(INACTIVE_PAD)
        }
        if (on) {
            out.append(GREEN).append(CHECKED).append(RESET)
        } else {
            out.append(DIM).append(UNCHECKED).append(RESET)
        }
        if (active) {
            out.append(' ').append(option.label)
        } else {
            out.append(' ').append(DIM).append(option.label).append(RESET)
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
private const val CHECKED = "◉"
private const val UNCHECKED = "○"
private const val INACTIVE_PAD = "  "
private const val HINT_GAP = "  "
private const val CSI_CUU = "\u001B[A"
private const val CSI_EL = "\u001B[2K"
