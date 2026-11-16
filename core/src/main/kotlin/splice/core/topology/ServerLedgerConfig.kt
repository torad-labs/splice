// NEW: server mode (docs/specs/server-mode.md, F3 and F5). The `[server]` tables about what the team spends: the
// reference card a report prices the same tokens at, the teams people are on, where the ledger is copied hour by hour,
// and the budget every person is held to. Each decodes to a default that does nothing.
package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.core.config.KnobWords
import java.time.temporal.ChronoUnit

/** `[server.reference]`: what a hosted model charges per million tokens, so a report can say what the team's own
 *  box saved. All null prices nothing. */
@Serializable
public data class ServerReference(
    @Serializable(with = TomlDollars::class) @SerialName("input_usd_per_mtok") val inputUsdPerMtok: Double? = null,
    @Serializable(with = TomlDollars::class) @SerialName("cached_usd_per_mtok") val cachedUsdPerMtok: Double? = null,
    @Serializable(with = TomlDollars::class) @SerialName("output_usd_per_mtok") val outputUsdPerMtok: Double? = null,
) {
    /** Whether a card is set at all. */
    val declared: Boolean get() = inputUsdPerMtok != null || cachedUsdPerMtok != null || outputUsdPerMtok != null
}

/** `[server.teams]`: the team of each email domain, for a person the operator gave no team of their own. */
@Serializable
public data class ServerTeams(
    @SerialName("by_domain") val byDomain: Map<String, String> = emptyMap(),
) {
    // why: a domain has dots, so it is written quoted, and ktoml hands a quoted key back with its quotes.
    private val domains: Map<String, String> by lazy {
        val keys = TomlKeys()
        byDomain.mapKeys { (domain, _) -> keys.unquote(domain).trim().lowercase() }
    }

    /** The team [person]'s domain is on, or null when no domain of theirs is listed. */
    public fun ofDomain(person: String): String? = domains[person.substringAfter('@').lowercase()]
}

/** `[server.export]`: the folder (`file:///dir`) or S3-compatible bucket (`s3://bucket/prefix`) the ledger is copied
 *  to every [every], and for a bucket how it is reached and the environment variables its key is read from. */
@Serializable
public data class ServerExport(
    val to: String = "",
    val endpoint: String? = null,
    val region: String? = null,
    @SerialName("path_style") val pathStyle: Boolean = false,
    @SerialName("access_key_env") val accessKeyEnv: String? = null,
    @SerialName("secret_key_env") val secretKeyEnv: String? = null,
    val every: String = DEFAULT_EXPORT_EVERY,
) {
    /** Whether an export is set at all. */
    val declared: Boolean get() = to.isNotBlank()

    /** [every] in milliseconds, or null when it is no duration. */
    val everyMs: Long? get() = ServerDurations.read(every)
}

/** `[server.budgets]`: the daily budget every person is held to unless one of their own is set, and what reaching a
 *  budget does: `block` refuses the next turn, `warn` serves it. */
@Serializable
public data class ServerBudgets(
    @Serializable(with = TomlDollars::class) @SerialName("person_daily_usd") val personDailyUsd: Double? = null,
    val action: String = KnobWords.BUDGET_BLOCK,
)

// why: an hour is the file the export writes, so once an hour keeps each file current within its own hour.
private const val DEFAULT_EXPORT_EVERY = "1h"

/** Reads a duration as the server's settings write one: a whole number and a unit, `90s`, `45m`, `12h`, `30d`, `8w`. */
public object ServerDurations {
    private val shape = Regex("""^(\d{1,9})([smhdw])$""")
    private val units = mapOf(
        's' to ChronoUnit.SECONDS,
        'm' to ChronoUnit.MINUTES,
        'h' to ChronoUnit.HOURS,
        'd' to ChronoUnit.DAYS,
        'w' to ChronoUnit.WEEKS,
    )

    /** [text] in milliseconds, or null when it is no such duration or is zero. */
    public fun read(text: String): Long? {
        val found = shape.matchEntire(text.trim().lowercase()) ?: return null
        val unit = units.getValue(found.groupValues[2].single()).duration
        return unit.multipliedBy(found.groupValues[1].toLong()).toMillis().takeIf { it > 0 }
    }
}
