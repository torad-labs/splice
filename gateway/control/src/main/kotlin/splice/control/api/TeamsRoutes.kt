// NEW: V4-131, FEATURES.md 4.13, 6 and 6.1 — the team routes. The read is SPLIT (6.1): there is no
// GET /api/teams/{id}; the board composes from GET /api/teams, /api/sessions (whose rows carry the
// bound team id) and the day-scoped panels (TeamReads).
//
//   GET  /api/teams                              {teams: Team[]}, archived included
//   PUT  /api/teams                              create one team under an Idempotency-Key header;
//                                                the daemon mints the id (201), a repeated key answers
//                                                the team it already made (200), and a repeated
//                                                key with a different body is refused (409)
//   PUT  /api/teams/{id}                         replace one team's composition (6.1: id in the path)
//   PUT  /api/teams/{id}/sessions                {bindings: {<slot id>: <session id> | null}}
//   PUT  /api/teams/{id}/slots/{slot}/instructions  {instructions: string | null}
//   POST /api/teams/{id}/archive                 sets the flag; nothing is ever deleted
//   GET  /api/teams/{id}/economics               lifetime turns, tokens and dollars per role and slot
//   and TeamReads' edges, chat?day= and activity?day=.
//
// ECONOMICS joins perf rows on the 8-character session tag the row stores (TurnDrive.SESSION_TAG_CHARS
// in the gateway module, which this module cannot import), over EVERY session a slot ever held
// (TeamSlot.sessionsHistory): a rebind must not erase a slot's past cost, which is the "never on the
// live registry" rule of 4.13. Rows on the team's heads that carry no session tag cannot belong to
// anyone and are COUNTED as unattributed_turns rather than dropped; the oldest turn the perf files
// still hold is named, because the files rotate and a lifetime total reaches only as far back as they
// do. Dollars use the SessionCost arithmetic per row against the head's own catalog, and are null for
// any aggregate with a turn no rate card priced: a partial sum would be a differently-wrong confident
// number.
//
// CHECKS has no source yet: every economics slot carries checks null and checks_source naming V4-159,
// the row that will fill it (CONTRACTS section 8: the honest empty names its row).
//
// UNWIRED IS NOT EMPTY: no team store answers 503 naming it. An unknown team is 404; a refused write
// or an unreadable body is 400 naming why.
//
// V4-160 (concentration, 2026-09-18): the economics moved verbatim to TeamsEconomics.kt, the day-scoped
// reads to TeamsReads.kt, and the members and edges to TeamsEdges.kt.
package splice.control.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.control.ManagedHead
import splice.core.sessions.SessionRegistry
import splice.core.teams.Team
import splice.core.teams.TeamKeyConflict
import splice.core.teams.TeamRefusal
import splice.core.teams.TeamStore
import splice.core.util.Cancellables
import splice.core.util.WallClock
import java.io.IOException

internal const val TEAMS_UNWIRED = "the team store is not wired into this control plane"
internal const val KEY_REQUIRED =
    "PUT /api/teams needs an Idempotency-Key header, so a retried create cannot make a second team"

/** The daemon's team store, read per request because ControlPlane assigns it after construction. */
internal fun interface TeamSource {
    public operator fun invoke(): TeamStore?
}

@Serializable
private data class BindBody(val bindings: Map<String, String?> = emptyMap())

@Serializable
private data class InstructionsBody(val instructions: String? = null)

@Serializable
private data class TeamsBody(val teams: List<Team>)

internal class TeamsRoutes(
    private val teams: TeamSource,
    private val heads: Map<String, ManagedHead>,
    registry: SessionRegistry?,
    activity: ActivitySource,
    texts: SentTextSource,
    clock: WallClock = WallClock(System::currentTimeMillis),
) {
    /** GET /api/teams/{id}/edges, chat?day= and activity?day=. */
    public val reads: TeamReads = TeamReads(teams, registry, activity, texts, clock)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun list(): JsonReply = withStore { store ->
        JsonReply(HttpStatusCode.OK, json.encodeToString(TeamsBody.serializer(), TeamsBody(store.teams())))
    }

    /** PUT /api/teams: a new team under [key] (the Idempotency-Key header). A body that names an id is
     *  refused toward the path form; a key already used answers its team with 200, not a second team. */
    public fun create(body: String, key: String?): JsonReply {
        var fresh = false
        val reply = write { store ->
            val team = json.decodeFromString(Team.serializer(), body)
            if (team.id.isNotEmpty()) {
                throw TeamRefusal("a new team takes no id; PUT /api/teams/${team.id} replaces one")
            }
            val (made, created) = store.create(team, key ?: throw TeamRefusal(KEY_REQUIRED))
            fresh = created
            made
        }
        return if (fresh) reply.copy(status = HttpStatusCode.Created) else reply
    }

    /** PUT /api/teams/{id}: the path names the team, whatever the body says. */
    public fun replace(id: String, body: String): JsonReply = write { store ->
        store.team(id) ?: throw TeamRefusal("$NO_SUCH_TEAM$id")
        val team = json.decodeFromString(Team.serializer(), body)
        store.upsert(team.copy(id = id, idempotencyKey = null, createFingerprint = null))
    }

    public fun bind(id: String, body: String): JsonReply = write { store ->
        store.bind(id, json.decodeFromString(BindBody.serializer(), body).bindings)
    }

    public fun instruct(id: String, slot: String, body: String): JsonReply = write { store ->
        store.instruct(id, slot, json.decodeFromString(InstructionsBody.serializer(), body).instructions)
    }

    public fun archive(id: String): JsonReply = write { store -> store.archive(id) }

    public fun economics(id: String): JsonReply = withStore { store ->
        val team = store.team(id) ?: return refuse(HttpStatusCode.NotFound, "no such team: $id")
        JsonReply(HttpStatusCode.OK, TeamEconomics(team, heads).json().toString())
    }

    /** A write answering the saved team: a refusal or an unreadable body is a 400 naming why, an
     *  unknown team (the store's refusal, prefixed [NO_SUCH_TEAM]) a 404, a reused create key with a
     *  different body a 409, and a failed disk write a 500. */
    private inline fun write(block: (TeamStore) -> Team): JsonReply =
        withStore { store ->
            Cancellables.runCatchingCancellable { block(store) }.fold(
                onSuccess = { JsonReply(HttpStatusCode.OK, json.encodeToString(Team.serializer(), it)) },
                onFailure = { failure ->
                    val status = when {
                        failure is IOException -> HttpStatusCode.InternalServerError
                        failure is TeamKeyConflict -> HttpStatusCode.Conflict
                        failure.message.orEmpty().startsWith(NO_SUCH_TEAM) -> HttpStatusCode.NotFound
                        else -> HttpStatusCode.BadRequest
                    }
                    refuse(status, failure.message ?: "the team write failed with no reason given")
                },
            )
        }

    private inline fun withStore(block: (TeamStore) -> JsonReply): JsonReply {
        val store = teams() ?: return refuse(HttpStatusCode.ServiceUnavailable, TEAMS_UNWIRED)
        return block(store)
    }

    private fun refuse(status: HttpStatusCode, message: String) =
        JsonReply(status, buildJsonObject { put("error", message) }.toString())
}

/** TeamStore's refusal prefix for an unknown team id, which the routes answer 404. */
internal const val NO_SUCH_TEAM = "no such team: "
