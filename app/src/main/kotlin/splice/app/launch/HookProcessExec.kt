// NEW: the ProcessBuilder half of the exec-probe (V4-103) — the one child-process step :core may not
// own, implemented here so HookScriptFiles.probeExecutability stays framework- and process-free.
package splice.app.launch

import splice.client.login.HookExec
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object HookProcessExec {
    /** The real [HookExec]: spawn the probe script and wait, bounded — null on a clean exit, or the
     *  failure naming why not (timeout, non-zero exit). Mirrors the DR-8 noexec contract. */
    internal val exec: HookExec = HookExec { script, timeoutSeconds ->
        val process = ProcessBuilder(script.toString()).redirectErrorStream(true).start()
        if (!process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
            process.destroyForcibly()
            IOException("exec probe timed out after ${timeoutSeconds}s")
        } else if (process.exitValue() != 0) {
            IOException("exec probe exited ${process.exitValue()}")
        } else {
            null
        }
    }
}
