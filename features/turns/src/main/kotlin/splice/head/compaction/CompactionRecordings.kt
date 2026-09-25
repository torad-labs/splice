// NEW: V4-216 (2026-09-25) — a finished compaction answer kept on disk, so it outlives the daemon.
//
// CompactionReplay holds the answer of a compaction whose client hung up, for the byte-identical
// retry Claude Code sends minutes later. Held in memory only, a `splice restart` between the answer
// and the retry threw the answer away, and the retry paid for a second full upstream compaction.
// Only a FINISHED, whole recording crosses this port: one still in flight has a drive in this
// process and nothing another process could follow. The replay's map stays the first read; this
// store is read through on a miss, and a delivered replay removes the file with the entry.
package splice.head.compaction

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/** Where finished compaction answers are kept between the answer and its retry, by replay key.
 *  Every recording kept is a whole answer: the frames of a turn whose terminal ended cleanly. */
public interface CompactionRecordings {
    /** Keep [frames] as [key]'s answer. A failure is the adapter's to report: the retry then runs
     *  upstream, as it did before this store existed, and the drive that called this is unaffected. */
    public fun save(key: String, frames: List<String>)

    /** [key]'s kept answer, or null when there is none to serve (never kept, expired, unreadable). */
    public fun load(key: String): List<String>?

    public fun remove(key: String)
}

/** One owner-only file per key under [dir], named by the key's hash (the key carries a
 *  client-supplied session id, which is never a path). [now] is wall time because the file outlives
 *  the process that wrote it; a file older than [ttlMs] is never served and is swept on the next save. */
public class FileCompactionRecordings(
    private val dir: Path,
    private val log: LogSink = LogSink(DaemonLog::write),
    private val now: WallClock = WallClock(System::currentTimeMillis),
    private val ttlMs: Long = RECORDING_TTL_MS,
) : CompactionRecordings {
    private val json = Json { ignoreUnknownKeys = true }

    override fun save(key: String, frames: List<String>) {
        Cancellables.runCatchingCancellable {
            SecureFile.ownerOnlyDirectory(dir)?.let { open -> log("[compaction] $dir is not owner-only: $open\n") }
            sweep()
            val file = fileFor(key)
            val text = json.encodeToString(KeptRecording.serializer(), KeptRecording(key, frames))
            SecureFile.writeAtomic0600(file, text)
            Files.setLastModifiedTime(file, FileTime.fromMillis(now()))
        }.onFailure { failure ->
            log("[compaction] could not keep a compaction answer (${SafeFailureText.render(failure)}); $UPSTREAM\n")
        }
    }

    override fun load(key: String): List<String>? {
        val file = fileFor(key)
        return Cancellables.runCatchingCancellable {
            if (expired(file)) {
                Files.deleteIfExists(file)
                null
            } else {
                val kept = json.decodeFromString(KeptRecording.serializer(), Files.readString(file))
                kept.frames.takeIf { kept.key == key }
            }
        }.getOrElse { failure ->
            if (failure !is NoSuchFileException) {
                val why = SafeFailureText.render(failure)
                log("[compaction] a kept compaction answer is unreadable ($why); $UPSTREAM\n")
            }
            null
        }
    }

    override fun remove(key: String) {
        Cancellables.runCatchingCancellable { Files.deleteIfExists(fileFor(key)) }.onFailure { failure ->
            log("[compaction] could not remove a replayed compaction answer (${SafeFailureText.render(failure)})\n")
        }
    }

    private fun sweep() {
        Files.newDirectoryStream(dir, "*$SUFFIX").use { files ->
            files.filter(::expired).forEach(Files::deleteIfExists)
        }
    }

    private fun expired(file: Path): Boolean = now() - Files.getLastModifiedTime(file).toMillis() > ttlMs

    private fun fileFor(key: String): Path {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return dir.resolve(hash.joinToString("") { "%02x".format(it) } + SUFFIX)
    }
}

@Serializable
private data class KeptRecording(val key: String, val frames: List<String>)

private const val SUFFIX = ".json"
private const val UPSTREAM = "its retry runs upstream"

/** How long an answer is held for its retry, in memory and on disk alike: Claude Code retries a
 *  compaction within minutes, and a retry hours later is a new conversation state. */
internal const val RECORDING_TTL_MS = 2 * 60 * 60 * 1000L
