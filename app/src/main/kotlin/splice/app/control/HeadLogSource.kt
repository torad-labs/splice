// PORT-OF: ManagedHead.kt — the head's log tail read (file truth), split out so the record that composes
// a head names each capability's source from its own file.
package splice.app.control

/** Reads the head's log tail (file truth). */
public interface HeadLogSource {
    public fun tail(lines: Int): String

    /** The log file path — /api/logs reports it (webui LogsPayload.path). */
    public fun path(): String
}
