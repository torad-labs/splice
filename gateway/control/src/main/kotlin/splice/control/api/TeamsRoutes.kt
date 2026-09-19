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
package splice.control.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.control.ManagedHead
import splice.control.PerfRow
import splice.core.activity.ActivityStores
import splice.core.activity.MessageEdge
import splice.core.model.ModelCatalog
import splice.core.model.TokenBuckets
import splice.core.model.TokenCost
import splice.core.perf.PerfKeys
import splice.core.sessions.SentTexts
import splice.core.sessions.SessionRecord
import splice.core.sessions.SessionRegistry
import splice.core.teams.Team
import splice.core.teams.TeamKeyConflict
import splice.core.teams.TeamRefusal
import splice.core.teams.TeamSlot
import splice.core.teams.TeamStore
import splice.core.util.Cancellables
import splice.core.util.WallClock
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

internal const val TEAMS_UNWIRED = "the team store is not wired into this control plane"
internal const val KEY_REQUIRED =
    "PUT /api/teams needs an Idempotency-Key header, so a retried create cannot make a second team"
internal const val CHECKS_SOURCE = "V4-159"
private const val ROLE = "role"
private const val TEAM_ID = "team_id"

/** The perf row's session tag width (TurnDrive.SESSION_TAG_CHARS). */
private const val PERF_TAG_CHARS = 8

/** The daemon's team store, read per request because ControlPlane assigns it after construction. */
public fun interface TeamSource {
    public operator fun invoke(): TeamStore?
}

@Serializable
private data class BindBody(val bindings: Map<String, String?> = emptyMap())

@Serializable
private data class InstructionsBody(val instructions: String? = null)

@Serializable
private data class TeamsBody(val teams: List<Team>)

