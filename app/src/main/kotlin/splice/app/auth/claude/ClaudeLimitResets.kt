// NEW: Nov 13, 2026: a Claude login's limit resets, read and spent by splice when a person asks for one.
//
// Anthropic gives some Claude plans a few resets to spend. A Fable reset gives back the week's Fable usage and refills
// the 5-hour limit, a weekly reset refills the week, a full reset refills every limit, a 5-hour reset the 5-hour one.
// Claude Code offers them with /limit-reset. That evening every login on one machine ran out of Fable at about 6:24 PM
// CT, and the turns held for it could only wait for Sunday or for Marcos to call a reset on each login by hand.
//
// This reads a login's resets the way Claude Code 2.1.325 reads them, beside its usage, and spends one the way it does,
// on the same grant: the first of that kind that can be used now. It spends one only when a person asks for that kind
// on that login, because a reset cannot be undone. A request whose answer did not confirm the spend is asked again with
// the same request id, so asking twice never spends two.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package splice.app.auth.claude

import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.accounts.pool.LoginResetKind
import splice.accounts.pool.LoginResetSpend
import splice.accounts.pool.LoginResets
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.oauth.AuthHttpClientFactory
import splice.usage.quota.CLAUDE_OAUTH_BETA
import splice.usage.quota.ClientUserAgent
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// why: Claude Code reads a login's resets beside its usage, at the usage address with these two switches.
private const val CLAUDE_RESETS_URL = "https://api.anthropic.com/api/oauth/usage?cedar_ember=1&skip_spend=1"

// why: Claude Code spends a reset at this address, under the organization the login's profile names.
private const val CLAUDE_RESET_ORGS_URL = "https://api.anthropic.com/api/organizations/"

// why: the last part of that address.
private const val CLAUDE_RESET_SPEND_PATH = "/reset_rate_limits"

// why: the program name Claude Code sends with every spend, the same word that names the block in the usage reply.
private const val CLAUDE_RESET_PROGRAM = "cedar_ember"

// why: Claude Code gives a spend 25 seconds before it calls the answer unconfirmed.
private const val CLAUDE_RESET_TIMEOUT_MS = 25_000L

// why: the one window a 5-hour reset clears; a grant that clears only it is a 5-hour reset.
private const val FIVE_HOUR_LIMIT = "five_hour"

// why: the outcome of a spend whose answer did not say it was spent or refused; asked again with the same request id.
private const val UNCONFIRMED = "unconfirmed"

// why: what an unconfirmed spend tells the person, since a second ask is safe.
private const val ASK_AGAIN = "Asking again sends the same request, so it is never spent twice."

// why: what a spend says when the login's profile names no organization to spend the reset under.
private const val NO_ORGANIZATION = "Anthropic's profile of this login names no organization."

// why: the sentence for a login whose token Anthropic refused, said wherever a read or a spend meets it.
private const val TOKEN_REFUSED = "Anthropic refused this login's token; sign it in again."

/** The kinds a person can ask for, in Claude Code's own words for them (`/limit-reset fable`). */
internal object ResetKinds {
    const val FABLE = "fable"
    const val WEEKLY = "weekly"
    const val FULL = "full"
    const val FIVE_HOUR = "5-hour"
    val all: List<String> = listOf(FABLE, WEEKLY, FULL, FIVE_HOUR)

    // A grant's own type, where Anthropic names one, in the words a person types.
    val byType: Map<String, String> = mapOf("session" to FIVE_HOUR, "weekly" to WEEKLY, "full" to FULL)

    fun name(kind: String): String = when (kind) {
        FABLE -> "Fable reset"
        WEEKLY -> "weekly reset"
        FULL -> "full reset"
        else -> "5-hour reset"
    }
}

@Serializable
@JsonIgnoreUnknownKeys
private data class ResetsDocument(@SerialName("cedar_ember") val resets: ResetsBlock? = null)

@Serializable
@JsonIgnoreUnknownKeys
private data class ResetsBlock(
    val eligible: Boolean = false,
    @SerialName("ineligible_reason") val ineligibleReason: String? = null,
    val grants: List<ResetGrant> = emptyList(),
    @SerialName("cooldown_until") val cooldownUntil: String? = null,
)

