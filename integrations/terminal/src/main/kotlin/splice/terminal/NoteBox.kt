// NEW: titled bordered note for the CLI prompt toolkit (cli-wizard CW-2).
package splice.terminal

import splice.core.terminal.CYAN
import splice.core.terminal.RESET

internal class NoteBox(
    private val out: Appendable = System.out,
) {
    fun render(title: String, lines: List<String>) {
        val inner = innerWidth(title, lines)
        val titleFill = (inner - TITLE_PREFIX - visibleWidth(title)).coerceAtLeast(0)
        paint("$TL$H $title ${H.repeat(titleFill)}$TR")
        for (line in lines) {
            val pad = (inner - CONTENT_INDENT - visibleWidth(line)).coerceAtLeast(0)
            out.append(CYAN).append(V).append(RESET)
            out.append(' ').append(line).append(" ".repeat(pad))
            out.append(CYAN).append(V).append(RESET).append('\n')
        }
        paint("$BL${H.repeat(inner)}$BR")
    }

    private fun paint(rule: String) {
        out.append(CYAN).append(rule).append(RESET).append('\n')
    }

    private fun innerWidth(title: String, lines: List<String>): Int {
        var widest = 0
        for (line in lines) {
            val w = visibleWidth(line)
            if (w > widest) widest = w
        }
        return maxOf(widest + CONTENT_INDENT + 1, visibleWidth(title) + TITLE_PREFIX)
    }

    private fun visibleWidth(text: String): Int = SGR.replace(text, "").length
}

private const val TL = "╭"
private const val TR = "╮"
private const val BL = "╰"
private const val BR = "╯"
private const val H = "─"
private const val V = "│"
private const val TITLE_PREFIX = 3 // dash, space before title, space after title
private const val CONTENT_INDENT = 1
private val SGR = Regex("\u001B\\[[0-9;]*m")
