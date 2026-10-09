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
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapReservations
import splice.core.memory.HeapText
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import splice.upstream.memory.JvmHeap
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/** Where finished compaction answers are kept between the answer and its retry, by replay key.
 *  Every recording kept is a whole answer: the frames of a turn whose terminal ended cleanly. */
public interface CompactionRecordings {
    /** Keep [frames] as [key]'s answer under [generation], replacing any older generation kept for the key. A failure is the
     *  adapter's to report: the retry then runs upstream, as it did before this store existed, and the drive that called this is
     *  unaffected. */
    public fun save(key: String, generation: String, frames: List<String>)

    /** [key]'s kept answer with the generation it was kept under, or null when there is none to serve (never kept, expired,
     *  unreadable). */
    public fun load(key: String): KeptAnswer?

    /** Spend the answer kept for [key] under [generation]. A different generation (a newer answer kept at the key since) is left alone,
     *  whatever any cache still remembers: the generation lives with the durable copy. */
    public fun remove(key: String, generation: String)
}

public data class KeptAnswer(public val generation: String, public val frames: List<String>)

/** One owner-only file per key under [dir], named by the key's hash (the key carries a
 *  client-supplied session id, which is never a path). [now] is wall time because the file outlives
 *  the process that wrote it; a file older than [ttlMs] is never served, and is swept at the head's
 *  start ([sweep]) and on the next save. */
public class FileCompactionRecordings(
    private val dir: Path,
    private val log: LogSink = LogSink(DaemonLog::write),
    private val now: WallClock = WallClock(System::currentTimeMillis),
    private val ttlMs: Long = RECORDING_TTL_MS,
    private val heap: HeapReservations = JvmHeap.budget,
) : CompactionRecordings {
    private val json = Json { ignoreUnknownKeys = true }

    override fun save(key: String, generation: String, frames: List<String>) {
        Cancellables.runCatchingCancellable {
            SecureFile.ownerOnlyDirectory(dir)?.let { open -> log("[compaction] $dir is not owner-only: $open\n") }
            sweepExpired()
            val file = fileFor(key, generation)
            val text = json.encodeToString(KeptRecording.serializer(), KeptRecording(key, frames))
            SecureFile.writeAtomic0600(file, text)
            Files.setLastModifiedTime(file, FileTime.fromMillis(now()))
            // The newer answer replaces every older generation kept at the key.
            keptFor(key).filter { it != file }.forEach(Files::deleteIfExists)
        }.onFailure { failure ->
            log("[compaction] could not keep a compaction answer (${SafeFailureText.render(failure)}); $UPSTREAM\n")
        }
    }

    override fun load(key: String): KeptAnswer? {
        return Cancellables.runCatchingCancellable {
            val file = keptFor(key).maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
            if (file == null) {
                null
            } else if (expired(file)) {
                Files.deleteIfExists(file)
                null
            } else {
                HeapText.Reader.read(file, heap).use { staged ->
                    val kept = json.decodeFromString(KeptRecording.serializer(), staged.text)
                    val frames = kept.frames.takeIf { kept.key == key }?.also(staged::retain)
                    frames?.let { KeptAnswer(generationOf(file), it) }
                }
            }
        }.getOrElse { failure ->
            if (failure is HeapCapacityException) throw failure
            if (failure !is NoSuchFileException) {
                val why = SafeFailureText.render(failure)
                log("[compaction] a kept compaction answer is unreadable ($why); $UPSTREAM\n")
            }
            null
        }
    }

    override fun remove(key: String, generation: String) {
        Cancellables.runCatchingCancellable { Files.deleteIfExists(fileFor(key, generation)) }.onFailure { failure ->
            log("[compaction] could not remove a replayed compaction answer (${SafeFailureText.render(failure)})\n")
        }
    }

    /** V4-260: the expired answers go at the head's start too; swept only inside the next save, they
     *  stayed on disk for as long as the head kept no new answer. */
    public fun sweep() {
        if (!Files.isDirectory(dir)) return
        Cancellables.runCatchingCancellable { sweepExpired() }.onFailure { failure ->
            log("[compaction] could not sweep expired compaction answers (${SafeFailureText.render(failure)})\n")
        }
    }

    /** V4-260: every kept answer, and [dir] itself, for a head the topology no longer names. */
    public fun purge() {
        if (!Files.isDirectory(dir)) return
        Cancellables.runCatchingCancellable {
            Files.newDirectoryStream(dir).use { files -> files.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(dir)
        }.onFailure { failure ->
            log("[compaction] could not remove $dir (${SafeFailureText.render(failure)}); its head is gone\n")
        }
    }

    private fun sweepExpired() {
        Files.newDirectoryStream(dir, "*$SUFFIX").use { files ->
            files.filter(::expired).forEach(Files::deleteIfExists)
        }
    }

    private fun expired(file: Path): Boolean = now() - Files.getLastModifiedTime(file).toMillis() > ttlMs

    /** The kept files of [key]: `<hash of key>.<generation>.json`, one per generation (a save leaves only the newest), or the legacy `<hash of key>.json`. */
    private fun keptFor(key: String): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        val generations = Files.newDirectoryStream(dir, "${keyHash(key)}.*$SUFFIX").use { it.toList() }
        return generations + listOf(fileFor(key, LEGACY_GENERATION)).filter(Files::exists)
    }

    private fun fileFor(key: String, generation: String): Path {
        require(GENERATION.matches(generation)) { "a compaction generation is letters, digits and dashes" }
        if (generation == LEGACY_GENERATION) return dir.resolve("${keyHash(key)}$SUFFIX")
        return dir.resolve("${keyHash(key)}.$generation$SUFFIX")
    }

    private fun generationOf(file: Path): String =
        file.fileName.toString().removeSuffix(SUFFIX).substringAfter('.', LEGACY_GENERATION)

    private fun keyHash(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

@Serializable
private data class KeptRecording(val key: String, val frames: List<String>)

private const val SUFFIX = ".json"
private val GENERATION = Regex("[0-9A-Za-z-]+")

/** The generation of an answer kept before generations existed, as `<keyhash>.json`: still served after an upgrade, spent by its own delivery. */
private const val LEGACY_GENERATION = "legacy"
private const val UPSTREAM = "its retry runs upstream"

/** How long an answer is held for its retry, in memory and on disk alike: Claude Code retries a
 *  compaction within minutes, and a retry hours later is a new conversation state. */
internal const val RECORDING_TTL_MS = 2 * 60 * 60 * 1000L
