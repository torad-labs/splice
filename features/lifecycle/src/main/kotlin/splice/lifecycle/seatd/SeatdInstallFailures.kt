package splice.lifecycle.seatd

import splice.core.install.seatdPipeRepository
import splice.lifecycle.seatd.SeatdInstaller.Failure
import splice.lifecycle.seatd.SeatdInstaller.Result.Failed
import java.nio.file.Path

internal class SeatdInstallFailures(private val retryCommand: String) {
    fun unpinned(): Failed = Failed(
        Failure.NO_REVIEWED_RELEASE,
        "No reviewed seatd-pipe release is pinned yet. Check $seatdPipeRepository for release news.",
    )

    fun unsupported(platform: SeatdPlatform): Failed = Failed(
        Failure.UNSUPPORTED_PLATFORM,
        "seatd-pipe is not available for ${platform.os} (${platform.arch}). " +
            "See $seatdPipeRepository for supported platforms.",
    )

    fun mismatch(): Failed = Failed(
        Failure.CHECKSUM_MISMATCH,
        "The seatd-pipe download did not match its reviewed SHA-256 checksum. Nothing was installed. " +
            "Run `$retryCommand` to retry. If it happens again, report it at $seatdPipeRepository/issues.",
    )

    fun invalidArchive(): Failed = Failed(
        Failure.INVALID_ARCHIVE,
        "The seatd-pipe archive must contain exactly seatd-pipe, LICENSE and THIRD_PARTY_NOTICES.md " +
            "as regular files. Nothing was installed. Report the archive at $seatdPipeRepository/issues.",
    )

    fun absent(): Failed = Failed(
        Failure.DOWNLOAD_FAILED,
        "The pinned seatd-pipe asset is missing from the release. Report it at $seatdPipeRepository/issues, " +
            "then run `$retryCommand` once it is available.",
    )

    fun download(): Failed = Failed(
        Failure.DOWNLOAD_FAILED,
        "Could not download seatd-pipe. Check your connection and $seatdPipeRepository/releases, " +
            "then run `$retryCommand`.",
    )

    fun notWritable(folder: Path): Failed = Failed(
        Failure.NOT_WRITABLE,
        "Cannot install seatd-pipe in $folder. Make sure the folder is writable, allows executables, " +
            "and has free disk space, then run `$retryCommand`.",
    )

    fun inProgress(): Failed = Failed(
        Failure.INSTALL_IN_PROGRESS,
        "Another seatd-pipe install is running. Wait for it to finish, then run `$retryCommand`.",
    )
}
