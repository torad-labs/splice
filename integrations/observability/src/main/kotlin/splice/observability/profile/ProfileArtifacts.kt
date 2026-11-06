package splice.observability.profile

import splice.core.perf.observation.PerfIdentity
import splice.core.perf.runtime.PerfProfileArtifact
import splice.core.util.SecureFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** All names are generated IDs; no route-supplied path is opened. Atomic publication follows size validation. */
internal class ProfileArtifacts(directory: Path, private val identity: PerfIdentity) : AutoCloseable {
    private val directory = directory.toAbsolutePath().normalize()
    private var lockChannel: FileChannel? = null
    private var fileLock: FileLock? = null

    init {
        Files.createDirectories(this.directory)
        check(SecureFile.ownerOnlyDirectory(this.directory) == null) { "Profile directory must be owner-only" }
        require(!Files.isSymbolicLink(this.directory)) { "Profile directory must not be a symbolic link" }
    }

    fun lock() {
        val file = directory.resolve(".profile-owner.lock")
        try {
            SecureFile.createNew0600(file, byteArrayOf())
        } catch (_: FileAlreadyExistsException) {
            check(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { "Invalid profile directory lock" }
        }
        val channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        try {
            fileLock = checkNotNull(channel.tryLock()) { "Profile directory is already owned" }
            lockChannel = channel
        } finally {
            if (lockChannel == null) channel.close()
        }
    }

    override fun close() {
        val ownedLock = fileLock
        val channel = lockChannel
        fileLock = null
        lockChannel = null
        try {
            ownedLock?.release()
        } finally {
            channel?.close()
        }
    }

    fun temporary(record: ProfileRecord): Path = path(record.id, "tmp").also {
        SecureFile.createNew0600(it, byteArrayOf())
    }

    fun publish(record: ProfileRecord, temporary: Path) {
        val size = Files.size(temporary)
        if (size > ProfileLimits.ARTIFACT_BYTES) throw ProfileOversized()
        check(size > 0L) { "Profile produced an empty artifact" }
        Files.move(temporary, path(record.id, "profile"), StandardCopyOption.ATOMIC_MOVE)
        record.sizeBytes = size
        record.available = true
    }

    fun metadata(record: ProfileRecord) = SecureFile.writeAtomic0600(
        path(record.id, "json"),
        record.json(identity).toString(),
    )

    fun artifact(record: ProfileRecord): PerfProfileArtifact? {
        val file = path(record.id, "profile")
        if (!record.available || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        val bytes = Files.size(file)
        if (bytes != record.sizeBytes || bytes > ProfileLimits.ARTIFACT_BYTES) return null
        val type = if (record.request.profiler == "jfr") "application/octet-stream" else "text/plain; charset=utf-8"
        return PerfProfileArtifact(record.id, file, bytes, type)
    }

    fun expire(record: ProfileRecord) {
        Files.deleteIfExists(path(record.id, "profile"))
        Files.deleteIfExists(path(record.id, "tmp"))
        record.available = false
        record.issue = "artifact_expired"
        metadata(record)
    }

    fun deleteTemporary(record: ProfileRecord) { Files.deleteIfExists(path(record.id, "tmp")) }

    /** A new manager cannot adopt another boot's capture. Remove only our generated filenames. */
    fun clearPreviousBoot() {
        Files.newDirectoryStream(directory).use { entries ->
            entries.forEach { file ->
                if (MANAGED_NAME.matches(
                        file.fileName.toString(),
                    ) && !Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)
                ) {
                    Files.deleteIfExists(file)
                }
            }
        }
    }

    fun forget(record: ProfileRecord) {
        expire(record)
        Files.deleteIfExists(path(record.id, "json"))
    }

    private fun path(id: String, extension: String): Path = directory.resolve("$id.$extension")
}

private val MANAGED_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(profile|json|tmp)")
