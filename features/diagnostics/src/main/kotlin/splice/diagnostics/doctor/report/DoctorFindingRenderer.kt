// NEW: split from DoctorCommand.kt so the command stays under the concentration ceiling — one finding's
// CLI lines (glyph, headline, details, remedy), byte-identical to the output it replaced.
package splice.diagnostics.doctor.report

import splice.core.terminal.CliPalette
import splice.core.terminal.TerminalOutput
import splice.diagnostics.doctor.CheckStatus
import splice.diagnostics.doctor.DoctorCheck

/** Renders one finding without probing or changing it: headline, optional details, then its remedy.
 *  The caller supplies the report's palette so colour and NO_COLOR stay consistent across rows. */
internal class DoctorFindingRenderer(private val output: TerminalOutput) {
    fun render(check: DoctorCheck, palette: CliPalette) {
        val glyph = when (check.status) {
            CheckStatus.FAIL -> palette.paint(palette.dead, FAIL_GLYPH)
            CheckStatus.WARN -> palette.paint(palette.strain, WARN_GLYPH)
            // An INFO reaching here has a fix but is not a fault — a fresh machine with no topology
            // is not sick. It gets the room without the alarm.
            else -> palette.paint(palette.quiet, NOTE_GLYPH)
        }
        output.line("")
        output.line("  $glyph " + palette.paint(palette.strong, check.name))
        output.line("      " + palette.paint(palette.quiet, check.detail))
        check.details?.let {
            output.line("      " + palette.paint(palette.quiet, "Show the details"))
            output.line("        " + palette.paint(palette.quiet, it))
        }
        check.fix?.let {
            output.line("      " + palette.paint(palette.quiet, "fix") + "   " + palette.paint(palette.signal, it))
        }
    }
}

// Colour is the second carrier. These shapes also distinguish each status without colour.
// PASS and NOTE are shared with DoctorCommand's compact passed and informational rows.
internal const val PASS_GLYPH = "✓"
internal const val NOTE_GLYPH = "–"
private const val WARN_GLYPH = "!"
private const val FAIL_GLYPH = "✗"