@Serializable
@JsonIgnoreUnknownKeys
private data class ResetGrant(
    val id: String,
    @SerialName("resets_total") val resetsTotal: Int = 0,
    @SerialName("resets_left") val resetsLeft: Int = 0,
    @SerialName("ends_at") val endsAt: String? = null,
    val clears: List<String> = emptyList(),
    val paused: Boolean = false,
    @SerialName("usable_now") val usableNow: Boolean = false,
    val type: String? = null,
    @SerialName("gives_back_weekly") val givesBackWeekly: Boolean = false,
    @SerialName("unavailable_reason") val unavailableReason: String? = null,
) {
    /** Claude Code's reading of a grant's kind: a Fable reset gives the week back, and otherwise the grant's own type,
     *  or what it clears, decides. */
    val kind: String
        get() = when {
            givesBackWeekly -> ResetKinds.FABLE
            type in ResetKinds.byType -> ResetKinds.byType.getValue(type.orEmpty())
            clears.isEmpty() -> ResetKinds.FULL
            clears.all { it == FIVE_HOUR_LIMIT } -> ResetKinds.FIVE_HOUR
            clears.none { it == FIVE_HOUR_LIMIT } -> ResetKinds.WEEKLY
            else -> ResetKinds.FULL
        }

    /** Claude Code offers a grant that is not paused and has a reset left or can be used now. */
    val offered: Boolean get() = !paused && (resetsLeft > 0 || usableNow)

    /** Whether its time to be used ended before [nowMs]; a time Anthropic wrote that does not parse never ends it. */
    fun expired(nowMs: Long): Boolean {
        val ends = endsAt ?: return false
        return try {
            Instant.parse(ends).toEpochMilli() <= nowMs
        } catch (_: DateTimeParseException) {
            false
        }
    }

    fun usable(nowMs: Long): Boolean = usableNow && !expired(nowMs)
}

@Serializable
@JsonIgnoreUnknownKeys
private data class OrgProfile(val organization: OrgId? = null)

@Serializable
@JsonIgnoreUnknownKeys
private data class OrgId(val uuid: String? = null)

@Serializable
@JsonIgnoreUnknownKeys
private data class SpendAnswer(
    val result: String? = null,
    val reason: List<String> = emptyList(),
    @SerialName("resets_left") val resetsLeft: Int? = null,
    val cleared: List<String> = emptyList(),
    @SerialName("cooldown_until") val cooldownUntil: String? = null,
)

/** One login's resets as Anthropic answered, or the sentence that says why they could not be read. */
private sealed class ResetRead {
    data class Read(val block: ResetsBlock) : ResetRead()

    data class Refused(val outcome: String, val said: String) : ResetRead()
}

/** What a person reads about a login's resets, in one place so the list and a spend say the same thing. */
private object ResetWords {
    fun notOffered(reason: String?): String = when (reason) {
        "tier", "seat" -> "This login's plan has no limit resets."
        "no_grant" -> "This login has no limit resets."
        "cli_version" -> "Anthropic wants a newer Claude Code before this login's resets can be used."
        null -> "Anthropic offers this login no limit resets."
        else -> "Anthropic offers this login no limit resets now ($reason)."
    }

    /** Why none of [grants], the offered grants of [kind], can be spent at [nowMs]. */
    fun notNow(kind: String, grants: List<ResetGrant>, cooldownUntil: String?, nowMs: Long): String {
        val name = ResetKinds.name(kind)
        val reason = grants.firstNotNullOfOrNull(ResetGrant::unavailableReason)
        return when {
            grants.isEmpty() -> "This login has no $name left."
            reason == "cooldown" -> "Resets are cooling down" + until(cooldownUntil) + "."
            reason == "needs_limit" -> "A $name can be used only once this login reaches a limit it clears."
            reason == "cannot_clear" -> "A $name cannot clear the limit this login is at."
            reason?.startsWith("blocked_") == true -> "A limit this $name does not clear is used up, so it cannot help."
            grants.any { it.expired(nowMs) } -> "This login's $name has expired."
            else -> "Anthropic says it cannot be used now."
        }
    }

    fun until(at: String?): String = at?.let { " until $it" }.orEmpty()
}

