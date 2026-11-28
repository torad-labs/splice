package splice.lifecycle.seatd

import splice.core.config.InstallPaths
import splice.core.util.EnvReader
import splice.lifecycle.upgrade.JdkUpgradeFetch
import splice.lifecycle.upgrade.ReleaseLock
import splice.lifecycle.upgrade.UpgradeFetch
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val SEATD_RETRY = "splice setup --update"
private const val SEATD_INSTALL_LOCK = ".install.lock"

/** Local availability and consented installation of the optional companion. */
public interface SeatdCompanion {
    /** The running Splice release's pin, if present. */
    public fun installed(): SeatdInstaller.Location?

    /** Whether any version is present to refresh, even if the running release pins another. */
    public fun hasInstallation(): Boolean

    /** Installs the running Splice release's pin. Blocking; the caller owns consent and output. */
    public fun install(retryCommand: String = SEATD_RETRY): SeatdInstaller.Result
}

/** The one installer, backed by the pin shipped with the running release. */
public class SeatdInstaller(private val env: EnvReader = EnvReader(System::getenv)) : SeatdCompanion {
    private val downloader: UpgradeFetch by lazy { JdkUpgradeFetch() }

    /** The pinned version's immutable executable path. */
    public data class Location(public val path: Path, public val version: String)

    /** The outcome of a consented install, including anything the caller needs to tell the user. */
    public sealed class Result {
        public data class Installed(public val location: Location, public val cleanupNotice: String?) : Result()
        public data class AlreadyInstalled(public val location: Location) : Result()
        public data class Failed(public val kind: Failure, public val message: String) : Result()
    }

    public enum class Failure {
        CHECKSUM_MISMATCH,
        DOWNLOAD_FAILED,
        INVALID_ARCHIVE,
        NOT_WRITABLE,
        UNSUPPORTED_PLATFORM,
        NO_REVIEWED_RELEASE,
        INSTALL_IN_PROGRESS,
    }

    /** Read-only lookup by file presence, with no download, hash or subprocess. */
    override fun installed(): Location? = SeatdPins.bundled?.let { installed(it, SeatdPlatform()) }

    /** Is there an installed version to refresh? The new jar's pin need not match the previous one. */
    override fun hasInstallation(): Boolean = versions(SeatdPlatform()).hasInstallation()

    /** Installs only the bundled pin. Call off the request thread, and only after affirmative consent. */
    override fun install(retryCommand: String): Result =
        install(SeatdPins.bundled, SeatdPlatform(), retryCommand)

    internal fun installed(pin: SeatdPin, platform: SeatdPlatform): Location? =
        pin.takeIf { it.reviewed() && platform.key() in it.platforms }?.let {
            val versions = versions(platform)
            versions.location(it.version).takeIf { location -> versions.installed(location.path) }
        }

    internal fun install(
        pin: SeatdPin?,
        platform: SeatdPlatform,
        retryCommand: String,
        fetch: UpgradeFetch? = null,
    ): Result {
        val messages = SeatdInstallFailures(retryCommand)
        val reviewed = pin?.takeIf(SeatdPin::reviewed) ?: return messages.unpinned()
        val asset = reviewed.download(platform) ?: return messages.unsupported(platform)
        val versions = versions(platform)
        val location = versions.location(reviewed.version)
        return if (versions.installed(location.path)) {
            Result.AlreadyInstalled(location)
        } else {
            underLock(versions, location, messages) {
                SeatdStage(versions, location, fetch ?: downloader, messages).install(asset)
            }
        }
    }

    private fun versions(platform: SeatdPlatform): SeatdVersions =
        SeatdVersions(InstallPaths(envReader = env).shareDir.resolve("seatd").toAbsolutePath().normalize(), platform)

    private inline fun underLock(
        versions: SeatdVersions,
        location: Location,
        messages: SeatdInstallFailures,
        run: () -> Result,
    ): Result = try {
        val root = versions.root
        if (Files.isSymbolicLink(root)) {
            messages.notWritable(root)
        } else {
            val lock = ReleaseLock(root.resolve(SEATD_INSTALL_LOCK))
            lock.held {
                // Another installer can land it between the first lookup and the lock.
                if (versions.installed(location.path)) Result.AlreadyInstalled(location) else run()
            } ?: messages.inProgress()
        }
    } catch (_: IOException) {
        messages.notWritable(versions.root)
    }
}
