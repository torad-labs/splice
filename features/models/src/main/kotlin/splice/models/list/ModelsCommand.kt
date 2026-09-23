// PORT-OF: app/cli/models/ModelsCommand.kt — report the complete provider roster without mutating configuration.
package splice.models.list

import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepthProbe
import splice.core.terminal.TerminalOutput
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

/** The verdicts of a served model no row declares: discovered into the picker, or kept out of it. */
private val UNDECLARED = setOf(RosterVerdict.NEW, RosterVerdict.EXCLUDED)

/** Compare configured model rows with their providers, reporting every declared row. */
public class ModelsCommand(
    private val configuration: ModelConfigurationSource,
    credentials: ModelCredentialSource,
    private val output: TerminalOutput,
) {
    private val probe = ModelsProbe(credentials = credentials)
    private val diff = RosterDiff()

    /** True when every provider that answered agrees with splice.toml. */
    public fun models(args: List<String>, env: EnvReader): Boolean {
        // Resolved from the caller's env like status and doctor, so NO_COLOR, a dumb TERM or a pipe
        // with no TERM gets the plain text, byte for byte.
        val report = Report(CliPalette(ColorDepthProbe(env).depth()))
        val wanted = args.firstOrNull { !it.startsWith("-") }
        val topology = configuration.load()
        val providers = topology.providers.filterKeys { wanted == null || it == wanted }
        if (providers.isEmpty()) {
            output.line("splice: no provider '$wanted' in ${topology.path}")
            output.line("  ${report.quiet("declared:")} ${topology.providers.keys.joinToString(", ")}")
            return false
        }
        report.header()
        val all = args.contains(ALL_FLAG)
        return providers.map { (key, provider) -> report.provider(probe.probe(key, provider, env), all) }.all { it }
    }

    /** One command's rendering, in one palette. Each verdict's tone names its state: served rows are
     *  live, faults are dead, a discovered row is splice's own addition (signal), the rest is
     *  structure. The glyph carries the verdict on its own, so the plain text reads the same. */
    private inner class Report(private val palette: CliPalette) {

        fun quiet(text: String): String = palette.paint(palette.quiet, text)

        private fun strong(text: String): String = palette.paint(palette.strong, text)

        private fun dead(text: String): String = palette.paint(palette.dead, text)

        fun header() {
            output.line("${strong("splice models")} ${quiet("— what each provider serves, against splice.toml")}")
            output.line(
                "  ${glyph(RosterVerdict.SERVED)}${quiet(" declared and served")}   " +
                    "${glyph(RosterVerdict.CAPPED)}${quiet(" this row caps a larger ceiling")}   " +
                    "${glyph(RosterVerdict.UNSERVED)}${quiet(" needs a decision")}   " +
                    "${glyph(RosterVerdict.NEW)}${quiet(" joins heads with no models list")}   " +
                    "${glyph(RosterVerdict.EXCLUDED)}${quiet(" served, kept out")}",
            )
        }

        fun provider(probed: ProbedProvider, all: Boolean): Boolean {
            val dialect = DialectWires.name(probed.provider.dialect)
            output.line("")
            output.line("  ${strong(probed.key)} ${quiet("$dialect · ${probed.url}")}")
            val provider = probed.provider
            return when (val roster = probed.roster) {
                is UpstreamRoster.Unpublished -> true.also { output.line("    ${quiet("–")} ${roster.reason}") }
                is UpstreamRoster.Unreadable -> false.also { output.line("    ${dead("✗")} ${roster.detail}") }
                is UpstreamRoster.Published ->
                    rows(diff.of(provider.models, roster.models, provider.isLocal, provider.discovery), all)
            }
        }

        /** Every declared row, then the displayed undeclared rows — discovered ones first, then those kept
         *  out — and an explicit remainder count. */
        private fun rows(rows: List<RosterRow>, all: Boolean): Boolean {
            val (undeclared, declared) = rows.partition { it.verdict in UNDECLARED }
            val ordered = undeclared.sortedBy { it.verdict == RosterVerdict.EXCLUDED }
            val shown = if (all) ordered else ordered.take(NEW_SHOWN)
            (declared + shown).forEach(::line)
            if (shown.size < ordered.size) {
                val more = "… and ${ordered.size - shown.size} more — `splice models <provider> $ALL_FLAG`"
                output.line("    ${glyph(RosterVerdict.NEW)} ${quiet(more)}")
            }
            val discovered = undeclared.count { it.verdict == RosterVerdict.NEW }
            val faults = declared.count { it.verdict in FAULTS }
            output.line(
                "    " + quiet(
                    "${declared.size} declared · $discovered discovered · ${undeclared.size - discovered} kept out · " +
                        "$faults need a decision",
                ),
            )
            return faults == 0
        }

        /** Undeclared rows share the legend; declared rows retain their specific diagnostic. */
        private fun line(row: RosterRow) {
            val window = (row.declaredWindow ?: row.upstreamWindow)?.toString().orEmpty()
            val note = if (row.verdict == RosterVerdict.NEW) "" else row.note
            val tail = if (note.isEmpty()) "" else "  ${quiet(note)}"
            val cells = "${row.id.padEnd(ID_PAD)} ${window.padStart(WINDOW_PAD)}"
            output.line("    ${glyph(row.verdict)} $cells$tail".trimEnd())
        }

        private fun glyph(verdict: RosterVerdict): String = when (verdict) {
            RosterVerdict.SERVED -> palette.paint(palette.live, "✓")
            RosterVerdict.CAPPED -> palette.paint(palette.live, "·")
            RosterVerdict.OVER_CEILING -> dead("✗")
            RosterVerdict.UNSERVED -> dead("✗")
            RosterVerdict.NEW -> palette.paint(palette.signal, "+")
            RosterVerdict.EXCLUDED -> quiet("–")
        }
    }
}
