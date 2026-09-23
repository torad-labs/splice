// NEW: intro, step, note, confirm, cancel, outro chrome for the setup wizard (cli-wizard CW-5).
package splice.terminal

import splice.core.terminal.BG_CYAN
import splice.core.terminal.BLACK
import splice.core.terminal.DIM
import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import splice.core.util.Cancellables

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
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): same as AddSeams' prompter: a failed TTY read and an empty line both mean [default], which the `null, "" -> default` arm below says out loud.
        val line = Cancellables.runCatchingCancellable { readlnOrNull()?.trim()?.lowercase() }.getOrNull()
        return when (line) {
            null, "" -> default
            "y", "yes" -> true
            else -> false
        }
    }
}

/** The operator cancelled the wizard; [WizardFrame.cancel] throws it after saying why. */
public class WizardCancelled(reason: String) : RuntimeException(reason)

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

    public fun cancel(reason: String): Nothing {
        out.append(DIM).append(reason).append(RESET).append('\n')
        throw WizardCancelled(reason)
    }

    public fun outro(message: String) {
        out.append(GREEN).append(message).append(RESET).append('\n')
    }
}
