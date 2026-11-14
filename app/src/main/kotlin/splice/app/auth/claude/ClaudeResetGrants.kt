// NEW: Nov 13, 2026: what Anthropic says about a Claude login's limit resets, as Claude Code 2.1.325 reads it, and the
// sentences a person reads about them. Split from ClaudeLimitResets.kt, which reads and spends them, so the wire
// shapes and the words sit in one place and the client stays one concern.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package splice.app.auth.claude

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import java.time.Instant
import java.time.format.DateTimeParseException

// why: the one window a 5-hour reset clears; a grant that clears only it is a 5-hour reset.
private const val FIVE_HOUR_LIMIT = "five_hour"

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
internal data class ResetsDocument(@SerialName("cedar_ember") val resets: ResetsBlock? = null)

@Serializable
@JsonIgnoreUnknownKeys
internal data class ResetsBlock(
    val eligible: Boolean = false,
    @SerialName("ineligible_reason") val ineligibleReason: String? = null,
    val grants: List<ResetGrant> = emptyList(),
    @SerialName("cooldown_until") val cooldownUntil: String? = null,
)

@Serializable
@JsonIgnoreUnknownKeys
internal data class ResetGrant(
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
internal data class OrgProfile(val organization: OrgId? = null)

@Serializable
@JsonIgnoreUnknownKeys
internal data class OrgId(val uuid: String? = null)

@Serializable
@JsonIgnoreUnknownKeys
internal data class SpendAnswer(
    val result: String? = null,
    val reason: List<String> = emptyList(),
    @SerialName("resets_left") val resetsLeft: Int? = null,
    val cleared: List<String> = emptyList(),
    @SerialName("cooldown_until") val cooldownUntil: String? = null,
)

/** What a person reads about a login's resets, in one place so the list and a spend say the same thing. */
internal object ResetWords {
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
