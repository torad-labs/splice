// NEW: server mode (docs/specs/server-mode.md, F3 and F4). What the operator set for one person with `splice server
// person set <email>`: their own in-flight and per-minute caps, which override the server's, and the upstream their
// turns go to while it is healthy, and the team they are on (F5), which their usage is reported under. Kept in
// `<state>/server/people.json` and read at each turn, so a change holds from the person's next request.
package splice.server

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import splice.core.topology.ServerTeams
import java.nio.file.Path

/** One person's own settings. A null leaves that setting to the server's; a cap of 0 is no cap. [upstream] is the
 *  name of the server they prefer among a provider's upstreams, and "" once the operator took a preference back.
 *  [team] is the team they are on, over the one their email's domain is on, and "" once the operator took it back. */
@Serializable
public data class PersonSettings(
    val inflight: Int? = null,
    val rpm: Int? = null,
    val upstream: String? = null,
    val team: String? = null,
) {

    /** The upstream this person's turns go to while it is healthy, or null when they have none. */
    val preferred: String? get() = upstream?.takeIf(String::isNotEmpty)

    /** The team the operator put this person on, or null when they put them on none. */
    val ownTeam: String? get() = team?.takeIf(String::isNotEmpty)

    /** These settings with every one [change] names taken from it. */
    public fun with(change: PersonSettings): PersonSettings = PersonSettings(
        change.inflight ?: inflight,
        change.rpm ?: rpm,
        change.upstream ?: upstream,
        change.team ?: team,
    )
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

    /** The team [person] is on now: the one the operator set, else the one [teams] puts their domain on. */
    public fun team(person: String, teams: ServerTeams): String? = of(person).ownTeam ?: teams.ofDomain(person)

    /** Sets what [change] names for [person], keeps the rest, and answers their settings as they now stand. */
    public suspend fun set(person: String, change: PersonSettings): PersonSettings = mutation.withLock {
        val settings = of(person).with(change)
        val next = people + (person to settings)
        withContext(io) { store.write(PeopleFile(next)) }
        people = next
        settings
    }
}
