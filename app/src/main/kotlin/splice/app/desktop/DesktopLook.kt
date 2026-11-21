// NEW: desktop-full (docs/specs/desktop-full.md, df-diagnostics). Claude Desktop's mode, read where splice keeps it by a
// command that runs with no daemon: `splice doctor` says it (ClaudeLaneCheck), and warns while Desktop has not been
// reopened since splice changed its configuration, since Desktop reads it only at launch.
package splice.app.desktop

import splice.client.desktop.DesktopAppConfig
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.diagnostics.doctor.DesktopReading

internal class DesktopLook(statePaths: StatePaths, env: EnvReader, log: LogSink) {
    private val records = DesktopRecords(statePaths.desktopFile, log)
    private val app = DesktopAppMode(
        records,
        DesktopAppLibrary(DesktopAppConfig(env)),
        DesktopCredentialHelper(statePaths.stateDir),
    )

    fun reading(): DesktopReading {
        val record = records.get()
        val reopen = app.relaunched(record) == false
        val said = app.mode().said(app.other(record))
        return DesktopReading("Claude Desktop: $said", warn = reopen, fix = REOPEN.takeIf { reopen })
    }
}

private const val REOPEN = "quit and reopen Claude Desktop to apply splice's configuration"
