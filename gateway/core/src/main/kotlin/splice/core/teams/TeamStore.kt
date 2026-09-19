// NEW: V4-131, FEATURES.md 4.13 and 6 — the operator's teams, in `teams.json` under the state dir.
//
// A TEAM IS SLOTS, NEVER PIDS. A slot is a role on a head (free-text role, a lead flag independent of
// the role's name, optional model and account, standing instructions); a session BINDS to a slot and
// the team survives every terminal restart because nothing here names a process. Composition is never
// enforced: a session may be bound in several teams, and a slot may be open.
//
// NEVER DELETED. There is no delete: archive sets a flag and every read stays available (chat, edges,
// activity, tracking). A slot removed by an upsert is the operator editing the composition, which is
// theirs to do; the team itself only ever grows an archived flag.
//
// THE FILE. One JSON document, written whole through SecureFile's temp-then-atomic-move (0600: role
// instructions are the operator's own prompts), so a crash mid-write leaves the previous version. The
// version being replaced is copied to `teams.json.bak` first, so one bad edit is one copy away. The
// daemon is the only writer; reads are served from memory and re-read when the file's modification
// time moves, so an operator's hand edit is seen without a restart. A file that does not parse is NOT
// replaced by an empty list on the next write: every write refuses until the file parses again, and
// the refusal names the file, because overwriting it would be the delete this store promises never
// to do.
//
// IDS are minted here (`team-` plus 12 hex of a random UUID) when an upsert carries none, so the
// console never invents one; slot ids are the operator's, unique within a team, and required.
//
// CREATION IS IDEMPOTENT BY KEY. A minted id means the client cannot name the team it is creating, so a
// retried create (the response lost, the request re-sent) would make a second team that looks like
// success. [create] takes the caller's idempotency key, stores it on the team, and answers a repeated
// key with the team it already made, under the same lock that makes the team. The key is kept for
// the team's life: teams are never deleted, so a key is never reused by accident.
//
// A REPEATED KEY WITH A DIFFERENT BODY IS REFUSED (TeamKeyConflict), never answered with the old team:
// that would tell a client its edited composition was created while discarding it. The comparison is
// against a FINGERPRINT of the body taken at create, not against the stored team, because the stored
// team legitimately moves on (bindings, instruction stamps, a replace) and a state comparison would
// refuse a correct replay the moment a session bound. A team with no fingerprint (written before this
// field existed) replays exactly as before and is never refused, so no record needs migrating.
//
// ORDER. bindingsOf answers by created time, then id: a session bound in two teams gets the same
// order on every read, which is what makes "the first unarchived team" a decision and not an accident
// of iteration.
package splice.core.teams

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** The state-dir file the teams live in. */
public const val TEAMS_FILE: String = "teams.json"

@Serializable
public data class TeamSlot(
    val id: String,
    val role: String,
    val head: String,
    val model: String? = null,
    val account: String? = null,
    val lead: Boolean = false,
    /** Standing instructions added to the system prompt of the session bound here (SlotInstructions). */
    val instructions: String? = null,
    /** The session bound to this slot, or null when the slot is open. */
    val session: String? = null,
    /** When [instructions] last changed, so the console can show the one cold-cache turn it costs. */
    @SerialName("instructions_updated_epoch_millis") val instructionsUpdatedAt: Long? = null,
    /** Every session ever bound here, oldest first, the current one included: team tracking joins the
     *  perf rows of all of them, so rebinding a slot never erases what it cost before (FEATURES 4.13,
     *  "never on the live registry"). */
    @SerialName("sessions_history") val sessionsHistory: List<String> = emptyList(),
)

@Serializable
public data class Team(
    val id: String = "",
    val name: String,
    val goal: String = "",
    val features: List<String> = emptyList(),
    /** The git root the team works in (the project id, FEATURES.md 4.14). */
    val repo: String = "",
    val archived: Boolean = false,
    @SerialName("created_epoch_millis") val createdAt: Long = 0L,
    @SerialName("updated_epoch_millis") val updatedAt: Long = 0L,
    val slots: List<TeamSlot> = emptyList(),
    /** The key the team was created under ([TeamStore.create]); null on a team made before keys. */
    @SerialName("idempotency_key") val idempotencyKey: String? = null,
    /** sha256 of the body the team was created from, so a replay under its key can be told from a
     *  different body reusing it; null on a team made before fingerprints. */
    @SerialName("create_fingerprint") val createFingerprint: String? = null,
)

@Serializable
private data class TeamsDocument(val teams: List<Team> = emptyList())

/** One change to one team, applied under the store's lock. */
public fun interface TeamEdit {
    public operator fun invoke(team: Team): Team
}

/** A write the store refused, with the reason the route reports. */
public class TeamRefusal(message: String) : IllegalArgumentException(message)

/** A create that reused an idempotency key with a different body; the route answers 409. */
public class TeamKeyConflict(message: String) : IllegalArgumentException(message)

