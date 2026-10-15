// NEW: Oct 15, 2026 (the splice manager) — which session is the manager, the head it runs on, and whether the person
// agreed to it: manager.json in the state dir, written whole at 0600 so a reader never sees half of it.
package splice.sessions.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private const val MANAGER_FILE = "manager.json"

/** The manager as splice last recorded it. [sessionId] is null until a start or an adoption names one; [head] can be
 *  set before then, so the first start runs on it. [since] is when the person agreed (an ISO-8601 instant). */
@Serializable
public data class ManagerPointer(
    @SerialName("session_id") val sessionId: String?,
    val head: String?,
    val consented: Boolean,
    val since: String?,
)

/** manager.json beside the daemon's other state. */
public class ManagerPointerStore(stateDir: Path) {
    private val file: Path = stateDir.resolve(MANAGER_FILE)
    private val json = Json { ignoreUnknownKeys = true }

    /** The pointer, or null when none was written. One that does not parse reads as none: the session it named is
     *  still found by its folder and its transcripts (ManagerLookup), and the next write replaces the file whole. */
    public fun read(): ManagerPointer? {
        if (!Files.isRegularFile(file)) return null
        return Cancellables.runCatchingCancellable {
            json.decodeFromString(ManagerPointer.serializer(), Files.readString(file))
        }.fold({ it }, { null })
    }

    public fun write(pointer: ManagerPointer) {
        SecureFile.writeAtomic0600(file, json.encodeToString(ManagerPointer.serializer(), pointer))
    }

    /** [before] with his yes: [session] on [head], since the moment he first agreed. */
    internal fun agreed(before: ManagerPointer?, session: String?, head: String?): ManagerPointer =
        ManagerPointer(session, head, consented = true, since = before?.since ?: Instant.now().toString())
}
