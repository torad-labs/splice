// NEW: V4-230 — where doctor reads the running daemon from. The CLI asks it over loopback with this
// shell's management key, as it always has. The daemon's own doctor (GET /api/doctor) reads the
// answers the daemon already has, in process: asking itself over HTTP from inside a request it was
// serving, it timed out on its own /health in 3 page loads of 6 and read "stopped" (2026-09-25).
package splice.diagnostics.doctor

import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.daemonclient.DaemonProbe
import splice.daemonclient.MgmtKeyFile
import splice.daemonclient.MgmtKeyRead

/** One read of the daemon past /health: what it answered, or why it was not asked. Each section
 *  words the misses its own way, so the cause stays a case rather than a sentence. */
internal sealed class DaemonRead<out T> {
    data class Answered<T>(val value: T) : DaemonRead<T>()

    /** This shell's state dir holds no management key, so the daemon was not asked. */
    data object KeyAbsent : DaemonRead<Nothing>()

    /** This shell's management key could not be read, for [reason]. */
    data class KeyUnreadable(val reason: String) : DaemonRead<Nothing>()

    /** The daemon was asked and gave no answer. */
    data object Unreachable : DaemonRead<Nothing>()
}

/** Everything doctor asks the running daemon. */
internal interface DaemonReads {
    fun health(port: Int): DaemonProbe.HealthProbe

    fun heads(port: Int, env: EnvReader): DaemonRead<List<DaemonProbe.HeadRuntime>>

    fun auth(port: Int, env: EnvReader): DaemonRead<Map<String, DaemonProbe.HeadAuthSeen>>

    fun accountPools(port: Int, env: EnvReader): AccountPoolsRead
}

/** The CLI's reads: over loopback, with the management key this shell's state dir holds. */
internal class LoopbackDaemon(private val pools: AccountPoolRead) : DaemonReads {
    override fun health(port: Int): DaemonProbe.HealthProbe = DaemonProbe.healthProbe(port)

    override fun heads(port: Int, env: EnvReader): DaemonRead<List<DaemonProbe.HeadRuntime>> =
        when (val key = MgmtKeyFile().read(env)) {
            is MgmtKeyRead.Present -> answered(DaemonProbe.headsRuntime(port, key.key))
            MgmtKeyRead.Absent -> DaemonRead.KeyAbsent
            is MgmtKeyRead.Unreadable -> DaemonRead.KeyUnreadable(key.reason)
        }

    override fun auth(port: Int, env: EnvReader): DaemonRead<Map<String, DaemonProbe.HeadAuthSeen>> =
        when (val key = MgmtKeyFile().read(env)) {
            is MgmtKeyRead.Present -> answered(DaemonProbe.authSeen(port, key.key))
            MgmtKeyRead.Absent -> DaemonRead.KeyAbsent
            is MgmtKeyRead.Unreadable -> DaemonRead.KeyUnreadable(key.reason)
        }

    override fun accountPools(port: Int, env: EnvReader): AccountPoolsRead = pools(port, env)

    private fun <T> answered(value: T?): DaemonRead<T> =
        value?.let { DaemonRead.Answered(it) } ?: DaemonRead.Unreachable
}

/** The daemon's own reads: the answers it gave itself in process, so its doctor never waits on the
 *  port it is serving from and needs no key. [port] only names where the daemon listens. A body that
 *  does not parse is a bug in the daemon that wrote it, so heads and auth let it reach the section's
 *  crash row rather than read it as a miss. */
internal class AnsweredDaemon(private val answers: DaemonAnswers) : DaemonReads {
    override fun health(port: Int): DaemonProbe.HealthProbe =
        Cancellables.runCatchingCancellable { DaemonProbe.HealthProbe.Up(DaemonProbe.parseHealth(answers.health)) }
            .getOrElse { DaemonProbe.HealthProbe.Odd("its own /health answer did not parse") }

    override fun heads(port: Int, env: EnvReader): DaemonRead<List<DaemonProbe.HeadRuntime>> =
        DaemonRead.Answered(DaemonProbe.parseHeadsRuntime(answers.heads))

    override fun auth(port: Int, env: EnvReader): DaemonRead<Map<String, DaemonProbe.HeadAuthSeen>> =
        DaemonRead.Answered(DaemonProbe.parseAuthSeen(answers.auth))

    override fun accountPools(port: Int, env: EnvReader): AccountPoolsRead = AccountPoolProjection().read(answers.auth)
}
