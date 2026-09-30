// PORT-OF: splice/app/Daemon.kt (DashboardHtml) @ ed5c868 — the dashboard HTML the control plane serves.
// V4-444: packaged HTML is authoritative unless an absolute SPLICE_CONSOLE_HTML explicitly selects a file.
package splice.app

import splice.app.control.DashboardPage
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

/** Serves the jar-bundled console by default. An explicit override fails visibly, never back to the jar. */
internal class DashboardHtml(private val env: EnvReader = EnvReader(System::getenv)) {
    internal fun source(
        classpathHtml: ClasspathHtml = ClasspathHtml {
            // The app packages :console-next:bundle at this resource name; no working directory participates.
            ClassLoader.getSystemResourceAsStream("webui/index.html")
                ?.bufferedReader()
                ?.use { it.readText() }
        },
    ): DashboardPage {
        val override = env("SPLICE_CONSOLE_HTML")
        return DashboardPage {
            if (override == null) {
                Cancellables.runCatchingCancellable { classpathHtml() }.fold(
                    onSuccess = { it ?: "<!doctype html><title>splice</title><p>dashboard build missing</p>" },
                    onFailure = { "<!doctype html><title>splice</title><p>packaged dashboard build unreadable</p>" },
                )
            } else {
                Cancellables.runCatchingCancellable {
                    val path = Path.of(override)
                    require(path.isAbsolute) { "console override must be an absolute path" }
                    Files.readString(path)
                }.getOrElse {
                    "<!doctype html><title>splice</title><p>SPLICE_CONSOLE_HTML override path is unreadable</p>"
                }
            }
        }
    }
}
