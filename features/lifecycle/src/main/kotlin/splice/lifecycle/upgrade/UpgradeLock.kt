// NEW: v0.4.0 FEATURES.md §5 — one `splice upgrade` at a time per install: two runs interleave their
// pointer flips and the prune of releases/ (review 2026-09-14: the prune list of one run could name
// the release the other was activating). An OS file lock under releases/, gone with the process
// however it ends; a second run is refused before it fetches anything. Split from UpgradeCommand.kt
// (concentration, 2026-09-14).
package splice.lifecycle.upgrade

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private const val LOCK_FILE = ".upgrade.lock"

/** The upgrade or rollback that runs under the lock; true when it activated what it set out to. */
internal fun interface UpgradeRun {
    operator fun invoke(): Upgraded<Boolean>
}

internal class UpgradeLock(layout: UpgradeLayout) {
    private val lock = ReleaseLock(layout.releases.resolve(LOCK_FILE))

    fun held(run: UpgradeRun): Upgraded<Boolean> = lock.held { run() }
        ?: Upgraded.Refused("another splice upgrade is running (holding ${lock.path}); wait for it")
}

/** Keep one release writer at a time, including downloads, promotion and pruning. */
internal class ReleaseLock(val path: Path) {
    inline fun <T : Any> held(run: () -> T): T? {
        Files.createDirectories(path.parent)
        return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            // null: another process holds it; Overlapping: this process does (a test, or a nested run).
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            lock?.use { run() }
        }
    }
}
