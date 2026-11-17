// NEW: server mode (docs/specs/server-mode.md, F3, F4 and F12). What the operator set for one person with `splice
// server person set <email>`: their own in-flight, per-minute and token caps, which override the server's, the upstream
// their turns go to while it is healthy, the team they are on (F5), which their usage is reported under, and the
// commands they may use (F12); and what the operator set for a team with `splice server team set <team>`: the commands
// its people may use (F12). Kept in `<state>/server/people.json` and read at each turn, so a change holds from the
// person's next request.
package splice.server

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import splice.core.topology.ServerTeams
import splice.share.StateFile
import java.nio.file.Path

/** One person's own settings. A null leaves that setting to the server's; an in-flight or per-minute cap of 0 is no
 *  cap, and a token cap is above 0. [upstream] is the name of the server they prefer among a provider's upstreams,
 *  and "" once the operator took a preference back. [team] is the team they are on, over the one their email's
 *  domain is on, and "" once the operator took it back. [commands] are the commands they may use, over their team's. */
@Serializable
public data class PersonSettings(
    val inflight: Int? = null,
    val rpm: Int? = null,
    val tpm: Int? = null,
    val upstream: String? = null,
    val team: String? = null,
    val commands: CommandGrant? = null,
) {

    /** The upstream this person's turns go to while it is healthy, or null when they have none. */
    val preferred: String? get() = upstream?.takeIf(String::isNotEmpty)

    /** The team the operator put this person on, or null when they put them on none. */
    val ownTeam: String? get() = team?.takeIf(String::isNotEmpty)

    /** These settings with every one [change] names taken from it. */
    public fun with(change: PersonSettings): PersonSettings = PersonSettings(
        change.inflight ?: inflight,
        change.rpm ?: rpm,
        change.tpm ?: tpm,
        change.upstream ?: upstream,
        change.team ?: team,
        change.commands ?: commands,
    )
}

/** One team's settings: the commands its people may use, or null when the operator set none for it. */
@Serializable
public data class TeamSettings(val commands: List<String>? = null)

@Serializable
internal data class PeopleFile(
    val people: Map<String, PersonSettings> = emptyMap(),
    val teams: Map<String, TeamSettings> = emptyMap(),
)

public class ServerPeople(
    /** `<state>/server/people.json`. Read here, once; a file that is there and cannot be read throws. */
    file: Path,
    private val io: CoroutineDispatcher,
) {
    private val store = StateFile(file, PeopleFile.serializer())
    private val mutation = Mutex()

    @Volatile
    private var settings: PeopleFile = store.read() ?: PeopleFile()

    /** What the operator set for [person], a lowercased email; every setting null for someone never set. */
    public fun of(person: String): PersonSettings = settings.people[person] ?: PersonSettings()

    /** What the operator set for [team]; every setting null for a team never set. */
    public fun ofTeam(team: String): TeamSettings = settings.teams[team] ?: TeamSettings()

    /** The team [person] is on now: the one the operator set, else the one [teams] puts their domain on. */
    public fun team(person: String, teams: ServerTeams): String? = of(person).ownTeam ?: teams.ofDomain(person)

    /** Sets what [change] names for [person], keeps the rest, and answers their settings as they now stand. */
    public suspend fun set(person: String, change: PersonSettings): PersonSettings = mutation.withLock {
        val now = of(person).with(change)
        write(settings.copy(people = settings.people + (person to now)))
        now
    }

    /** Sets [team]'s settings to [now], and answers them. */
    public suspend fun setTeam(team: String, now: TeamSettings): TeamSettings = mutation.withLock {
        write(settings.copy(teams = settings.teams + (team to now)))
        now
    }

    private suspend fun write(next: PeopleFile) {
        withContext(io) { store.write(next) }
        settings = next
    }
}
