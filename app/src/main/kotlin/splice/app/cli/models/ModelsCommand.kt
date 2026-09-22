// NEW: 2026-09-22 — `splice models [provider]`: what each provider ACTUALLY serves, beside the rows
// splice.toml declares for it.
//
// THE VERB EXISTS BECAUSE THE ROSTER WAS ONLY EVER HAND-AUTHORED. Adding grok-4.7 took reading xAI's
// release notes; the endpoint had been listing it for twenty days. `splice add` already fetched a
// model list, but only as a yes/no check that the ids someone typed were real — it could not report
// an id nobody typed, which is the entire question.
//
// IT REPORTS; IT DOES NOT WRITE. splice.toml's rows carry decisions no endpoint can supply — catalog
// ORDER is the slot assignment, a `[1m]` row is a splice spelling, a window under the ceiling is a
// deliberate cap — so this prints what an operator would need to decide and leaves the file alone.
// `splice add-model` is the verb that writes.
//
// IT CAN FAIL, AND THAT IS THE POINT. A declared window ABOVE the published ceiling and a row the
// endpoint no longer lists are config faults this verb just PROVED, so they exit nonzero rather than
// scrolling past in green. :app is println-exempt.
package splice.app.cli.models

import splice.app.cli.BOLD
import splice.app.cli.CYAN
import splice.app.cli.DIM
import splice.app.cli.GREEN
import splice.app.cli.RED
import splice.app.cli.RESET
import splice.app.cli.YELLOW
import splice.app.daemon.TopologyLoader
import splice.core.model.RosterDiff
import splice.core.model.RosterRow
import splice.core.model.RosterVerdict
import splice.core.model.UpstreamRoster
import splice.core.topology.DialectWires
import splice.core.util.EnvReader

// why: the id column is padded to the longest id that still reads as a column rather than a wrap —
// an aggregator's vendor-prefixed ids ("inclusionai/ling-3.0-flash-vl:free") run past any width, and
// a row that overruns pushes its own window right instead of truncating an id the operator must be
// able to copy into splice.toml verbatim.
private const val ID_PAD = 30

// why: nine digits right-aligns every window splice can declare, up to a 100M-token ceiling, so the
// numbers form a column an eye can compare without reading them.
private const val WINDOW_PAD = 9

/** How many undeclared upstream models are printed before the rest become a counted line.
 *
 *  A CAP, NOT A FILTER, AND IT NAMES WHAT IT WITHHELD. An aggregator publishes hundreds of models
 *  (openrouter listed 300+ on 2026-09-22) and printing all of them buries the four rows the operator
 *  actually declared. The count of the remainder is always printed and `--all` prints them, so the
 *  denominator is never hidden — what is hidden is only ever scrolling. */
private const val NEW_SHOWN = 8

/** Print every undeclared upstream model rather than the first [NEW_SHOWN]. */
private const val ALL_FLAG = "--all"

/** The two verdicts that are CONFIG FAULTS this verb just proved — a window the endpoint will not
 *  honor, and a row it will refuse. A NEW model is news, not a fault: an operator is allowed to not
 *  want a model. */
private val FAULTS = setOf(RosterVerdict.OVER_CEILING, RosterVerdict.UNSERVED)

internal class ModelsCommand(
    private val probe: ModelsProbe = ModelsProbe(),
    private val diff: RosterDiff = RosterDiff(),
) {

    /** True when every provider that answered agrees with splice.toml. */
    fun models(args: List<String> = emptyList(), env: EnvReader = EnvReader(System::getenv)): Boolean {
        val wanted = args.firstOrNull { !it.startsWith("-") }
        val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath())
        val providers = topology.providers.filterKeys { wanted == null || it == wanted }
        if (providers.isEmpty()) {
            println("splice: no provider '$wanted' in ${TopologyLoader.configPath()}")
            println("  ${DIM}declared:$RESET ${topology.providers.keys.joinToString(", ")}")
            return false
        }
        println("${BOLD}splice models$RESET $DIM— what each provider serves, against splice.toml$RESET")
        println(
            "  $DIM$GREEN✓$RESET$DIM declared and served   $CYAN·$RESET$DIM this row caps a larger ceiling   " +
                "$RED✗$RESET$DIM needs a decision   $YELLOW+$RESET$DIM served, declared by no row$RESET",
        )
        val all = args.contains(ALL_FLAG)
        return providers.map { (key, provider) -> report(probe.probe(key, provider, env), all) }.all { it }
    }

    private fun report(probed: ProbedProvider, all: Boolean): Boolean {
        val dialect = DialectWires.name(probed.provider.dialect)
        println()
        println("  $BOLD${probed.key}$RESET $DIM$dialect · ${probed.url ?: probed.provider.baseUrl}$RESET")
        return when (val roster = probed.roster) {
            // Not a fault: a provider shape with no list to ask for has nothing to disagree about.
            is UpstreamRoster.Unpublished -> true.also { println("    $DIM–$RESET ${roster.reason}") }
            is UpstreamRoster.Unreadable -> false.also { println("    $RED✗$RESET ${roster.detail}") }
            is UpstreamRoster.Published ->
                rows(diff.of(probed.provider.models, roster.models, probed.provider.isLocal), all)
        }
    }

    /** Every DECLARED row, then as many undeclared upstream models as [NEW_SHOWN] allows, then the
     *  count line. No declared row is ever withheld — those are the ones with a verdict against
     *  them — and the undeclared remainder is counted rather than dropped. */
    private fun rows(rows: List<RosterRow>, all: Boolean): Boolean {
        val (fresh, declared) = rows.partition { it.verdict == RosterVerdict.NEW }
        val shown = if (all) fresh else fresh.take(NEW_SHOWN)
        (declared + shown).forEach(::line)
        if (shown.size < fresh.size) {
            println("    $YELLOW+$RESET $DIM… and ${fresh.size - shown.size} more — `splice models <provider> $ALL_FLAG`$RESET")
        }
        val faults = declared.count { it.verdict in FAULTS }
        println(
            "    $DIM${declared.size} declared · ${fresh.size} served upstream and not declared · " +
                "$faults need a decision$RESET",
        )
        return faults == 0
    }

    /** A NEW row's note is the same sentence every time, so it is said once in the legend rather
     *  than once per row; a declared row's note is specific to that row and always printed. */
    private fun line(row: RosterRow) {
        val window = (row.declaredWindow ?: row.upstreamWindow)?.toString().orEmpty()
        val note = if (row.verdict == RosterVerdict.NEW) "" else row.note
        val tail = if (note.isEmpty()) "" else "  $DIM$note$RESET"
        println("    ${glyph(row.verdict)} ${row.id.padEnd(ID_PAD)} ${window.padStart(WINDOW_PAD)}$tail".trimEnd())
    }

    private fun glyph(verdict: RosterVerdict): String = when (verdict) {
        RosterVerdict.SERVED -> "$GREEN✓$RESET"
        RosterVerdict.CAPPED -> "$CYAN·$RESET"
        RosterVerdict.OVER_CEILING -> "$RED✗$RESET"
        RosterVerdict.UNSERVED -> "$RED✗$RESET"
        RosterVerdict.NEW -> "$YELLOW+$RESET"
    }

}
