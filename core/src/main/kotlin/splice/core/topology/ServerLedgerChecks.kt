// NEW: server mode (docs/specs/server-mode.md, F3 and F5). What the decode accepts in `[server.reference]`,
// `[server.teams]`, `[server.export]` and `[server.budgets]` but the daemon could not act on: a negative price, a team
// with no name, an export to nowhere it can write, a bucket with no way to sign, a budget that is no amount or no
// action. Each is a boot finding beside every other, naming its key; a key's secret is named by its variable only.
package splice.core.topology

import splice.core.config.KnobWords
import splice.core.topology.toml.TomlKeys

internal class ServerLedgerChecks(private val server: ServerConfig) {
    private val keys = TomlKeys()

    fun findings(): List<TopologyFinding> = reference() + teams() + export() + backup() + budgets()

    private fun reference(): List<TopologyFinding> {
        val card = server.reference
        val prices = mapOf(
            "input_usd_per_mtok" to card.inputUsdPerMtok,
            "cached_usd_per_mtok" to card.cachedUsdPerMtok,
            "output_usd_per_mtok" to card.outputUsdPerMtok,
        )
        return prices.filterValues { it != null && it < 0.0 }.keys.map {
            TopologyFinding("$REFERENCE.$it", "must be a price in dollars per million tokens, 0 or more")
        }
    }

    private fun teams(): List<TopologyFinding> {
        val unnamed = server.teams.byDomain.any { (domain, team) -> keys.unquote(domain).isBlank() || team.isBlank() }
        val why = "each domain needs a team, as in { \"team.example\" = \"core\" }"
        return listOfNotNull(TopologyFinding("$TABLE.teams.by_domain", why).takeIf { unnamed })
    }

    private fun export(): List<TopologyFinding> {
        val export = server.export
        if (!export.declared) return emptyList()
        return targetFindings(EXPORT, export.to, export.everyMs, export.accessKeyEnv, export.secretKeyEnv)
    }

    private fun backup(): List<TopologyFinding> {
        val backup = server.backup
        if (!backup.declared) return emptyList()
        return targetFindings(BACKUP, backup.to, backup.everyMs, backup.accessKeyEnv, backup.secretKeyEnv)
    }

    private fun targetFindings(
        prefix: String,
        rawTo: String,
        everyMs: Long?,
        accessKeyEnv: String?,
        secretKeyEnv: String?,
    ): List<TopologyFinding> {
        val target = rawTo.trim()
        val where = "must be a folder (file:///var/lib/splice-usage) or a bucket (s3://bucket/prefix)"
            .takeUnless { writable(target) }
        val every = "must be a duration, as in \"1h\" or \"15m\"".takeIf { everyMs == null }
        val signed = if (target.startsWith(S3_SCHEME)) signing(prefix, accessKeyEnv, secretKeyEnv) else emptyList()
        return listOfNotNull(
            where?.let { TopologyFinding("$prefix.to", it) },
            every?.let { TopologyFinding("$prefix.every", it) },
        ) + signed
    }

    /** Whether [target] names a folder, or a bucket, the export can write to. */
    private fun writable(target: String): Boolean = when {
        target.startsWith(FILE_SCHEME) -> target.length > FILE_SCHEME.length
        target.startsWith(S3_SCHEME) -> target.removePrefix(S3_SCHEME).substringBefore('/').isNotBlank()
        else -> false
    }

    /** A bucket is written with SigV4, so both halves of its key must come from somewhere, and never from the file. */
    private fun signing(prefix: String, accessKeyEnv: String?, secretKeyEnv: String?): List<TopologyFinding> {
        val named = "names the environment variable the bucket's %s is read from; set it, as in \"%s\""
        return listOfNotNull(
            TopologyFinding("$prefix.access_key_env", named.format("access key", "USAGE_S3_KEY"))
                .takeIf { accessKeyEnv.isNullOrBlank() },
            TopologyFinding("$prefix.secret_key_env", named.format("secret key", "USAGE_S3_SECRET"))
                .takeIf { secretKeyEnv.isNullOrBlank() },
        )
    }

    private fun budgets(): List<TopologyFinding> {
        val budgets = server.budgets
        val actions = KnobWords.BUDGET_ACTIONS.joinToString(" or ") { "\"$it\"" }
        return listOfNotNull(
            TopologyFinding("$BUDGETS.person_daily_usd", "must be an amount in dollars, more than 0")
                .takeIf { budgets.personDailyUsd?.let { it <= 0.0 } == true },
            TopologyFinding("$BUDGETS.action", "must be $actions")
                .takeUnless { budgets.action in KnobWords.BUDGET_ACTIONS },
        )
    }
}

private const val TABLE = "server"
private const val REFERENCE = "$TABLE.reference"
private const val EXPORT = "$TABLE.export"
private const val BACKUP = "$TABLE.backup"
private const val BUDGETS = "$TABLE.budgets"

/** How `[server.export] to` names a folder and a bucket. */
public const val FILE_SCHEME: String = "file://"
public const val S3_SCHEME: String = "s3://"
