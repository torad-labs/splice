// NEW: what Claude Code files as a user record that is not the person's own prose: a slash command and what it
// printed, and the system-reminder notes it adds for the model. The person typed "/rename notes", so that is the line
// they see; the command's output is a quiet system line; the reminders are the client's note to the model and are
// never a message.
package splice.client.transcript

private val REMINDER = Regex("<system-reminder>.*?</system-reminder>", RegexOption.DOT_MATCHES_ALL)
private val COMMAND_NAME = Regex("<command-name>\\s*(.*?)\\s*</command-name>", RegexOption.DOT_MATCHES_ALL)
private val COMMAND_ARGS = Regex("<command-args>(.*?)</command-args>", RegexOption.DOT_MATCHES_ALL)
private val COMMAND_STDOUT =
    Regex("<local-command-std(?:out|err)>(.*?)</local-command-std(?:out|err)>", RegexOption.DOT_MATCHES_ALL)

/** What one user record's text is once the client's own markup is read. */
internal sealed class EchoReading {
    /** Nothing a person would read: the record was only reminders, or an empty command output. */
    data object Hidden : EchoReading()

    /** The person's command, as they typed it: "/rename notes-2-5". */
    data class Command(val line: String) : EchoReading()

    /** What a command printed, without its tags. */
    data class Output(val text: String) : EchoReading()

    /** Ordinary text, reminders removed. */
    data class Plain(val text: String) : EchoReading()
}

internal class ClientEcho {
    fun read(text: String): EchoReading {
        val kept = REMINDER.replace(text, "").trim()
        val name = COMMAND_NAME.find(kept)?.groupValues?.get(1)
        val printed = COMMAND_STDOUT.find(kept)?.groupValues?.get(1)?.trim()
        return when {
            kept.isEmpty() -> EchoReading.Hidden
            name != null -> {
                val args = COMMAND_ARGS.find(kept)?.groupValues?.get(1)?.trim().orEmpty()
                EchoReading.Command(listOf(name, args).filter { it.isNotEmpty() }.joinToString(" "))
            }
            printed != null -> printed.takeIf { it.isNotEmpty() }?.let(EchoReading::Output) ?: EchoReading.Hidden
            else -> EchoReading.Plain(kept)
        }
    }
}