public class TeamsRoutes(
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
private const val NO_SUCH_TEAM = "no such team: "

/** Lifetime economics for one team over the perf files of every head its slots name. */
internal class TeamEconomics(private val team: Team, private val heads: Map<String, ManagedHead>) {
    private val slotOfTag = team.slots
        .flatMap { slot -> held(slot).map { it.take(PERF_TAG_CHARS) to slot } }
        .distinctBy { it.first }
        .toMap()
    private val bySlot = team.slots.associate { it.id to PerfTally() }
    private val byRole = team.slots.map { it.role }.distinct().associateWith { PerfTally() }
    private var unattributed = 0
    private var oldest: Long? = null

    fun json(): JsonObject {
        val read = team.slots.map { it.head }.distinct().filter { it in heads }
        read.forEach { tally(heads.getValue(it)) }
        return buildJsonObject {
            put(TEAM_ID, team.id)
            put("heads_read", buildJsonArray { read.forEach { add(JsonPrimitive(it)) } })
            put("unattributed_turns", unattributed)
            put("oldest_turn_epoch_millis", oldest)
            put("roles", buildJsonArray { byRole.forEach { (role, tally) -> add(tally.json { it.put(ROLE, role) }) } })
            put(
                "slots",
                buildJsonArray {
                    bySlot.forEach { (slot, tally) ->
                        add(
                            tally.json {
                                it.put("slot", slot)
                                it.put("checks", JsonNull)
                                it.put("checks_source", CHECKS_SOURCE)
                            },
                        )
                    }
                },
            )
        }
    }

    /** Every session [slot] ever held, the current one included. */
    private fun held(slot: TeamSlot): List<String> = slot.sessionsHistory + listOfNotNull(slot.session)

    private fun tally(head: ManagedHead) {
        val window = head.perfRows?.window(0L) ?: return
        oldest = listOfNotNull(oldest, window.oldestHeldTs ?: window.rows.minOfOrNull { it.ts }).minOrNull()
        for (row in window.rows) {
            if (row.session == null) unattributed += 1
            row.session?.let(slotOfTag::get)?.let { slot ->
                bySlot.getValue(slot.id).add(row, head.catalog)
                byRole.getValue(slot.role).add(row, head.catalog)
            }
        }
    }
}

/** How a tally's JSON names the aggregate it covers (a role, a slot, a project). */
internal fun interface TallyLabel {
    operator fun invoke(into: JsonObjectBuilder)
}

/** Turns, token buckets and dollars over perf rows, each row priced against its own head's catalog
 *  with the SessionCost arithmetic. Dollars are null while any counted turn had no rate card. */
internal class PerfTally(private val cost: TokenCost = TokenCost()) {
    var turns: Long = 0L
        private set
    private var unpriced = 0L
    private var input = 0L
    private var cacheRead = 0L
    private var cacheWrite = 0L
    private var output = 0L
    private var usd = 0.0

    /** The newest counted turn, or null before the first. */
    var lastAt: Long? = null
        private set

    /** The priced total, or null while any counted turn had no rate card. */
    val costUsd: Double? get() = usd.takeIf { unpriced == 0L }

    fun add(row: PerfRow, catalog: ModelCatalog?) {
        val cached = row.fields[PerfKeys.CACHED_TOKENS] ?: 0L
        val written = row.fields[PerfKeys.CACHE_WRITE_TOKENS] ?: 0L
        // IN_TOKENS is inclusive of both cache buckets (SessionCost.bucketsFor says why), floored at 0.
        val buckets = TokenBuckets(
            input = ((row.fields[PerfKeys.IN_TOKENS] ?: 0L) - cached - written).coerceAtLeast(0L),
            cacheRead = cached,
            cacheWrite = written,
            output = row.fields[PerfKeys.OUT_TOKENS] ?: 0L,
        )
        turns += 1
        input += buckets.input
        cacheRead += buckets.cacheRead
        cacheWrite += buckets.cacheWrite
        output += buckets.output
        lastAt = maxOf(lastAt ?: row.ts, row.ts)
        val rates = rates(row.model, catalog)
        if (rates == null) unpriced += 1 else usd += cost.of(buckets, rates)
    }

    fun json(label: TallyLabel): JsonObject = buildJsonObject {
        label(this)
        put("turns", turns)
        put(
            "tokens",
            buildJsonObject {
                put("input", input)
                put("cache_read", cacheRead)
                put("cache_write", cacheWrite)
                put("output", output)
            },
        )
        put("cost_usd", costUsd)
        put("unpriced_turns", unpriced)
        put("last_turn_at_epoch_millis", lastAt)
    }

    /** The provider model entry's rates, keyed on the canonical id as SessionCost keys them. */
    private fun rates(model: String?, catalog: ModelCatalog?) = catalog?.let { c ->
        model?.let(c::stripSuffixes)?.let { key ->
            cost.ratesFor(null, key, c.models.firstOrNull { c.stripSuffixes(it.id) == key }?.rates)
        }
    }
}

internal const val PACKET_NOTE = "no wire source: a SendMessage call carries no dispatch unit"
internal const val ACTIVITY_SAMPLE_NOTE = "labels are samples: one per activity side query the client sends, " +
    "about every 30 seconds while a session works; a gap is a session that sent none"
private const val DAY_MS = 86_400_000L
private const val DIRECTION_INTERNAL = "internal"
private const val DIRECTION_OUT = "out"
private const val DIRECTION_IN = "in"

/** A sender's SendMessage texts by tool_use id (TranscriptReader.sentTexts), a seam for tests. */
public fun interface SentTextSource {
    public fun read(session: String, head: String?, ids: Set<String>): SentTexts
}

/** A day-scoped panel's body, given the team, the stores and the day's start. */
internal fun interface DayPanel {
    fun body(team: Team, stores: ActivityStores, dayStart: Long): JsonObject
}

/** The team reads over the activity stores. A MEMBER is every session a slot ever held, as in the
 *  economics. CHAT TEXT is read on demand from the SENDER's transcript (TranscriptReader.sentTexts);
 *  the edge store never holds it, and a message whose text was not found carries text null and a
 *  missing_reason naming the path read, never an empty string. `packet` has no wire source, so every
 *  message carries null and the payload says why (6.1: the honest empty, never an invented value).
 *  Days are UTC calendar dates, today when the query omits one. Unwired stores answer 503. */
public class TeamReads internal constructor(
    private val teams: TeamSource,
    private val registry: SessionRegistry?,
    private val activity: ActivitySource,
    private val texts: SentTextSource,
    private val clock: WallClock,
) {
    public fun edges(id: String): JsonReply = daily(id, null) { team, stores, _ ->
        val members = Members(team, registry?.read().orEmpty())
        buildJsonObject {
            put(TEAM_ID, team.id)
            put("edges", buildJsonArray { members.edges(stores.edges.edges()).forEach { add(it.json()) } })
        }
    }

    public fun chat(id: String, day: String?): JsonReply = daily(id, day) { team, stores, start ->
        val members = Members(team, registry?.read().orEmpty())
        val today = members.edges(stores.edges.edges()).filter { it.edge.at in start until start + DAY_MS }
        val found = today.groupBy { it.edge.from }.mapValues { (from, sent) ->
            texts.read(from, members.headOf(from), sent.map { it.edge.id }.toSet())
        }
        buildJsonObject {
            put(TEAM_ID, team.id)
            put("day_start_epoch_millis", start)
            put("packet_note", PACKET_NOTE)
            put("messages", buildJsonArray { today.forEach { add(it.message(found[it.edge.from])) } })
        }
    }

    public fun activity(id: String, day: String?): JsonReply = daily(id, day) { team, stores, start ->
        val slots = Members(team, emptyList()).slotOfSession
        val rows = slots.keys.flatMap { stores.activity.rows(it) }.filter { it.at in start until start + DAY_MS }
        buildJsonObject {
            put(TEAM_ID, team.id)
            put("day_start_epoch_millis", start)
            put("sample_interval_note", ACTIVITY_SAMPLE_NOTE)
            put("upstream_label_queries", rows.count { it.upstream })
            put(
                "entries",
                buildJsonArray {
                    rows.filter { it.label != null }.sortedBy { it.at }.forEach { row ->
                        val label = row.label.orEmpty()
                        add(
                            buildJsonObject {
                                put("at", row.at)
                                put("session", row.session)
                                put("slot", slots[row.session]?.id)
                                put("head", row.head)
                                put("label", label.substringBefore('\n'))
                                put("detail", label.substringAfter('\n', "").trim().takeIf { it.isNotEmpty() })
                            },
                        )
                    }
                },
            )
        }
    }

    /** One team read: the store, the team and the activity stores, each a named refusal when absent.
     *  [day] is a UTC date, today when null; anything else is a 400. */
    private fun daily(id: String, day: String?, panel: DayPanel): JsonReply {
        val today = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).toLocalDate()
        val date = if (day == null) today else parseDay(day)
        val store = teams()
        val team = store?.team(id)
        val stores = activity()
        val body = date?.let { d ->
            val start = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            team?.let { t -> stores?.let { panel.body(t, it, start) } }
        }
        return body?.let { JsonReply(HttpStatusCode.OK, it.toString()) } ?: refusal(id, day, store)
    }

    /** Why [daily] had nothing to answer, first cause first. */
    private fun refusal(id: String, day: String?, store: TeamStore?): JsonReply {
        val (status, message) = when {
            day != null && parseDay(day) == null -> HttpStatusCode.BadRequest to "day is a UTC date, YYYY-MM-DD: $day"
            store == null -> HttpStatusCode.ServiceUnavailable to TEAMS_UNWIRED
            store.team(id) == null -> HttpStatusCode.NotFound to "$NO_SUCH_TEAM$id"
            else -> HttpStatusCode.ServiceUnavailable to EDGES_UNWIRED
        }
        return JsonReply(status, buildJsonObject { put("error", message) }.toString())
    }

    /** Null for anything that is not a calendar date: the caller answers it with a 400 naming it.
     *  DateTimeParseException is not one of the kinds runCatchingCancellable folds, so it is caught here. */
    private fun parseDay(day: String): LocalDate? =
        try {
            LocalDate.parse(day)
        } catch (_: DateTimeParseException) {
            null
        }
}

