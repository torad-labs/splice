// NEW: intro, step, note, confirm, cancel, outro chrome for the setup wizard (cli-wizard CW-5).
package splice.terminal

import splice.core.terminal.BG_CYAN
import splice.core.terminal.BLACK
import splice.core.terminal.DIM
import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import java.io.IOException

/** Asks a y/n question and returns the answer. */
public fun interface ConfirmPrompt {
    public operator fun invoke(question: String, default: Boolean): Boolean
}

/** A y/n read from the terminal. With no console attached (a pipe, CI, a test) it is [default] and
 *  writes nothing; an empty line or a failed read is [default] too. */
public class ConsoleConfirm(
    private val out: Appendable = System.out,
    private val hasConsole: ConsolePresence = ConsolePresence { System.console() != null },
) : ConfirmPrompt {
    override fun invoke(question: String, default: Boolean): Boolean {
        if (!hasConsole()) return default
        out.append(question).append(if (default) " [Y/n] " else " [y/N] ")
        // A failed TTY read and an empty line both mean [default], which the `null, "" -> default` arm below says out loud.
        val line = try {
            readlnOrNull()?.trim()?.lowercase()
        } catch (_: IOException) {
            null
        }
        return when (line) {
            null, "" -> default
            "y", "yes" -> true
            else -> false
        }
    }
}

/** The setup wizard's chrome: intro, steps, notes, confirm, cancel and outro, all on [out]. */
public class WizardFrame(
    private val out: Appendable = System.out,
    private val ask: ConfirmPrompt = ConsoleConfirm(out),
) {
    public fun intro(title: String) {
        out.append(BG_CYAN).append(BLACK).append(' ').append(title).append(' ').append(RESET).append('\n')
    }

    public fun step(label: String) {
        out.append(DIM).append(label).append(RESET).append('\n')
    }

    public fun note(title: String, lines: List<String>) {
        NoteBox(out).render(title, lines)
    }

    public fun confirm(question: String, default: Boolean): Boolean = ask(question, default)

    /** Says why the wizard ends without installing. True is the wizard's own answer for that ending: the operator chose it. */
    public fun cancel(reason: String): Boolean {
        out.append(DIM).append(reason).append(RESET).append('\n')
        return true
    }

    public fun outro(message: String) {
        out.append(GREEN).append(message).append(RESET).append('\n')
    }
}
