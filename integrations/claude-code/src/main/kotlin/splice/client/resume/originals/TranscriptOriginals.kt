// NEW: preserve every session JSONL before rewriting any, with immutable forced owner-only publication.
package splice.client.resume.originals

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import splice.client.resume.TRANSCRIPT_SUFFIX
import splice.core.config.StatePaths
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal val originalSessionId = Regex("[A-Za-z0-9_-]{1,128}")

private data class StagedOriginal(val source: Path, val target: Path, val staged: Path)

private object OriginalOperations {
    val mutex = ReentrantLock()
}

/** Immutable originals plus their live source locations, all under the selected state root. */
public class TranscriptOriginals(
    paths: StatePaths,
    private val copier: TranscriptOriginalCopy = ForcedTranscriptOriginalCopy,
) {
    private val root = paths.transcriptOriginalsDir

    /** [files] is the rewrite's frozen source census; a failed copy prevents every live rewrite. */
    public fun preserve(transcript: Path, files: List<Path>): Unit = OriginalOperations.mutex.withLock {
        openLock().use { channel ->
            channel.lock().use {
                val project = transcript.toAbsolutePath().normalize().parent
                val into = root.resolve(project.toRealPath().fileName.toString())
                directory(into)
                val staged = mutableListOf<StagedOriginal>()
                try {
                    for (source in files) {
                        val relative = project.relativize(source.toAbsolutePath().normalize())
                        val target = into.resolve(relative).normalize()
                        if (!target.startsWith(into)) throw IOException("Transcript original is outside its project")
                        directories(into, target.parent)
                        stage(source, target)?.let(staged::add)
                    }
                    if (staged.any { Files.mismatch(it.source, it.staged) != -1L }) {
                        throw IOException("Transcript changed while copying its original")
                    }
                    rememberSource(into, transcript)
                    staged.forEach(::publish)
                } finally {
                    staged.forEach { Files.deleteIfExists(it.staged) }
                }
            }
        }
    }

    /** An unchanged head copy still owns the existing original's lifetime; a new session stores nothing. */
    public fun rememberIfKept(transcript: Path) {
        try {
            if (Files.readAttributes(root, "basic:isDirectory", NOFOLLOW_LINKS)["isDirectory"] != true) {
                throw IOException("Transcript originals root is not a directory")
            }
            OriginalOperations.mutex.withLock {
                openLock().use { channel ->
                    channel.lock().use {
                        val parent = transcript.toAbsolutePath().normalize().parent
                        val into = root.resolve(parent.toRealPath().fileName.toString())
                        regular(into.resolve(transcript.fileName))
                        rememberSource(into, transcript)
                    }
                }
            }
        } catch (_: NoSuchFileException) {
            // No original of this session exists yet; an unchanged resume creates none.
        }
    }

    /** Startup cleanup keeps every unresolved source; only positive absence can retire an original. */
    public fun sweep(log: LogSink) {
        try {
            val directory = Files.readAttributes(root, "basic:isDirectory", NOFOLLOW_LINKS)["isDirectory"] == true
            if (!directory) throw IOException("Transcript originals root is not a directory")
            OriginalOperations.mutex.withLock {
                openLock().use { channel ->
                    channel.lock().use { TranscriptOriginalSweep(root, log).run() }
                }
            }
        } catch (_: NoSuchFileException) {
            // No original store yet.
        } catch (error: IOException) {
            log("[resume] original sweep kept unresolved files (${SafeFailureText.render(error)})\n")
        }
    }

    private fun openLock(): FileChannel {
        directory(root)
        val lock = root.resolve(".lock")
        try {
            SecureFile.createNew0600(lock, byteArrayOf())
        } catch (_: FileAlreadyExistsException) {
            regular(lock)
        }
        return FileChannel.open(lock, WRITE, NOFOLLOW_LINKS)
    }

    private fun rememberSource(into: Path, transcript: Path) {
        val name = transcript.fileName.toString()
        val id = name.removeSuffix(TRANSCRIPT_SUFFIX)
        if (!name.endsWith(TRANSCRIPT_SUFFIX) || !originalSessionId.matches(id)) {
            throw IOException("Transcript original has an invalid session filename")
        }
        val marker = into.resolve("$id.sources.json")
        val sources = try {
            regular(marker)
            Json.decodeFromString<List<String>>(Files.readString(marker))
        } catch (_: NoSuchFileException) {
            emptyList()
        }
        val live = transcript.toRealPath().toString()
        if (live !in sources) {
            SecureFile.writeAtomic0600(marker, Json.encodeToString(sources + live))
            force(marker)
            force(marker.parent)
        }
    }

    private fun stage(source: Path, target: Path): StagedOriginal? {
        try {
            regular(target)
            return null
        } catch (_: NoSuchFileException) {
            // Only proven absence earns a new original.
        }
        val staged = target.parent.resolve(".original-${UUID.randomUUID()}.tmp")
        SecureFile.createNew0600(staged, byteArrayOf())
        var copied = false
        try {
            copier.copy(source, staged)
            copied = true
            return StagedOriginal(source, target, staged)
        } finally {
            if (!copied) Files.deleteIfExists(staged)
        }
    }

    private fun publish(original: StagedOriginal) {
        try {
            // A hard-link publication is exclusive; unlike ATOMIC_MOVE it cannot replace an existing original.
            Files.createLink(original.target, original.staged)
        } catch (_: FileAlreadyExistsException) {
            regular(original.target)
        }
        force(original.target.parent)
    }

    private fun regular(file: Path) {
        if (Files.readAttributes(file, "basic:isRegularFile", NOFOLLOW_LINKS)["isRegularFile"] != true) {
            throw IOException("Transcript original is not a regular file")
        }
    }

    private fun directories(project: Path, parent: Path) {
        var dir = project
        for (part in project.relativize(parent)) {
            dir = dir.resolve(part)
            directory(dir)
        }
    }

    private fun directory(dir: Path) {
        if (Files.isSymbolicLink(dir)) throw IOException("Transcript originals directory is a symbolic link")
        val fault = SecureFile.ownerOnlyDirectory(dir)
        if (fault != null) throw IOException("Transcript originals directory is not owner-only: $fault")
        force(dir.parent)
    }

    private fun force(path: Path) {
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) }
    }
}
