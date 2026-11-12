// NEW: V4-334 — the reasoning cache's conversations on disk, so a restarted daemon injects what the last
// one recorded. codex-rs keeps every reasoning item until compaction (context_manager/history.rs:944-960);
// the cache lived in memory only, and after the ten restarts of 2026-09-26 not one pre-restart tool step
// in three live claudex histories carried its reasoning (0/634, 0/389, 0/14).
//
// One file per conversation, one line per admitted round, appended through JsonlSink with rotation off (a
// rolled generation would be half a conversation) and with no force per line (fix 3, see `force` below). The
// directory is owner-only and each conversation file is created 0600. This file only reads, appends and deletes:
// WHAT is on disk is the cache's decision (ReasoningCache.kt), made in memory and written behind it in order
// (ReasoningCacheWrites). Every failure met here degrades to the no-injection status quo with one daemon.log line,
// never to a turn error (NEVER-BELOW-STATUS-QUO law).
package splice.dialect.responses.reasoning

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.JSONL_LOCK_SUFFIX
import splice.core.util.JsonlForce
import splice.core.util.JsonlSink
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

internal class ReasoningCacheFiles(
    private val dir: Path,
    /** How an appended record reaches the disk: the page cache, not a force per record (fix 3, 2026-10-26). The page
     *  cache outlives the process, so a restart keeps every record. A power loss can take the records the kernel had
     *  not yet written out and cut the last one short; the next start keeps every whole record before that ([read]).
     *  A test stands a slow disk in here. */
    private val force: JsonlForce = JsonlSink.PAGE_CACHE_FORCE,
    private val log: LogSink,
) {

    /** Whether [key] can name a file here. The cache's keys are `splice-<hex>`, with `-<hex>` per session
     *  (ReasoningCachePolicy.conversationKey); any other key stays in memory rather than become a path. */
    fun accepts(key: String): Boolean = KEY_SHAPE.matches(key)

    /** Every conversation the directory holds, least recently written first (the order the cache's LRU map
     *  keeps), or null when the directory cannot be made owner-only: then nothing is written and the cache is
     *  the in-memory one it was before V4-334. A file with a line that is not a round anywhere but its end is removed
     *  and skipped: its conversation injects nothing, which is where it stood before. */
    fun restore(): List<StoredConversation>? {
        val listed = try {
            val open = SecureFile.ownerOnlyDirectory(dir)
            if (open != null) return inMemoryOnly("it is not owner-only: $open")
            Files.list(dir).use { entries -> entries.filter { conversationOf(it) != null }.toList() }
                .map { it to Files.getLastModifiedTime(it) }
                .sortedBy { it.second }
                .map { it.first }
        } catch (failure: IOException) {
            return inMemoryOnly(SafeFailureText.render(failure))
        }
        return listed.mapNotNull { read(it) }
    }

    /** Append one admitted round to [key]'s file. False when it could not be written: the file is then
     *  removed, so the disk never holds a conversation with a round missing from its middle. */
    fun append(key: String, round: StoredRound): Boolean {
        val file = fileOf(key)
        return try {
            if (Files.notExists(file)) SecureFile.createNew0600(file, ByteArray(0))
            val line = lines.encodeToString(StoredRound.serializer(), round)
            JsonlSink.appendLine(file, line, maxBytes = Long.MAX_VALUE, force = force)
            true
        } catch (failure: IOException) {
            log(
                "[reasoning-cache] conversation ${key.take(KEY_LOG_CHARS)}… not written " +
                    "(${SafeFailureText.render(failure)}): its file is removed and it lives in memory only",
            )
            drop(key)
            false
        }
    }

    fun replace(key: String, rounds: List<StoredRound>): Boolean = try {
        val contents = rounds.joinToString("\n", postfix = "\n") { lines.encodeToString(StoredRound.serializer(), it) }
        SecureFile.writeAtomic0600(fileOf(key), contents)
        true
    } catch (failure: IOException) {
        log("[reasoning-cache] reconciled conversation not written (${SafeFailureText.render(failure)}): memory only")
        drop(key)
        false
    }

    /** Remove [key]'s file, and the lock JsonlSink.appendLine keeps beside it (the ActivityDays siblings). */
    fun drop(key: String) {
        val file = fileOf(key)
        listOf(file, file.resolveSibling("${file.fileName}$JSONL_LOCK_SUFFIX")).forEach { path ->
            try {
                Files.deleteIfExists(path)
            } catch (failure: IOException) {
                log("[reasoning-cache] ${path.fileName} not removed (${SafeFailureText.render(failure)})")
            }
        }
    }

    /** Remove every conversation here: the head no longer runs the cache, so nothing will read them. */
    fun purge() {
        val keys = try {
            if (Files.notExists(dir)) return
            Files.list(dir).use { entries -> entries.toList() }.mapNotNull { conversationOf(it) }
        } catch (failure: IOException) {
            log("[reasoning-cache] $dir not listed (${SafeFailureText.render(failure)}): its conversations stay")
            return
        }
        keys.forEach(::drop)
        if (keys.isNotEmpty()) log("[reasoning-cache] ${keys.size} conversation(s) removed: the cache is off")
    }

    private fun fileOf(key: String): Path = dir.resolve("$key$CONVERSATION_FILE")

    private fun conversationOf(file: Path): String? =
        file.fileName.toString().takeIf { it.endsWith(CONVERSATION_FILE) }?.removeSuffix(CONVERSATION_FILE)
            ?.takeIf(::accepts)

    /** A LINE CUT SHORT AT THE END COSTS THAT ROUND ALONE (2026-11-10). A record is appended as its line and the
     *  newline after it, so whatever follows the last newline is a write that never finished: this machine stopped
     *  dead at 8:28 PM on Nov 10 (its journal ends there with no shutdown), and the next start removed two live
     *  conversations whole, with every round they had kept, over one such line each. The rounds before it are whole
     *  and are everything the conversation had kept, so they keep serving, which leaves the request the same up to
     *  the lost round where removing the file changed it from the first one. A line that is not a round anywhere
     *  before the end is no cut write, and removes the file as before. */
    private fun read(file: Path): StoredConversation? {
        val key = conversationOf(file) ?: return null
        val why = try {
            val bytes = Files.readAllBytes(file)
            val ended = bytes.lastIndexOf(NEWLINE) + 1
            // Strict up to the last newline (a line there that is not text is not a round); what follows it may end
            // mid-character, so it is read leniently and judged as a round or not.
            val finished = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, ended)).lineSequence()
                .filter(String::isNotBlank).map { lines.decodeFromString(StoredRound.serializer(), it) }.toList()
            val unfinished = String(bytes, ended, bytes.size - ended, Charsets.UTF_8).takeIf(String::isNotBlank)
            val last = unfinished?.let(::roundOrNull)
            val rounds = finished + listOfNotNull(last)
            if (rounds.isNotEmpty() && rounds.all(StoredRound::whole)) {
                val stored = StoredConversation(key, rounds)
                return if (unfinished == null) stored else mended(stored, cut = last == null)
            }
            "an empty conversation or round"
        } catch (failure: IOException) {
            SafeFailureText.render(failure)
        } catch (_: IllegalArgumentException) {
            // kotlinx's SerializationException is one: a torn or foreign line. Its message quotes the line,
            // so the reason is ours.
            "a line that is not a round"
        }
        log(
            "[reasoning-cache] conversation file ${file.fileName} unreadable ($why): removed, so that " +
                "conversation injects nothing until its next round",
        )
        drop(key)
        return null
    }

    /** [line] as a round, or null when it is not one: the end of a write that was cut short. */
    private fun roundOrNull(line: String): StoredRound? = try {
        lines.decodeFromString(StoredRound.serializer(), line)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** [stored] written back as whole lines, so the next round starts on a line of its own instead of joining what
     *  the cut write left. A file that cannot be written again is removed ([replace]), and its conversation is then
     *  not restored: half of it must never be what the disk holds. */
    private fun mended(stored: StoredConversation, cut: Boolean): StoredConversation? {
        if (!replace(stored.key, stored.rounds)) return null
        if (cut) {
            log(
                "[reasoning-cache] conversation ${stored.key.take(KEY_LOG_CHARS)}… ended in a line cut short (a " +
                    "write that never finished): its ${stored.rounds.size} whole record(s) are kept, the cut one " +
                    "is gone",
            )
        }
        return stored
    }

    private fun inMemoryOnly(why: String): List<StoredConversation>? {
        log("[reasoning-cache] conversations are not kept in $dir ($why): reasoning lives in memory only")
        return null
    }
}

/** One conversation as the disk holds it: its rounds in the order they were admitted. */
internal data class StoredConversation(val key: String, val rounds: List<StoredRound>)

/** One line of a conversation file: a round's real function_call ids and its ordered envelopes. */
@Serializable
internal data class StoredRound(
    val ids: List<String>,
    val envelopes: List<String>,
    val resultProofs: Map<String, String?> = emptyMap(),
) {
    /** A round the cache would have admitted: at least one id and one envelope. */
    val whole: Boolean get() = if (ids.isEmpty()) {
        envelopes.isEmpty() && resultProofs.isNotEmpty()
    } else {
        envelopes.isNotEmpty() && resultProofs.isEmpty()
    }
}

private val lines = Json { ignoreUnknownKeys = false }

// `splice-` + 32 hex (the opening's hash), then optionally `-` + 16 hex (the session's).
private val KEY_SHAPE = Regex("[A-Za-z0-9-]{1,96}")
private const val CONVERSATION_FILE = ".jsonl"

// why: the byte JsonlSink.appendLine ends every record with; what follows the last one in a file is an unfinished
// write.
private const val NEWLINE = '\n'.code.toByte()
