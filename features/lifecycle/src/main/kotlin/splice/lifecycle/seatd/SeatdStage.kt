package splice.lifecycle.seatd

import splice.lifecycle.release.ReleaseDirectories
import splice.lifecycle.seatd.SeatdInstaller.Location
import splice.lifecycle.seatd.SeatdInstaller.Result
import splice.lifecycle.upgrade.Downloaded
import splice.lifecycle.upgrade.QuietDownload
import splice.lifecycle.upgrade.UpgradeFetch
import splice.lifecycle.upgrade.UpgradeFetchFailed
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.PosixFilePermissions

private val SEATD_PIPE_MODE = PosixFilePermissions.fromString("rwxr-xr-x")

/** Only a verified archive is read. The complete version directory is published with one rename. */
internal class SeatdStage(
    private val versions: SeatdVersions,
    private val location: Location,
    private val fetch: UpgradeFetch,
    private val messages: SeatdInstallFailures,
) {
    fun install(asset: SeatdDownload): Result {
        val work = Files.createTempDirectory(versions.root, ".staging-")
        val cleanup: String?
        val result = try {
            install(asset, work)
        } finally {
            cleanup = cleanup(work)
        }
        return if (result is Result.Installed && cleanup != null) {
            result.copy(cleanupNotice = listOfNotNull(result.cleanupNotice, cleanup).joinToString(" "))
        } else {
            result
        }
    }

    private fun install(asset: SeatdDownload, work: Path): Result = try {
        val archive = work.resolve("download.tar.gz")
        when (fetch.download(asset.url, archive, asset.sha256, QuietDownload())) {
            Downloaded.Landed -> promote(archive, work)
            Downloaded.Absent -> messages.absent()
            is Downloaded.Mismatch -> messages.mismatch()
        }
    } catch (_: UpgradeFetchFailed) {
        messages.download()
    } catch (_: IOException) {
        messages.notWritable(versions.root)
    } catch (_: UnsupportedOperationException) {
        messages.notWritable(versions.root)
    }

    private fun promote(archive: Path, work: Path): Result {
        val payload = Files.createDirectory(work.resolve("files"))
        if (!SeatdArchive().unpack(archive, payload)) return messages.invalidArchive()
        Files.setPosixFilePermissions(payload.resolve(SEATD_PIPE_BINARY), SEATD_PIPE_MODE)
        Files.move(payload, location.path.parent, ATOMIC_MOVE)
        return Result.Installed(location, versions.prune(location))
    }

    private fun cleanup(work: Path): String? = try {
        ReleaseDirectories.discard(work)
        null
    } catch (_: IOException) {
        "The temporary seatd-pipe download could not be removed from $work."
    }
}