public class TeamStore(
    private val file: Path,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private var cached: List<Team> = emptyList()
    private var cachedStamp: Long? = null
    private val rules = TeamRules()

    /** Every team, archived included, in the order they were created. */
    @Synchronized
    public fun teams(): List<Team> = load().getOrElse { emptyList() }

    public fun team(id: String): Team? = teams().firstOrNull { it.id == id }

    /** The teams [session] is bound in, with the slot it holds in each, by created time then id. */
    public fun bindingsOf(session: String): List<Pair<Team, TeamSlot>> =
        teams().sortedWith(compareBy({ it.createdAt }, { it.id }))
            .flatMap { team -> team.slots.filter { it.session == session }.map { team to it } }

    /** Creates [team] under [key], or answers the team a previous create with the same key made. The
     *  second value is true only when this call made the team. */
    @Synchronized
    public fun create(team: Team, key: String): Pair<Team, Boolean> {
        if (key.isBlank()) throw TeamRefusal("a create needs an idempotency key")
        val print = fingerprint(team)
        val made = writable().firstOrNull { it.idempotencyKey == key }
            ?: return upsert(team.copy(id = "", idempotencyKey = key, createFingerprint = print)) to true
        if (made.createFingerprint != null && made.createFingerprint != print) {
            throw TeamKeyConflict(
                "idempotency key $key already made ${made.id} from a different body; a new team needs a new key",
            )
        }
        return made to false
    }

    /** The body as submitted, without the fields the store assigns, hashed. */
    private fun fingerprint(team: Team): String {
        val submitted = team.copy(id = "", idempotencyKey = null, createFingerprint = null)
        val body = json.encodeToString(Team.serializer(), submitted)
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.toByteArray()))
    }

    /** Creates or replaces one team's composition. Bindings and instruction timestamps of slots that
     *  keep their id are carried over when the upsert leaves them out, so re-saving a team from the
     *  composer never unbinds a live session. */
    @Synchronized
    public fun upsert(team: Team): Team {
        rules.validate(team)
        val all = writable()
        val previous = all.firstOrNull { it.id == team.id && team.id.isNotEmpty() }
        val now = clock()
        val saved = team.copy(
            id = previous?.id ?: team.id.ifEmpty { rules.mintId() },
            archived = previous?.archived ?: team.archived,
            idempotencyKey = previous?.idempotencyKey ?: team.idempotencyKey,
            createFingerprint = previous?.createFingerprint ?: team.createFingerprint,
            createdAt = previous?.createdAt ?: now,
            updatedAt = now,
            slots = team.slots.map { slot ->
                rules.carried(slot, previous?.slots?.firstOrNull { it.id == slot.id }, now)
            },
        )
        write(if (previous == null) all + saved else all.map { if (it.id == saved.id) saved else it })
        return saved
    }

    /** Binds (or with null, unbinds) sessions to slots. A slot id the team does not have is refused. */
    @Synchronized
    public fun bind(teamId: String, bindings: Map<String, String?>): Team = change(teamId) { team ->
        val unknown = bindings.keys - team.slots.map { it.id }.toSet()
        if (unknown.isNotEmpty()) throw TeamRefusal("no such slot in ${team.id}: ${unknown.sorted().joinToString()}")
        team.copy(
            slots = team.slots.map { slot ->
                if (slot.id in bindings) rules.remembered(slot.copy(session = bindings[slot.id])) else slot
            },
        )
    }

    /** Sets one slot's standing instructions; blank clears them. */
    @Synchronized
    public fun instruct(teamId: String, slotId: String, text: String?): Team = change(teamId) { team ->
        if (team.slots.none { it.id == slotId }) throw TeamRefusal("no such slot in ${team.id}: $slotId")
        val value = text?.takeIf { it.isNotBlank() }
        team.copy(
            slots = team.slots.map { slot ->
                if (slot.id == slotId && slot.instructions != value) {
                    slot.copy(instructions = value, instructionsUpdatedAt = clock())
                } else {
                    slot
                }
            },
        )
    }

    @Synchronized
    public fun archive(teamId: String): Team = change(teamId) { it.copy(archived = true) }

    private fun change(teamId: String, edit: TeamEdit): Team {
        val all = writable()
        val team = all.firstOrNull { it.id == teamId } ?: throw TeamRefusal("no such team: $teamId")
        val saved = edit(team).copy(updatedAt = clock())
        write(all.map { if (it.id == teamId) saved else it })
        return saved
    }

    /** The current teams, or a refusal when the file exists and does not parse (see the header). */
    private fun writable(): List<Team> =
        load().getOrElse { throw TeamRefusal("$file does not parse; fix or move it first") }

    private fun write(teams: List<Team>) {
        if (Files.exists(file)) {
            SecureFile.writeAtomic0600(file.resolveSibling("${file.fileName}.bak"), Files.readString(file))
        }
        SecureFile.writeAtomic0600(file, json.encodeToString(TeamsDocument.serializer(), TeamsDocument(teams)))
        cached = teams
        cachedStamp = stamp()
    }

    private fun load(): Result<List<Team>> {
        val now = stamp()
        if (now == null) return Result.success(emptyList())
        if (now == cachedStamp) return Result.success(cached)
        return Cancellables.runCatchingCancellable {
            json.decodeFromString(TeamsDocument.serializer(), Files.readString(file)).teams
        }.onSuccess {
            cached = it
            cachedStamp = now
        }
    }

    private fun stamp(): Long? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- no file yet is the empty store, not a failure
        Cancellables.runCatchingCancellable { Files.getLastModifiedTime(file).toMillis() }.getOrNull()
}
