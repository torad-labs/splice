package splice.provider.codex.history

import kotlinx.serialization.json.Json
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapLines
import splice.core.memory.HeapReservations
import splice.core.util.JsonlSink
import splice.core.util.LogSink
import splice.core.util.SecureFile
import splice.provider.codex.state.CodeModeStateJournal
import splice.provider.codex.state.CodeModeStateText
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Forced suffix-only appends. Only an uncommitted final fragment can be ignored, then trimmed before retry. */
internal class CodeModeHistoryJournal(
    private val json: Json,
    private val heap: HeapReservations,
    private val maxBytes: Long,
    private val log: LogSink,
) {
    fun read(file: Path, key: String): CodeModeHistoryReplay {
        if (Files.size(file) > maxBytes) throw IOException("authoritative history journal exceeds its byte bound")
        val complete = CodeModeStateJournal.endsWithNewline(file)
        val replay = CodeModeHistoryReplay(key, heap)
        HeapLines(file, heap).use { lines -> readLines(lines, complete, replay) }
        checkTail(file, complete, replay)
        return replay
    }

    private fun readLines(lines: HeapLines, complete: Boolean, replay: CodeModeHistoryReplay) {
        while (lines.hasNext()) {
            lines.next().use { stage ->
                if (lines.hasNext() || complete) {
                    replay.accept(json.decodeFromString<CodeModeHistoryDelta>(stage.text), stage)
                }
            }
        }
    }

    private fun checkTail(file: Path, complete: Boolean, replay: CodeModeHistoryReplay) {
        val fragment = !complete && Files.size(file) > 0
        if (fragment && replay.committedBytes == 0L) {
            throw IOException("authoritative history has no complete initial revision")
        }
        if (fragment) log("[code-mode] authoritative history ignored an uncommitted final fragment")
    }

    fun append(file: Path, delta: CodeModeHistoryDelta, committedBytes: Long): CodeModeHistoryCommitted {
        val weight = CodeModeHistoryDeltas.weight(delta) * HISTORY_DECODE_FACTOR
        val stage = heap.reserve(weight) ?: throw HeapCapacityException()
        return stage.use {
            val text = json.encodeToString(CodeModeHistoryDelta.serializer(), delta)
            val next = committedBytes + CodeModeStateText(text).bytes + 1L
            if (next > maxBytes) throw IOException("authoritative history journal exceeds its byte bound")
            secure(file)
            FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
                if (channel.size() != committedBytes) channel.truncate(committedBytes)
            }
            JsonlSink.appendLine(
                file,
                text,
                maxBytes = Long.MAX_VALUE,
                archive = JsonlSink.NO_ARCHIVE,
            )
            FileChannel.open(file.parent, StandardOpenOption.READ).use { channel -> channel.force(true) }
            CodeModeHistoryCommitted(next, splice.core.perf.InputDigest.hex(text))
        }
    }

    fun digest(delta: CodeModeHistoryDelta): String {
        val stage = heap.reserve(CodeModeHistoryDeltas.weight(delta) * HISTORY_DECODE_FACTOR)
            ?: throw HeapCapacityException()
        return stage.use {
            splice.core.perf.InputDigest.hex(
                json.encodeToString(CodeModeHistoryDelta.serializer(), delta),
            )
        }
    }

    private fun secure(file: Path) {
        val open = SecureFile.ownerOnlyDirectory(file.parent)
        if (open != null) throw IOException("authoritative history directory is not owner-only")
        if (Files.notExists(file)) SecureFile.writeAtomic0600(file, "")
    }
}

internal data class CodeModeHistoryCommitted(val bytes: Long, val digest: String)
