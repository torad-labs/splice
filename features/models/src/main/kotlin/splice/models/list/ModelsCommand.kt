// PORT-OF: app/cli/models/ModelsCommand.kt — report the complete provider roster without mutating configuration.
package splice.models.list

import splice.core.terminal.BOLD
import splice.core.terminal.CYAN
import splice.core.terminal.DIM
import splice.core.terminal.GREEN
import splice.core.terminal.RED
import splice.core.terminal.RESET
import splice.core.terminal.YELLOW
import splice.core.topology.DialectWires
import splice.core.util.EnvReader

// why: long model identifiers extend the column rather than being truncated.
private const val ID_PAD = 30

// why: nine digits align every declared context window up to a 100M-token ceiling.
private const val WINDOW_PAD = 9

/** Undeclared upstream models shown by default; the remainder is counted and --all reveals it. */
private const val NEW_SHOWN = 8
private const val ALL_FLAG = "--all"

/** A new upstream model is news, not a configuration fault. */
private val FAULTS = setOf(RosterVerdict.OVER_CEILING, RosterVerdict.UNSERVED)

/** Compare configured model rows with their providers, reporting every declared row. */
public class ModelsCommand(
    private val configuration: ModelConfigurationSource,
    credentials: ModelCredentialSource,
    private val output: ModelReportOutput,
) {
    private val probe = ModelsProbe(credentials = credentials)
    private val diff = RosterDiff()

    /** True when every provider that answered agrees with splice.toml. */
    public fun models(args: List<String>, env: EnvReader): Boolean {
        val wanted = args.firstOrNull { !it.startsWith("-") }
        val topology = configuration.load()
        val providers = topology.providers.filterKeys { wanted == null || it == wanted }
        if (providers.isEmpty()) {
            output.line("splice: no provider '$wanted' in ${topology.path}")
            output.line("  ${DIM}declared:$RESET ${topology.providers.keys.joinToString(", ")}")
            return false
        }
        output.line("${BOLD}splice models$RESET $DIM— what each provider serves, against splice.toml$RESET")
        output.line(
            "  $DIM$GREEN✓$RESET$DIM declared and served   $CYAN·$RESET$DIM this row caps a larger ceiling   " +
                "$RED✗$RESET$DIM needs a decision   $YELLOW+$RESET$DIM served, declared by no row$RESET",
        )
        val all = args.contains(ALL_FLAG)
        return providers.map { (key, provider) -> report(probe.probe(key, provider, env), all) }.all { it }
    }

    private fun report(probed: ProbedProvider, all: Boolean): Boolean {
        val dialect = DialectWires.name(probed.provider.dialect)
        output.line("")
        output.line("  $BOLD${probed.key}$RESET $DIM$dialect · ${probed.url ?: probed.provider.baseUrl}$RESET")
        return when (val roster = probed.roster) {
            is UpstreamRoster.Unpublished -> true.also { output.line("    $DIM–$RESET ${roster.reason}") }
            is UpstreamRoster.Unreadable -> false.also { output.line("    $RED✗$RESET ${roster.detail}") }
            is UpstreamRoster.Published ->
                rows(diff.of(probed.provider.models, roster.models, probed.provider.isLocal), all)
        }
    }

    /** Every declared row, then the displayed undeclared rows and an explicit remainder count. */
    private fun rows(rows: List<RosterRow>, all: Boolean): Boolean {
        val (fresh, declared) = rows.partition { it.verdict == RosterVerdict.NEW }
        val shown = if (all) fresh else fresh.take(NEW_SHOWN)
        (declared + shown).forEach(::line)
        if (shown.size < fresh.size) {
            val more = "… and ${fresh.size - shown.size} more — `splice models <provider> $ALL_FLAG`"
            output.line("    $YELLOW+$RESET $DIM$more$RESET")
        }
        val faults = declared.count { it.verdict in FAULTS }
        output.line(
            "    $DIM${declared.size} declared · ${fresh.size} served upstream and not declared · " +
                "$faults need a decision$RESET",
        )
        return faults == 0
    }

    /** Undeclared rows share the legend; declared rows retain their specific diagnostic. */
    private fun line(row: RosterRow) {
        val window = (row.declaredWindow ?: row.upstreamWindow)?.toString().orEmpty()
        val note = if (row.verdict == RosterVerdict.NEW) "" else row.note
        val tail = if (note.isEmpty()) "" else "  $DIM$note$RESET"
        output.line("    ${glyph(row.verdict)} ${row.id.padEnd(ID_PAD)} ${window.padStart(WINDOW_PAD)}$tail".trimEnd())
    }

    private fun glyph(verdict: RosterVerdict): String = when (verdict) {
        RosterVerdict.SERVED -> "$GREEN✓$RESET"
        RosterVerdict.CAPPED -> "$CYAN·$RESET"
        RosterVerdict.OVER_CEILING -> "$RED✗$RESET"
        RosterVerdict.UNSERVED -> "$RED✗$RESET"
        RosterVerdict.NEW -> "$YELLOW+$RESET"
    }
}
