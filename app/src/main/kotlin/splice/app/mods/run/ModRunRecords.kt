package splice.app.mods.run

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Path

// why: the retiring daemon gets its own stop hook and cleanup window before the successor needs to act.
private const val MOD_PREVIOUS_OWNER_GRACE_MS = 6000L
private const val MOD_START_TURN_MS = 5000L // why: a launch stuck holding its turn must not block its successor

internal class ModRunRecords(
    modsDir: Path,
    private val log: LogSink,
    private val ownership: ModGroupOwnership = ModGroupOwnership(ModProcessTable(log = log)),
) {
    private val files = ModRunFiles(modsDir, log)
    private val locks = ModStartLocks(files.directory)

    val supported: Boolean get() = ownership.supported

    fun tree(owner: ModRunOwner): ModProcessTree = ModProcessTree(owner, ownership)

    fun turn(name: String): ModStartTurn =
        locks.acquire(name, MOD_START_TURN_MS) ?: throw IOException("another start of mod $name is in progress")

    fun previous(name: String): ModRunOwner? {
        val owner = files.read(name) ?: return null
        return when (ownership.claim(owner)) {
            ModGroupClaim.Gone, ModGroupClaim.Reused -> null
            ModGroupClaim.Unreadable -> throw IOException("the previous mod group cannot be verified")
            ModGroupClaim.Held, ModGroupClaim.Draining -> owner
        }
    }

    suspend fun awaitPrevious(owner: ModRunOwner) {
        val leader = ModProcessIdentity.open(owner.leader)
        if (leader != null) withTimeoutOrNull(MOD_PREVIOUS_OWNER_GRACE_MS) { leader.onExit().await() }
        if (!tree(owner).terminate()) throw IOException("the previous mod group has not stopped")
    }

    fun requireGone(name: String) {
        if (previous(name) != null) throw IOException("the previous mod group has not stopped")
    }

    fun begin(name: String, leader: ModIdentity): ModRunOwner =
        ModRunOwner(leader, grouped = true).also { files.write(name, it) }

    suspend fun reap(except: Set<String>): Unit = coroutineScope {
        for (name in files.names()) {
            if (name !in except) launch { reap(name) }
        }
    }

    private suspend fun reap(name: String) {
        Cancellables.runCatchingBestEffort {
            turn(name).use {
                val owner = previous(name)
                if (owner == null || tree(owner).terminate()) files.clear(name)
            }
        }.onFailure { log("[mods] cannot reap $name: ${SafeFailureText.render(it)}\n") }
    }
}
