// PORT-OF: splice/app/Daemon.kt (DashboardHtml) @ ed5c868 — invariants unchanged: the dashboard
// HTML the control plane serves. Root package: Main.kt builds one and hands the result to Daemon,
// so this move needs no import change at either call site.
package splice.app

import splice.control.DashboardPage
import splice.core.util.Cancellables
import java.nio.file.Files
import java.nio.file.Path

/** The dashboard HTML the control plane serves: the built dist file, else the jar-bundled copy,
 *  else a placeholder. A constructed collaborator rather than a static factory (Kotlin style law,
 *  2026-08-15) — Main builds one and hands the result to [Daemon]. */
internal class DashboardHtml {
    internal fun source(
        distPath: Path,
        classpathHtml: ClasspathHtml = ClasspathHtml {
            // Asked of the class loader by absolute resource name, not of a class token via
            // `Daemon::class.java`. The shadow jar packages the dashboard at `webui/index.html`
            // (gateway/app/build.gradle.kts `from(dashboard) { into("webui") }`, the :console:bundle output) and the daemon runs as
            // `java -jar`, so the system loader is the one holding that jar — same bytes, minus
            // the reflective hop through a class whose only role was to name a loader.
            ClassLoader.getSystemResourceAsStream("webui/index.html")
                ?.bufferedReader()
                ?.use { it.readText() }
        },
    ): DashboardPage = DashboardPage {
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): a two-source optional probe: the dist file is absent in a packaged install and the classpath copy is absent in a dev run, so each null is the normal case and the chain ends in a page that says the dashboard build is missing.
        Cancellables.runCatchingCancellable { Files.readString(distPath) }
            .getOrNull()
            // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): second half of the same two-source probe; absent in a dev run, and the chain below ends in a page that SAYS the dashboard build is missing.
            ?: Cancellables.runCatchingCancellable { classpathHtml() }.getOrNull()
            ?: "<!doctype html><title>splice</title><p>dashboard build missing</p>"
    }
}
