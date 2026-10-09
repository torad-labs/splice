// NEW: the code-mode state a test's bridge left on disk. It is one file per conversation in [dir]
// (CodexCodeModeStore), where it used to be one file for the head; the tests that read what a save wrote,
// or make a save fail, or rewrite what an older daemon wrote, do it through this so that they say what
// they mean and not where the bytes are. It never uses the store's own file naming: a file is whatever
// [dir] holds, so a change to the naming cannot make a test agree with itself.
package splice.provider.codex

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import splice.provider.codex.state.CodeModeStateJournal
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

class CodeModeStateFiles(val dir: Path) {
    private val json = Json

    /** Every conversation file, in name order. Empty when nothing has been saved. A dot-named file is a
     *  save in flight (SecureFile writes `.secure*.tmp`, then renames it over its target), not state. */
    fun files(): List<Path> = if (Files.isDirectory(dir)) {
        Files.list(dir).use { entries ->
            entries.filter {
                Files.isRegularFile(it) && !it.fileName.toString().startsWith(".") &&
                    !it.fileName.toString().endsWith(".lock")
            }.sorted().toList()
        }
    } else {
        emptyList()
    }

    /** The state as the single file held it: every conversation's records, then every marker, one object.
     *  A file the sweep deletes between the listing and the read is gone, not an error: the retention tests
     *  poll this while the store deletes (CI run 36662545049 read a save's temp file after its rename). */
    fun state(): JsonObject {
        val conversations = files().mapNotNull { file ->
            try {
                Json.parseToJsonElement(json.encodeToString(CodeModeStateJournal.read(file, Json))).jsonObject
            } catch (_: NoSuchFileException) {
                null
            }
        }
        return JsonObject(
            mapOf(
                "version" to JsonPrimitive(1),
                "records" to JsonArray(conversations.flatMap { it["records"]?.jsonArray.orEmpty() }),
                "expired" to JsonArray(conversations.flatMap { it["expired"]?.jsonArray.orEmpty() }),
            ),
        )
    }

    fun text(): String = state().toString()

    /** Every saved record, as JSON. */
    fun records(): List<JsonObject> = state().getValue("records").jsonArray.map { it.jsonObject }

    /** Saves fail from here until [unblock], whatever they would write: [dir] is a regular file. What it
     *  held waits beside it, so the bytes a save left are still there when saves work again. */
    fun block() {
        if (Files.isDirectory(dir)) Files.move(dir, held())
        Files.writeString(dir, "")
    }

    fun unblock() {
        Files.delete(dir)
        if (Files.isDirectory(held())) Files.move(held(), dir)
    }

    /** What an older daemon wrote: each saved record replaced by [edit] of it, in the file it is in. */
    fun rewriteRecords(edit: (JsonObject) -> JsonObject) = files().forEach { file ->
        val restored = CodeModeStateJournal.read(file, Json)
        val conversation = Json.parseToJsonElement(json.encodeToString(restored)).jsonObject
        val records = conversation.getValue("records").jsonArray.map { edit(it.jsonObject) }
        Files.writeString(file, JsonObject(conversation + ("records" to JsonArray(records))).toString())
    }

    private fun held(): Path = dir.resolveSibling("${dir.fileName}.held")
}

/** A record of conversation [key], for a test that needs the store's input and no script. */
internal object CodeModeRecords {
    fun of(key: String, n: Int, updatedAt: Long = 1_790_000_000_000L + n): CodeModeRecord = CodeModeRecord(
        id = "$key-s$n",
        key = key,
        phase = CodeModePhase.LOST,
        origin = CodeModeOrigin(
            outer = JsonObject(emptyMap()),
            outerCallId = "outer-$key-$n",
            source = "text('$key/$n')",
            baseline = CodeModeBaseline(
                inputCount = n,
                inputDigest = "input-$key-$n",
                logicalCount = n,
                logicalDigest = "logical-$key-$n",
                metadataVersion = CODE_MODE_METADATA_VERSION,
            ),
        ),
        progress = CodeModeProgress(updatedAt = updatedAt, lastDigest = "digest-$key-$n"),
        carry = CodeModeNativeContinuity(segments = emptyList(), continuity = emptyList(), replay = emptyList()),
    )
}
