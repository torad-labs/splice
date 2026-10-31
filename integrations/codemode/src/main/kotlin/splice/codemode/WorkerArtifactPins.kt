// NEW: daemon-owned archive pins survive atomic installs and are reaped only after their owner exits.
package splice.codemode

import splice.core.process.LaunchProcess
import splice.core.process.LaunchProcessIdentity
import splice.core.util.SecureFile
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.time.Instant

// why: stream large release jars through a bounded buffer rather than retaining their bytes in daemon heap.
private const val PIN_BUFFER_BYTES = 64 * 1024
private val PIN_OWNER_NAME = Regex("[1-9][0-9]*-(?:unknown|[0-9]+-[0-9]+)")

/** Hardlink boundary, also allowing the install race and cross-filesystem fallback to be exercised. */
internal fun interface WorkerArchiveLink {
    fun invoke(target: Path, source: Path): Path
}

/** Streaming copy boundary, including partial-copy failures before atomic publication. */
internal fun interface WorkerArchiveCopy {
    fun invoke(source: InputStream, target: Path): Long
}

/** One process's immutable worker archives. A birth-qualified directory prevents PID reuse owning old pins. */
internal class WorkerArtifactPins(
    stateDir: Path,
    ownerPid: Long = ProcessHandle.current().pid(),
    ownerBirth: Instant? = ProcessHandle.current().info().startInstant().orElse(null),
    private val processes: LaunchProcessIdentity = LaunchProcessIdentity { pid ->
        ProcessHandle.of(pid).filter { it.isAlive }
            .map { LaunchProcess(it.info().startInstant().orElse(null)) }.orElse(null)
    },
    private val link: WorkerArchiveLink = WorkerArchiveLink { target, source -> Files.createLink(target, source) },
    private val copy: WorkerArchiveCopy = WorkerArchiveCopy { source, target ->
        Files.copy(source, target, REPLACE_EXISTING)
    },
) {
    private val root = stateDir.resolve("worker-artifacts")
    private val owner = root.resolve(ownerName(ownerPid, ownerBirth))
    private val captured = mutableMapOf<Path, Path>()

    init {
        check(SecureFile.ownerOnlyDirectory(root) == null) { "Worker artifact root is not owner-only" }
        reap()
        check(SecureFile.ownerOnlyDirectory(owner) == null) { "Worker artifact owner directory is not owner-only" }
    }

    /** Open before inspecting/linking the pathname; a raced link falls back to those already-open bytes. */
    @Synchronized
    fun pin(source: Path): Path {
        val original = source.toAbsolutePath().normalize()
        return captured.getOrPut(original) {
            val resolved = original.toRealPath()
            FileChannel.open(resolved, READ).use { opened ->
                val digest = hash(opened)
                val pinned = owner.resolve("$digest.jar")
                if (!Files.isRegularFile(pinned, NOFOLLOW_LINKS) || hash(pinned) != digest) {
                    materialize(resolved, opened, pinned, digest)
                }
                check(hash(pinned) == digest) { "Pinned worker archive does not match the opened daemon archive" }
                captured[pinned] = pinned
                pinned
            }
        }
    }

    private fun materialize(original: Path, opened: FileChannel, pinned: Path, digest: String) {
        val linked = try {
            val candidate = link.invoke(pinned, original)
            Files.isRegularFile(candidate, NOFOLLOW_LINKS) && hash(candidate) == digest
        } catch (_: IOException) {
            // Cross-device links and a source pathname removed during install both require a copy.
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
        if (!linked) copyOpened(opened, pinned, digest)
    }

    private fun copyOpened(opened: FileChannel, pinned: Path, digest: String) {
        val stage = Files.createTempFile(owner, "archive-", ".tmp")
        try {
            opened.position(0)
            copy.invoke(Channels.newInputStream(opened), stage)
            check(hash(stage) == digest) { "Copied worker archive does not match the opened daemon archive" }
            Files.move(stage, pinned, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(stage)
        }
    }

    private fun reap() {
        Files.newDirectoryStream(root).use { directories ->
            directories.filter(::isDeadOwner).forEach(::removeOwner)
        }
    }

    private fun isDeadOwner(directory: Path): Boolean {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return false
        val name = directory.fileName.toString()
        if (!PIN_OWNER_NAME.matches(name)) return false
        val pid = name.substringBefore('-').toLong()
        val process = processes.inspect(pid)
        // Neither a missing recorded birth nor an unavailable observed birth proves a live owner dead.
        return when {
            process == null -> true
            name == "$pid-unknown" -> false
            process.startedAt == null -> false
            else -> name != ownerName(pid, process.startedAt)
        }
    }

    private fun removeOwner(directory: Path) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun ownerName(pid: Long, birth: Instant?): String =
        if (birth == null) "$pid-unknown" else "$pid-${birth.epochSecond}-${birth.nano}"

    private fun hash(path: Path): String = FileChannel.open(path, READ).use(::hash)

    private fun hash(channel: FileChannel): String {
        channel.position(0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(PIN_BUFFER_BYTES)
        while (channel.read(buffer) >= 0) {
            buffer.flip()
            digest.update(buffer)
            buffer.clear()
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
