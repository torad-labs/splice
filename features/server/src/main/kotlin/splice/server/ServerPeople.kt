// NEW: server mode (docs/specs/server-mode.md, F3). What the operator set for one person with `splice server person
// set <email>`: for now their own in-flight and per-minute caps, which override the server's; the same record later
// carries their team and their preferred upstream. Kept in `<state>/server/people.json` and read by the limits at each
// turn, so a change holds from the person's next request.
package splice.server

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Path

/** One person's own settings. A null leaves that setting to the server's; a cap of 0 is no cap. */
@Serializable
public data class PersonSettings(val inflight: Int? = null, val rpm: Int? = null) {

    /** These settings with every one [change] names taken from it. */
    public fun with(change: PersonSettings): PersonSettings =
        PersonSettings(change.inflight ?: inflight, change.rpm ?: rpm)
}

@Serializable
internal data class PeopleFile(val people: Map<String, PersonSettings> = emptyMap())

public class ServerPeople(
    /** `<state>/server/people.json`. Read here, once; a file that is there and cannot be read throws. */
    file: Path,
    private val io: CoroutineDispatcher,
) {
    private val store = StateFile(file, PeopleFile.serializer())
    private val mutation = Mutex()

    @Volatile
    private var people: Map<String, PersonSettings> = store.read()?.people.orEmpty()

    /** What the operator set for [person], a lowercased email; every setting null for someone never set. */
    public fun of(person: String): PersonSettings = people[person] ?: PersonSettings()

    /** Sets what [change] names for [person], keeps the rest, and answers their settings as they now stand. */
    public suspend fun set(person: String, change: PersonSettings): PersonSettings = mutation.withLock {
        val settings = of(person).with(change)
        val next = people + (person to settings)
        withContext(io) { store.write(PeopleFile(next)) }
        people = next
        settings
    }
}
