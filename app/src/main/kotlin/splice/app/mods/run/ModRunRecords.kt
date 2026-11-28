package splice.app.mods.run

import kotlinx.coroutines.withTimeoutOrNull
import splice.core.util.LogSink
import splice.upstream.codemode.ProcessWaiter
import java.io.IOException
import java.nio.file.Path

// why: give the orphaned guardian its stop and reap window without blocking the successor forever.
private const val MOD_PREVIOUS_OWNER_GRACE_MS = 6000L
private const val MOD_START_TURN_MS = 5000L // why: a stuck launch cannot prevent the successor from responding
private const val MOD_GROUP_SETTLE_MS = 100L // why: avoid spinning on the OS process table during retirement

internal class ModRunRecords(modsDir: Path, log: LogSink) {
    private val files = ModRunFiles(modsDir)
    private val locks = ModStartLocks(files.directory)
    private val ownership = ModGroupOwnership(ModProcessTable(log = log))
    private val waiter = ProcessWaiter()

    val supported: Boolean get() = ownership.supported

    fun turn(name: String): ModStartTurn =
        locks.acquire(name, MOD_START_TURN_MS) ?: throw IOException("another start of mod $name is in progress")

    suspend fun awaitGone(name: String) {
        val old = files.read(name) ?: return
        var pid = ownership.stillRunning(old) ?: return
        val cleared = withTimeoutOrNull(MOD_PREVIOUS_OWNER_GRACE_MS) {
            while (true) {
                waiter.wait(MOD_GROUP_SETTLE_MS)
                pid = ownership.stillRunning(old) ?: break
            }
            true
        }
        if (cleared == null) throw ModStillRunning(pid)
    }

    fun requireGone(name: String) {
        val old = files.read(name) ?: return
        ownership.stillRunning(old)?.let { throw ModStillRunning(it) }
    }

    fun begin(name: String, guardian: ModIdentity): ModRunAdmission {
        files.write(name, ModRunOwner(guardian))
        return ModRunAdmission(this, name, guardian)
    }

    fun admit(name: String, guardian: ModIdentity, host: Long) {
        turn(name).use {
            check(files.read(name)?.guardian == guardian) { "the mod launch no longer owns its run record" }
            val group = ModProcessIdentity.of(host) ?: throw IOException("the new Bun host could not be identified")
            files.write(name, ModRunOwner(guardian, group))
        }
    }
}

internal class ModStillRunning(pid: Long) : IOException() {
    val explanation = "an earlier copy of this mod is still running (PID $pid); stop it before enabling this mod"
}

internal class ModRunAdmission(
    private val records: ModRunRecords,
    private val name: String,
    private val guardian: ModIdentity,
) {
    fun host(pid: Long) = records.admit(name, guardian, pid)
}
