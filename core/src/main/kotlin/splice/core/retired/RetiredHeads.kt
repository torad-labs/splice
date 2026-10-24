// NEW: Oct 12, 2026 — the record of a command a person removed from Models. Removing a command leaves every store it wrote
// in place (its requests, its sessions' transcripts, its usage), so this record is what keeps them findable once the head
// is gone from splice.toml: Requests resolves its key, Sessions reads its config directory, and the start sweep keeps its
// files. Re-adding the command drops the record, and the history joins it again.
package splice.core.retired

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

// why: the one file name, in the state dir the daemon and the CLI share.
private const val RETIRED_HEADS_FILE = "retired-heads.json"

/** One removed command: the head's key, the command a person typed, and the config directory its sessions wrote under. */
@Serializable
public data class RetiredHead(val key: String, val command: String, val configDir: String? = null)

/** The retired heads, one JSON file in the state dir. Every call reads the file, so a daemon and the CLI agree. */
public class RetiredHeads(stateDir: Path) {
    private val file: Path = stateDir.resolve(RETIRED_HEADS_FILE)
    private val json = Json { ignoreUnknownKeys = true }

    public fun all(): List<RetiredHead> {
        if (!Files.isRegularFile(file)) return emptyList()
        // A record that cannot be read leaves nothing retired: the commands stay gone from splice.toml either way.
        return Cancellables.runCatchingCancellable {
            json.decodeFromString(ListSerializer(RetiredHead.serializer()), Files.readString(file))
        }.fold(onSuccess = { it }, onFailure = { emptyList() })
    }

    /** Records [head], replacing an earlier record of the same key. */
    public fun retire(head: RetiredHead) {
        write(all().filterNot { it.key == head.key } + head)
    }

    /** Forgets [key]: the command is back, and its history is its own again. */
    public fun rejoin(key: String) {
        val kept = all()
        if (kept.any { it.key == key }) write(kept.filterNot { it.key == key })
    }

    private fun write(heads: List<RetiredHead>) {
        Files.createDirectories(file.parent)
        SecureFile.writeAtomic0600(file, json.encodeToString(ListSerializer(RetiredHead.serializer()), heads))
    }
}