/** Reads and spends a Claude login's limit resets with that login's own OAuth token. Never logs or keeps a token. */
internal class ClaudeLimitResets(
    private val userAgent: ClientUserAgent,
    private val log: LogSink,
    private val client: AuthClient = AuthClient(AuthHttpClientFactory()::create),
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val json = Json
    private val answered = setOf("reset", "already_used", "not_limited", "cooldown", "ineligible")

    // The request id of a spend whose answer did not confirm it, by organization and grant: the next ask for that grant
    // sends the same id, so Anthropic counts the two as one request (Claude Code keeps its id the same way).
    private val unconfirmed = ConcurrentHashMap<String, String>()

    /** The resets [token]'s login holds, one row per kind it has, for [label]. */
    suspend fun read(label: String, token: String): LoginResets = when (val read = fetch(token)) {
        is ResetRead.Refused -> LoginResets(label, refusal = read.said)
        is ResetRead.Read -> rows(label, read.block)
    }

    /** Spends one reset of [kind] on [token]'s login, when it has one it can use now. */
    suspend fun spend(label: String, token: String, kind: String): LoginResetSpend = when (val read = fetch(token)) {
        is ResetRead.Refused -> LoginResetSpend(label, kind, read.outcome, read.said)
        is ResetRead.Read -> spendFrom(label, token, kind, read.block)
    }

    private suspend fun spendFrom(label: String, token: String, kind: String, block: ResetsBlock): LoginResetSpend {
        val grants = block.grants.filter { it.offered && it.kind == kind }
        val target = grants.firstOrNull { it.usable(clock()) }
        val org = if (block.eligible && target != null) organization(token) else null
        return when {
            !block.eligible -> LoginResetSpend(label, kind, "ineligible", ResetWords.notOffered(block.ineligibleReason))
            target == null ->
                LoginResetSpend(label, kind, "not_now", ResetWords.notNow(kind, grants, block.cooldownUntil, clock()))
            org == null -> LoginResetSpend(label, kind, "refused", NO_ORGANIZATION)
            else -> claim(LoginResetSpend(label, kind, UNCONFIRMED, ""), token, org, target)
        }
    }

    private fun rows(label: String, block: ResetsBlock): LoginResets {
        if (!block.eligible) {
            return LoginResets(label, offered = false, notOffered = ResetWords.notOffered(block.ineligibleReason))
        }
        val now = clock()
        val kinds = block.grants.filter(ResetGrant::offered).groupBy(ResetGrant::kind).map { (kind, grants) ->
            val usable = grants.firstOrNull { it.usable(now) }
            LoginResetKind(
                kind = kind,
                name = ResetKinds.name(kind),
                left = grants.sumOf(ResetGrant::resetsLeft),
                total = grants.sumOf(ResetGrant::resetsTotal),
                usableNow = usable != null,
                notNow = if (usable == null) ResetWords.notNow(kind, grants, block.cooldownUntil, now) else null,
                endsAt = usable?.endsAt,
            )
        }
        return LoginResets(label, kinds = kinds.sortedBy { ResetKinds.all.indexOf(it.kind) })
    }

    private suspend fun fetch(token: String): ResetRead = Cancellables.runCatchingCancellable {
        client.open().use { http ->
            val response = http.get(CLAUDE_RESETS_URL) { signed(this, token, beta = true) }
            when (response.status) {
                HttpStatusCode.OK ->
                    json.decodeFromString<ResetsDocument>(response.bodyAsText()).resets
                        ?.let { ResetRead.Read(it) }
                        ?: ResetRead.Refused("not_offered", "Anthropic's usage reply for this login lists no resets.")
                else -> refusedRead(response.status)
            }
        }
    }.getOrElse { failure ->
        log("[resets] reading a login's resets failed (${failure::class.simpleName})\n")
        ResetRead.Refused("failed", "Anthropic could not be reached for this login's resets.")
    }

    private fun refusedRead(status: HttpStatusCode): ResetRead.Refused = when (status) {
        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> ResetRead.Refused("refused", TOKEN_REFUSED)
        HttpStatusCode.TooManyRequests ->
            ResetRead.Refused("rate_limited", "Anthropic asked splice to read this login less often; try in a minute.")
        else -> ResetRead.Refused("failed", "Anthropic answered HTTP ${status.value} when its resets were read.")
    }

    private suspend fun organization(token: String): String? = Cancellables.runCatchingCancellable {
        client.open().use { http ->
            val response = http.get(CLAUDE_PROFILE_URL) { signed(this, token, beta = false) }
            if (response.status != HttpStatusCode.OK) return@use null
            json.decodeFromString<OrgProfile>(response.bodyAsText()).organization?.uuid?.takeIf(String::isNotBlank)
        }
    }.getOrElse { failure ->
        log("[resets] reading a login's organization failed (${failure::class.simpleName})\n")
        null
    }

    /** Spends [grant] for the login and kind [asked] names, under [org]. */
    private suspend fun claim(asked: LoginResetSpend, token: String, org: String, grant: ResetGrant): LoginResetSpend {
        val key = "$org:${grant.id}"
        val requestId = unconfirmed[key] ?: UUID.randomUUID().toString()
        val body = buildJsonObject {
            put("program", CLAUDE_RESET_PROGRAM)
            put("grant_id", grant.id)
            put("request_id", requestId)
        }.toString()
        val answer = Cancellables.runCatchingCancellable {
            client.open().use { http ->
                val response = http.post(CLAUDE_RESET_ORGS_URL + org + CLAUDE_RESET_SPEND_PATH) {
                    signed(this, token, beta = true)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    timeout { requestTimeoutMillis = CLAUDE_RESET_TIMEOUT_MS }
                }
                ResetClaim(response.status, response.bodyAsText())
            }
        }.getOrElse { failure ->
            // A spend whose answer never came may have been spent: said as unconfirmed, and asked again with this id.
            log("[resets] a spend request got no answer (${failure::class.simpleName})\n")
            null
        }
        val spend = spent(asked, answer)
        if (spend.outcome == UNCONFIRMED) unconfirmed[key] = requestId else unconfirmed.remove(key)
        return spend
    }

    private fun spent(asked: LoginResetSpend, claim: ResetClaim?): LoginResetSpend {
        val status = claim?.status
        return when {
            claim == null || status == null -> asked.copy(said = "Anthropic's answer did not arrive. $ASK_AGAIN")
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
                asked.copy(outcome = "refused", said = TOKEN_REFUSED)
            status == HttpStatusCode.TooManyRequests ->
                asked.copy(outcome = "rate_limited", said = "Anthropic asked to wait before another reset request.")
            status != HttpStatusCode.OK -> asked.copy(said = "Anthropic answered HTTP ${status.value}. $ASK_AGAIN")
            else -> answeredSpend(asked, claim.body)
        }
    }

    /** Anthropic's answer to a spend; one that does not parse reads as no result, so it is said as unconfirmed. */
    private fun spendAnswer(body: String): SpendAnswer = try {
        json.decodeFromString<SpendAnswer>(body)
    } catch (_: SerializationException) {
        SpendAnswer()
    }

    private fun answeredSpend(asked: LoginResetSpend, body: String): LoginResetSpend {
        val answer = spendAnswer(body)
        val name = ResetKinds.name(asked.kind)
        val result = answer.result?.takeIf { it in answered } ?: UNCONFIRMED
        val said = when (result) {
            "reset" -> "Spent a $name." + answer.resetsLeft?.let { " $it left." }.orEmpty()
            "already_used" -> "Anthropic says that $name was already used."
            "not_limited" -> "Anthropic did not spend it: this login is not at a limit a $name clears."
            "cooldown" ->
                "Anthropic did not spend it: resets are cooling down" + ResetWords.until(answer.cooldownUntil) + "."
            "ineligible" ->
                "Anthropic did not spend it. " + ResetWords.notOffered(answer.reason.firstOrNull())
            else -> "Anthropic did not confirm the reset. $ASK_AGAIN"
        }
        return asked.copy(outcome = result, said = said, left = answer.resetsLeft, cleared = answer.cleared)
    }

    private fun signed(request: HttpRequestBuilder, token: String, beta: Boolean) {
        request.header("Authorization", "Bearer $token")
        if (beta) request.header("anthropic-beta", CLAUDE_OAUTH_BETA)
        request.header("Accept", "application/json")
        userAgent.latest()?.let { request.header("User-Agent", it) }
    }

    private data class ResetClaim(val status: HttpStatusCode, val body: String)
}
