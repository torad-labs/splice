package splice.lifecycle.seatd

import splice.lifecycle.release.ReleaseDirectories
import splice.lifecycle.upgrade.ReleaseOrder
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

private const val SEATD_PIPE_BINARY = "seatd-pipe"

/** Layout and retention for the companion. Each running launcher keeps the executable it started from. */
internal class SeatdVersions(val root: Path, private val platform: SeatdPlatform) {
    fun location(version: String): SeatdInstaller.Location =
        SeatdInstaller.Location(root.resolve(version).resolve(SEATD_PIPE_BINARY), version)

    fun installed(path: Path): Boolean = !Files.isSymbolicLink(root) &&
        Files.isDirectory(path.parent, LinkOption.NOFOLLOW_LINKS) &&
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)

    fun prune(current: SeatdInstaller.Location): String? = try {
        discardOld(current)
        null
    } catch (_: IOException) {
        cleanupNotice()
    } catch (_: UncheckedIOException) {
        cleanupNotice()
    }

    private fun discardOld(current: SeatdInstaller.Location) {
        val order = ReleaseOrder(current.version)
        val older = ReleaseDirectories.children(root).mapNotNull(::ordered)
            .filter { it.order < order }.sortedByDescending { it.order }
        val rollback = older.firstOrNull { installed(it.dir.resolve(SEATD_PIPE_BINARY)) }
        val stale = older.filterNot { it == rollback }
        if (stale.isNotEmpty()) {
            val activity = SeatdActivity(platform, root.toRealPath())
            stale.filterNot { activity.held(it.dir.fileName) }.forEach { ReleaseDirectories.discard(it.dir) }
        }
    }

    private fun ordered(dir: Path): VersionFolder? =
        dir.fileName.toString().takeIf(ReleaseDirectories::isVersion)?.let { VersionFolder(dir, ReleaseOrder(it)) }

    private fun cleanupNotice(): String =
        "seatd-pipe was installed, but some older versions could not be removed from $root."

    private data class VersionFolder(val dir: Path, val order: ReleaseOrder)
}
