// PORT-OF: app/cli/models/ModelsCommand.kt — report the complete provider roster without mutating configuration.
package splice.models.list

import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepthProbe
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader

// why: long model identifiers extend the column rather than being truncated.
private const val ID_PAD = 30

// why: nine digits align every declared context window up to a 100M-token ceiling.
private const val WINDOW_PAD = 9

/** Undeclared upstream models shown by default; the remainder is counted and --all reveals it. */
private const val NEW_SHOWN = 8
private const val ALL_FLAG = "--all"
private const val MODELS_USAGE = "usage: splice models [provider] [--all]"

/** The flags models takes: none, or --all once. */
private val MODELS_FLAGS = listOf(emptyList(), listOf(ALL_FLAG))

/** Compare configured model rows with their providers, reporting every declared row. The comparison
 *  is [ModelsReporter]'s, the same one GET /api/models/upstream serves; this renders it as text. */
public class ModelsCommand(
    configuration: ModelConfigurationSource,
    credentials: ModelCredentialSource,
    private val output: TerminalOutput,
) {
    private val reporter = ModelsReporter(configuration, credentials)

    /** True when every provider that answered agrees with splice.toml. */
    public fun models(args: List<String>, env: EnvReader): Boolean {
        // V4-309: [provider] and --all, once each, and nothing else. `splice models --help` dropped the
        // flag and ran the whole report, a request to every provider; any other word prints the usage.
        val (flags, words) = args.partition { it.startsWith("-") }
        if (words.size > 1 || flags !in MODELS_FLAGS) {
            output.line(MODELS_USAGE)
            return false
        }
        // Resolved from the caller's env like status and doctor, so NO_COLOR, a dumb TERM or a pipe
        // with no TERM gets the plain text, byte for byte.
        val report = Report(CliPalette(ColorDepthProbe(env).depth()))
        val wanted = words.firstOrNull()
        return when (val compared = reporter.report(wanted, env)) {
            is ModelsReport.NoSuchProvider -> {
                output.line("splice: no provider '${compared.wanted}' in ${compared.path}")
                output.line("  ${report.quiet("declared:")} ${compared.declared.joinToString(", ")}")
                false
            }
            is ModelsReport.Compared -> {
                report.header()
                val all = ALL_FLAG in flags
                compared.providers.map { report.provider(it, all) }.all { it }
            }
        }
    }

    /** One command's rendering, in one palette. Each verdict's tone names its state: served rows are
     *  live, faults are dead, a discovered row is splice's own addition (signal), the rest is
     *  structure. The glyph carries the verdict on its own, so the plain text reads the same. */
    private inner class Report(private val palette: CliPalette) {

        fun quiet(text: String): String = palette.paint(palette.quiet, text)

        private fun strong(text: String): String = palette.paint(palette.strong, text)

        private fun dead(text: String): String = palette.paint(palette.dead, text)

        fun header() {
            output.line("${strong("splice models")}${quiet(": what each provider serves, against splice.toml")}")
            output.line(
                "  ${glyph(RosterVerdict.SERVED)}${quiet(" declared and served")}   " +
                    "${glyph(RosterVerdict.CAPPED)}${quiet(" this row caps a larger ceiling")}   " +
                    "${glyph(RosterVerdict.UNSERVED)}${quiet(" needs a decision")}   " +
                    "${glyph(RosterVerdict.NEW)}${quiet(" joins heads with no models list")}   " +
                    "${glyph(RosterVerdict.EXCLUDED)}${quiet(" served, kept out")}",
            )
        }

        fun provider(reported: ProviderReport, all: Boolean): Boolean {
            output.line("")
            output.line("  ${strong(reported.key)} ${quiet("${reported.dialect} · ${reported.url}")}")
            return when (val roster = reported.roster) {
                is UpstreamRoster.Unpublished -> true.also { output.line("    ${quiet("–")} ${roster.reason}") }
                is UpstreamRoster.Unreadable -> false.also { output.line("    ${dead("✗")} ${roster.detail}") }
                is UpstreamRoster.Published -> {
                    rows(reported.rows, all)
                    reported.agrees
                }
            }
        }

        /** Every declared row, then the displayed undeclared rows — discovered ones first, then those kept
         *  out — and an explicit remainder count. */
        private fun rows(rows: List<RosterRow>, all: Boolean) {
            val (undeclared, declared) = rows.partition { it.verdict in rosterUndeclared }
            val ordered = undeclared.sortedBy { it.verdict == RosterVerdict.EXCLUDED }
            val shown = if (all) ordered else ordered.take(NEW_SHOWN)
            (declared + shown).forEach(::line)
            if (shown.size < ordered.size) {
                val more = "… and ${ordered.size - shown.size} more: `splice models <provider> $ALL_FLAG`"
                output.line("    ${glyph(RosterVerdict.NEW)} ${quiet(more)}")
            }
            val discovered = undeclared.count { it.verdict == RosterVerdict.NEW }
            val faults = declared.count { it.verdict in rosterFaults }
            output.line(
                "    " + quiet(
                    "${declared.size} declared · $discovered discovered · ${undeclared.size - discovered} kept out · " +
                        "$faults need a decision",
                ),
            )
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
