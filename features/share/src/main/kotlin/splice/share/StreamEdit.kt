// NEW: server mode (docs/specs/server-mode.md, F4, openai-route). A relayed event stream that is read on its way to
// the client. A relay passes a head's stream on byte for byte; one that answers for a provider's own server reads that
// server's stream event by event, because what one event carries decides whether its client gets it (the usage an
// OpenAI-style client did not ask for) and what the stream must end with. Each event goes to the client the moment
// the server has written it whole, so the stream is still relayed as it comes. What an event means is the editor's
// to know, which keeps a server's wire out of this module: the relay only cuts the stream where SSE ends an event.
package splice.share

/** What reads one relayed event stream, event by event. */
public interface StreamEdit {
    /** [event], one event of the server's stream without the blank line that ends it, as the client gets it: itself,
     *  another in its place, or null when the client gets none for it. */
    public fun event(event: String): String?

    /** The event a stream that ended whole is given after the server's last; null gives it none. A stream that was
     *  cut short is never given it: the relay ends that one with its own error event (HeadAnswers). */
    public fun ending(): String?
}

/** The client's side of a stream relayed through [edit]: [wire], fed the server's bytes as they are read. */
internal class EditedWire(private val wire: QuietWire, private val edit: StreamEdit) {
    // What was read past the last whole event: the start of the next one.
    private var held = ByteArray(0)

    /** The next [count] of the server's [bytes]. Every event they complete goes to the client at once. */
    suspend fun write(bytes: ByteArray, count: Int) {
        held += bytes.copyOfRange(0, count)
        while (true) {
            val blank = blankLine() ?: return
            val event = text(blank.first)
            held = held.copyOfRange(blank.last + 1, held.size)
            say(event)
        }
    }

    /** The server's stream reached its end: true when it ended whole, at the blank line that ends its last event, and
     *  then the stream is given what [edit] ends it with; false when it ended with an event that blank line never came
     *  for, a stream cut short of which nothing more is said: the relay ends it with its error event. */
    suspend fun end(): Boolean {
        val whole = text(held.size).isEmpty()
        held = ByteArray(0)
        if (whole) edit.ending()?.let { send(it) }
        return whole
    }

    private suspend fun say(event: String) {
        if (event.isNotBlank()) edit.event(event)?.let { send(it) }
    }

    private suspend fun send(event: String) {
        val bytes = "$event\n\n".toByteArray()
        wire.write(bytes, bytes.size)
    }

    /** The first [length] bytes held, as the event they are, without the line ends around it. Events are cut at line
     *  ends, so no character is split. */
    private fun text(length: Int): String = String(held, 0, length).trim('\r', '\n')

    /** The bytes of the blank line that ends the first event held (`\n\n`, or `\n\r\n` where lines end in `\r\n`), or
     *  null while that event is unfinished. */
    private fun blankLine(): IntRange? {
        for (at in 0 until held.size - 1) {
            if (held[at] != LINE_FEED) continue
            if (held[at + 1] == LINE_FEED) return at..at + 1
            if (held[at + 1] == CARRIAGE_RETURN && held.getOrNull(at + 2) == LINE_FEED) return at..at + 2
        }
        return null
    }
}
