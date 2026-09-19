// NEW: v0.4.0 FEATURES.md §5 — one `splice upgrade` at a time per install: two runs interleave their
// pointer flips and the prune of releases/ (review 2026-09-14: the prune list of one run could name
// the release the other was activating). An OS file lock under releases/, gone with the process
// however it ends; a second run is refused before it fetches anything. Split from UpgradeCommand.kt
// (concentration, 2026-09-14).
package splice.app.cli

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private const val LOCK_FILE = ".upgrade.lock"

/** The upgrade or rollback that runs under the lock; true when it activated what it set out to. */
internal fun interface UpgradeRun {
    operator fun invoke(): Boolean
}

internal class UpgradeLock(private val layout: UpgradeLayout) {

    /** Runs [run] holding the install's upgrade lock, or refuses when another process holds it. */
    fun held(run: UpgradeRun): Boolean {
        Files.createDirectories(layout.releases)
        val lockFile = layout.releases.resolve(LOCK_FILE)
        return FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            // null: another process holds it; Overlapping: this process does (a test, or a nested run).
            val lock = try {
                channel.tryLock()
            } catch (held: OverlappingFileLockException) {
                throw refused(lockFile).initCause(held)
            } ?: throw refused(lockFile)
            lock.use { run() }
        }
    }

    private fun refused(lockFile: Path) =
        UpgradeRefused("another splice upgrade is running (holding $lockFile); wait for it")
}