/** One team edge: the stored edge with its `to` resolved, its direction, and the slots at each end. */
internal class TeamEdge(
    val edge: MessageEdge,
    val direction: String,
    val fromSlot: TeamSlot?,
    val toSlot: TeamSlot?,
    val fromHead: String?,
) {
    fun json(): JsonObject = buildJsonObject {
        put("from", edge.from)
        put("to", edge.to)
        put("at", edge.at)
        put("direction", direction)
        put("from_slot", fromSlot?.id)
        put("to_slot", toSlot?.id)
    }

    /** The chat line, with the text [sent] found for this call or the reason it has none. */
    fun message(sent: SentTexts?): JsonObject = buildJsonObject {
        put("at", edge.at)
        put("from", edge.from)
        put("from_slot", fromSlot?.id)
        put("from_head", fromHead)
        put("to", edge.to)
        put("to_slot", toSlot?.id)
        put("packet", JsonNull)
        val text = sent?.texts?.get(edge.id)
        put("text", text)
        put("text_source", sent?.path?.takeIf { text != null })
        put("missing_reason", if (text == null) missingReason(sent) else null)
    }

    private fun missingReason(sent: SentTexts?): String = when {
        sent == null -> "no transcript lookup ran"
        sent.path == null -> "no transcript for the sender in " + sent.searched.joinToString()
        else -> "the call is not in ${sent.path}"
    }
}

/** A team's members: every session its slots ever held, and the addresses the registry knows for
 *  them, so an edge's `to` (an address or a session name) is matched to a slot. */
internal class Members(team: Team, private val records: List<SessionRecord>) {
    val slotOfSession: Map<String, TeamSlot> = team.slots
        .flatMap { slot -> (slot.sessionsHistory + listOfNotNull(slot.session)).map { it to slot } }
        .distinctBy { it.first }
        .toMap()
    private val addressOfName: Map<String, String> = records
        .mapNotNull { record -> record.name?.let { name -> record.address?.let { name to it } } }
        .distinctBy { it.first }
        .toMap()
    private val slotOfAddress: Map<String, TeamSlot> = records
        .mapNotNull { record ->
            val slot = record.sessionId?.let(slotOfSession::get)
            record.address?.let { address -> slot?.let { address to it } }
        }
        .toMap()

    /** The session's head: the registry's word, else its slot's. */
    fun headOf(session: String): String? =
        records.firstOrNull { it.sessionId == session }?.head ?: slotOfSession[session]?.head

    /** The edges touching a member, oldest first, `to` resolved to an address where the registry knows
     *  the name. An edge between two non-members is not the team's. */
    fun edges(all: List<MessageEdge>): List<TeamEdge> = all.mapNotNull { stored ->
        val edge = stored.copy(to = addressOfName[stored.to] ?: stored.to)
        val from = slotOfSession[edge.from]
        val to = slotOfAddress[edge.to] ?: slotOfSession[edge.to]
        direction(from, to)?.let { TeamEdge(edge, it, from, to, headOf(edge.from)) }
    }.sortedBy { it.edge.at }

    private fun direction(from: TeamSlot?, to: TeamSlot?): String? = when {
        from != null && to != null -> DIRECTION_INTERNAL
        from != null -> DIRECTION_OUT
        to != null -> DIRECTION_IN
        else -> null
    }
}
