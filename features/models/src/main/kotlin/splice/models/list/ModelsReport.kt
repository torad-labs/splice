// NEW: V4-239 — what `splice models [provider]` reports, as data: each provider asked, where, and its
// published roster against splice.toml. The verb renders it as text (ModelsCommand) and the console
// reads it as JSON (UpstreamModelsRoute), so the two are renderings of ONE comparison and cannot
// disagree about a verdict.
package splice.models.list

import splice.core.topology.DialectWires
import splice.core.util.EnvReader

/** A declared row that needs the operator: its window overruns the model's, or the model is gone.
 *  A new upstream model is news, not a configuration fault. */
internal val rosterFaults: Set<RosterVerdict> = setOf(RosterVerdict.OVER_CEILING, RosterVerdict.UNSERVED)

/** The verdicts of a served model no row declares: discovered into the picker, or kept out of it. */
internal val rosterUndeclared: Set<RosterVerdict> = setOf(RosterVerdict.NEW, RosterVerdict.EXCLUDED)

/** One provider's side of the report. [rows] is the comparison, every declared row and then every
 *  served model no row declares; it is empty unless the roster was published. */
internal data class ProviderReport(
    val key: String,
    val dialect: String,
    val url: String,
    val roster: UpstreamRoster,
    val rows: List<RosterRow>,
) {
    /** True when this provider agrees with splice.toml: its list could be read and no declared row
     *  needs a decision. An unpublished list agrees: there is nothing to disagree with. */
    val agrees: Boolean get() = roster !is UpstreamRoster.Unreadable && rows.none { it.verdict in rosterFaults }
}

/** The report, or why there is none: no configured provider answers to the name asked for. */
internal sealed class ModelsReport {
    abstract val path: String

    data class Compared(override val path: String, val providers: List<ProviderReport>) : ModelsReport()

    data class NoSuchProvider(
        override val path: String,
        val wanted: String?,
        val declared: List<String>,
    ) : ModelsReport()
}

/** Asks each provider [ModelsReporter.report] names what it serves and compares it with splice.toml.
 *  Every call reaches the providers: a reader asks for it, nothing polls it. The credential is read
 *  inside the probe and presented to the provider only; no report carries it. */
public class ModelsReporter(
    private val configuration: ModelConfigurationSource,
    credentials: ModelCredentialSource,
) {
    private val probe = ModelsProbe(credentials = credentials)
    private val diff = RosterDiff()

    /** [wanted] is one provider's key, or null for every configured provider. */
    internal fun report(wanted: String?, env: EnvReader): ModelsReport {
        val topology = configuration.load()
        val providers = topology.providers.filterKeys { wanted == null || it == wanted }
        if (providers.isEmpty()) {
            return ModelsReport.NoSuchProvider(topology.path, wanted, topology.providers.keys.toList())
        }
        val probed = providers.map { (key, provider) -> provider(probe.probe(key, provider, env)) }
        return ModelsReport.Compared(topology.path, probed)
    }

    private fun provider(probed: ProbedProvider): ProviderReport {
        val provider = probed.provider
        val roster = probed.roster
        val rows = if (roster is UpstreamRoster.Published) {
            diff.of(provider.models, roster.models, provider.isLocal, provider.discovery)
        } else {
            emptyList()
        }
        return ProviderReport(probed.key, DialectWires.name(provider.dialect), probed.url, roster, rows)
    }
}
