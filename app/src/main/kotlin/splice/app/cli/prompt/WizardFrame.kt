// NEW: intro, step, note, confirm, cancel, outro chrome for the setup wizard (cli-wizard CW-5).
package splice.app.cli.prompt

import splice.app.cli.AdminSupport
import splice.core.terminal.BG_CYAN
import splice.core.terminal.BLACK
import splice.core.terminal.DIM
import splice.core.terminal.GREEN
import splice.core.terminal.RESET

internal fun interface ConfirmPrompt {
    operator fun invoke(question: String, default: Boolean): Boolean
}

internal class WizardCancelled(reason: String) : RuntimeException(reason)

internal class WizardFrame(
    private val out: Appendable = System.out,
    private val ask: ConfirmPrompt = ConfirmPrompt { q, d -> AdminSupport.confirm(q, d) },
) {
    fun intro(title: String) {
        out.append(BG_CYAN).append(BLACK).append(' ').append(title).append(' ').append(RESET).append('\n')
    }

    fun step(label: String) {
        out.append(DIM).append(label).append(RESET).append('\n')
    }

    fun note(title: String, lines: List<String>) {
        NoteBox(out).render(title, lines)
    }

    fun confirm(question: String, default: Boolean): Boolean = ask(question, default)

    fun cancel(reason: String): Nothing {
        out.append(DIM).append(reason).append(RESET).append('\n')
        throw WizardCancelled(reason)
    }

    fun outro(message: String) {
        out.append(GREEN).append(message).append(RESET).append('\n')
    }
}
