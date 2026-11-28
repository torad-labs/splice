package splice.lifecycle.seatd

import splice.core.install.SEATD_PIPE_REPOSITORY
import splice.lifecycle.seatd.SeatdInstaller.Failure
import splice.lifecycle.seatd.SeatdInstaller.Result.Failed
import java.nio.file.Path

internal class SeatdInstallFailures(private val retryCommand: String) {
    fun unpinned(): Failed = Failed(
        Failure.NO_REVIEWED_RELEASE,
        "No reviewed seatd-pipe release is pinned yet. Check $SEATD_PIPE_REPOSITORY for release news.",
    )

    fun unsupported(platform: SeatdPlatform): Failed = Failed(
        Failure.UNSUPPORTED_PLATFORM,
        "seatd-pipe is not available for ${platform.os} (${platform.arch}). " +
            "See $SEATD_PIPE_REPOSITORY for supported platforms.",
    )

    fun mismatch(): Failed = Failed(
        Failure.CHECKSUM_MISMATCH,
        "The seatd-pipe download did not match its reviewed SHA-256 checksum. Nothing was installed. " +
            "Run `$retryCommand` to retry. If it happens again, report it at $SEATD_PIPE_REPOSITORY/issues.",
    )

    fun absent(): Failed = Failed(
        Failure.DOWNLOAD_FAILED,
        "The pinned seatd-pipe asset is missing from the release. Report it at $SEATD_PIPE_REPOSITORY/issues, " +
            "then run `$retryCommand` once it is available.",
    )

    fun download(): Failed = Failed(
        Failure.DOWNLOAD_FAILED,
        "Could not download seatd-pipe. Check your connection and $SEATD_PIPE_REPOSITORY/releases, " +
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
