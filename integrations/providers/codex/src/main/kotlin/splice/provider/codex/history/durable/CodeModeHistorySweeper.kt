package splice.provider.codex.history.durable

import splice.core.util.JSONL_LOCK_SUFFIX
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** What a sweep removed: conversation files and their bytes, and the digests of the conversations they named.
 *  [stayed] counts the files it could not remove. */
internal data class CodeModeHistorySwept(
    val journals: Int,
    val bytes: Long,
    val gone: Set<String> = emptySet(),
    val stayed: Int = 0,
)

/**
 * Removes every conversation file in [dir] last written before a moment, except the conversations a head holds, and
 * every lock file whose journal is gone. Code mode keeps a conversation's work for its records' lifetime (24 hours by
 * default); until Nov 9, 2026 nothing removed a history journal while code mode stayed on, and 1,977 of them held
 * 7 GB on one machine, back 15 days. A conversation removed here that comes back is folded from its client history,
 * as a new one is; one that turns hot while this runs repairs its journal from memory at its next write.
 */
internal class CodeModeHistorySweeper(private val dir: Path, private val log: LogSink) {
    fun sweep(beforeMs: Long, held: Set<String>): CodeModeHistorySwept {
        if (!Files.isDirectory(dir)) return CodeModeHistorySwept(0, 0L)
        val owned = Files.list(dir).use { it.toList() }.filter { file ->
            conversation(file).let { it != null && it !in held }
        }
        val (locks, files) = owned.partition { it.fileName.toString().endsWith(JSONL_LOCK_SUFFIX) }
        val swept = journals(files, beforeMs)
        val stayed = swept.stayed + orphans(locks)
        if (stayed > 0) {
            log("[code-mode] history sweep left $stayed file(s) it could not remove; the next sweep retries")
        }
        return swept
    }

    private fun journals(files: List<Path>, beforeMs: Long): CodeModeHistorySwept {
        var journals = 0
        var bytes = 0L
        var stayed = 0
        val gone = HashSet<String>()
        files.forEach { file ->
            when (val freed = removed(file, beforeMs)) {
                null -> stayed++
                NOT_REMOVED -> Unit
                else -> {
                    journals++
                    bytes += freed
                    conversation(file)?.let(gone::add)
                }
            }
        }
        return CodeModeHistorySwept(journals, bytes, gone, stayed)
    }

    /** Removes the lock files whose journal is gone; the count it could not remove. */
    private fun orphans(locks: List<Path>): Int = locks.count { lock ->
        val journal = lock.resolveSibling(lock.fileName.toString().removeSuffix(JSONL_LOCK_SUFFIX))
        Files.notExists(journal) && removed(lock, Long.MAX_VALUE) == null
    }

    /** The bytes [file] freed, [NOT_REMOVED] when it is newer than [beforeMs] or already gone, null when it could not
     *  go. */
    private fun removed(file: Path, beforeMs: Long): Long? = try {
        if (Files.getLastModifiedTime(file).toMillis() >= beforeMs) {
            NOT_REMOVED
        } else {
            val size = Files.size(file)
            if (Files.deleteIfExists(file)) size else NOT_REMOVED
        }
    } catch (_: NoSuchFileException) {
        // Removed by its own conversation's rewrite between the listing and now.
        NOT_REMOVED
    } catch (failure: IOException) {
        log("[code-mode] history file ${file.fileName} not removed (${SafeFailureText.render(failure)})")
        null
    }

    /** The conversation digest a history file is named by, or null for a name no conversation writes. */
    private fun conversation(file: Path): String? {
        val name = file.fileName.toString()
        val digest = name.substringBefore('.')
        val hex = digest.length == CONVERSATION_DIGEST_CHARS && digest.all { it in '0'..'9' || it in 'a'..'f' }
        return digest.takeIf { hex && name.length > CONVERSATION_DIGEST_CHARS }
    }
}

// What [CodeModeHistorySweeper] reads as a file it left: newer than the cut, or already gone.
private const val NOT_REMOVED = -1L

// A history file is named by the SHA-256 hex digest of its conversation key.
private const val CONVERSATION_DIGEST_CHARS = 64
