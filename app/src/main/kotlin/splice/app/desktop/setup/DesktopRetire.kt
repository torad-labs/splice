// NEW: desktop-full (docs/specs/desktop-full.md, df-uninstall). `splice uninstall` of the whole of splice puts Claude
// Desktop back, beside the units and the /login it takes away (InstallWiring). It takes splice's configuration out of
// Desktop's library exactly as `splice desktop off` does (DesktopAppMode.takeOut), with no daemon, since uninstall
// stops it, removes the credential helper, and leaves the record saying off. A door an older splice opened through
// Desktop's environment store, which splice can't write, is named with the variables to remove there. Off, it says
// nothing. A library splice can't read or write is named in one line and uninstall goes on: nothing that isn't splice's
// is ever deleted.
package splice.app.desktop.setup

import splice.app.desktop.record.DesktopRecords
import splice.client.desktop.DESKTOP_ENV_PLACE
import splice.client.desktop.DesktopAppConfig
import splice.client.desktop.DesktopEnvironment
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.SafeFailureText

internal class DesktopRetire(env: EnvReader, private val output: TerminalOutput) {
    private val paths = StatePaths(envReader = env)
    private val config = DesktopAppConfig(env)
    private val records = DesktopRecords(paths.desktopFile, LogSink { output.line(it.trimEnd()) })
    private val app = DesktopAppMode(records, DesktopAppLibrary(config), DesktopPrograms(paths.stateDir))

    fun retire() {
        val record = records.get()
        when {
            record.appLibrary != null -> takeOut()
            record.on -> {
                output.line("$STILL_SET $DESKTOP_ENV_PLACE:")
                DesktopEnvironment().names.forEach { output.line("  $it") }
            }
        }
    }

    /** splice's configuration taken out of Desktop's library, its helper removed, and the record left off. */
    private fun takeOut() {
        Cancellables.runCatchingCancellable {
            app.takeOut()
            records.update { it.copy(on = false) }
        }.fold(
            onSuccess = { output.line(TOOK_OUT) },
            onFailure = {
                output.line(
                    "splice: left Claude Desktop's configuration as it is: ${config.library}: " +
                        "${SafeFailureText.render(it)}; `splice desktop off` takes it out",
                )
            },
        )
    }
}

private const val TOOK_OUT = "splice: took splice's configuration out of Claude Desktop; quit and reopen Claude " +
    "Desktop: it opens on your claude.ai login again."

// What uninstall says of an older splice's door, before the place in Desktop and the names, which splice can't remove.
private const val STILL_SET = "splice: Claude Desktop's Code tab still has splice's variables; remove them in"
