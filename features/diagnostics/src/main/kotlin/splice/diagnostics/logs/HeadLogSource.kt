// PORT-OF: ManagedHead.kt — the head's log tail read (file truth), split out so the record that composes
// a head names each capability's source from its own file.
package splice.diagnostics.logs

/** Reads the head's log tail (file truth). */
public interface HeadLogSource {
    public fun tail(lines: Int): String

    /** [tail]'s lines, oldest first with no blank lines: what the log routes send. The default cuts [tail]'s text as
     *  the routes did, so a source that holds its lines already (LogFileSource) answers them without a join and a
     *  split, and one that knows only its text answers the same lines. */
    public fun tailLines(lines: Int): List<String> = tail(lines).split("\n").filter { it.isNotEmpty() }

    /** The log file path — /api/logs reports it (webui LogsPayload.path). */
    public fun path(): String
}
