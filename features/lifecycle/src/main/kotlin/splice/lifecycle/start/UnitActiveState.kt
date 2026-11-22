// NEW: Nov 21, 2026 — what a supervisor unit is doing, in systemd's own word. A restart asked `systemctl is-active`
// whether the unit ran the daemon, and that answers "no" for a unit that is starting or stopping its daemon exactly
// as for one that runs none. A restart that met either window took the daemon on the port for nobody's and stopped it
// from here, by signal, under systemd's hand: on Nov 21 an install's first launch left the unit between two runs,
// `splice restart --now` then stopped the unit's own daemon with SIGTERM and SIGKILL, the verb exited 1, and the
// unit's restart counter climbed to a 46 s backoff.
package splice.lifecycle.start

import splice.core.util.Cancellables
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// why: systemd's two names for a unit at rest, which holds no daemon; every other state runs one or is bringing one
// up or down (active, reloading, activating, deactivating).
internal const val UNIT_STATE_INACTIVE: String = "inactive"
internal const val UNIT_STATE_FAILED: String = "failed"

/** A unit's ActiveState as systemd names it (`active`, `reloading`, `activating`, `deactivating`, `inactive`,
 *  `failed`), or null when it cannot be had. A test names a unit's state with a lambda; [SystemctlActiveState] asks
 *  the unit manager. */
internal fun interface UnitActiveState {
    operator fun invoke(unit: String): String?
}

/** What `systemctl --user <args>` prints, bounded: null when there is no manager to ask, it answers non-zero, or it
 *  does not answer in time. Its stderr is discarded, as [JdkSystemctl]'s is. */
internal class SystemctlText(private val timeoutMs: Long = SYSTEMCTL_TIMEOUT_MS) {
    fun of(args: List<String>): String? {
        val process = Cancellables.runCatchingCancellable {
            UserManagerBus.supply(ProcessBuilder(listOf("systemctl", "--user") + args))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.getOrElse { return null }
        return try {
            val bytes = CompletableFuture.supplyAsync { process.inputStream.readAllBytes() }
                .get(timeoutMs, TimeUnit.MILLISECONDS)
            val answered = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) && process.exitValue() == 0
            bytes.toString(Charsets.UTF_8).takeIf { answered }
        } catch (_: TimeoutException) {
            null
        } finally {
            process.destroyForcibly()
        }
    }
}

/** The real one: `systemctl --user show --property=ActiveState --value <unit>`. A unit systemd does not know reads
 *  `inactive`, which is true of it: it holds no daemon. */
internal class SystemctlActiveState(private val systemctl: SystemctlText = SystemctlText()) : UnitActiveState {
    override fun invoke(unit: String): String? =
        systemctl.of(listOf("show", "--property=ActiveState", "--value", unit))?.trim()?.ifEmpty { null }
}
