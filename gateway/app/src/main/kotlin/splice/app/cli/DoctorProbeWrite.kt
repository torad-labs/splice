// NEW: (JW-17, split from DoctorCommand.kt — the file sits at detekt's function budget) the
// state/log writability probe. Three subsystems (daemon.log, config persistence, usage/perf/
// compact appends) degrade silently on an unwritable ~/.claude-codex; doctor printed the path
// but never touched it.
package splice.app.cli

import splice.core.util.runCatchingCancellable
import java.nio.file.Files
import java.nio.file.Path

/** The writability probe as a constructed collaborator rather than a free function (Kotlin style
 *  law, 2026-08-15: main sources carry no top-level functions). Stateless — doctor builds one and
 *  asks it; the member keeps the old function's name so every historical grep still lands. */
internal class DoctorProbeWrite {

    /** JW-17: write-and-delete a dot-prefixed probe in [dir] (created first, as the daemon would).
     *  OK carries [okDetail] (the path, or a richer label); a failure is a FAIL whose fix is chosen
     *  by cause — AccessDenied wants chmod, anything else (typically no space) wants df. Non-mutating
     *  in spirit: the probe is removed in a finally. */
    internal fun writableProbe(name: String, dir: Path, okDetail: String? = null): DoctorCheck {
        val probe = dir.resolve(".splice-doctor-write-probe")
        return try {
            Files.createDirectories(dir)
            Files.writeString(probe, "probe")
            DoctorCheck(name, CheckStatus.INFO, okDetail ?: dir.toString())
        } catch (e: java.nio.file.AccessDeniedException) {
            DoctorCheck(name, CheckStatus.FAIL, "$dir is not writable (${e.javaClass.simpleName})", "chmod u+rwx $dir")
        } catch (e: java.io.IOException) {
            DoctorCheck(name, CheckStatus.FAIL, "$dir is not writable (${e.message})", "check free space: df -h $dir")
        } finally {
            runCatchingCancellable { Files.deleteIfExists(probe) }
        }
    }
}
