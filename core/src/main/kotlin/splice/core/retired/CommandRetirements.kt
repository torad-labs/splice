// NEW: head-catalog (gw-retire). The record of a head command a person retired with `splice claude retire`: its
// command link and splice's settings in its Claude folder moved into a dated backup, while the head itself stays in
// splice.toml and keeps serving the Splice gateway. Not RetiredHeads, which records a head removed from splice.toml: a
// retired command's head is still configured, and only `splice claude restore` brings its command back. The CLI's
// install and the daemon's launch read the same file, so nothing lays a retired command's link again by itself.
package splice.core.retired

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

// why: the one file name, in the state dir the daemon and the CLI share.
private const val COMMAND_RETIREMENTS_FILE = "command-retirements.json"

/** One retired command: the head's [key], the [command] a person typed, the [selector] its pinned model runs as in
 *  `claude` (null when the head pins none), and the [backup] folder `splice claude restore` reads. */
@Serializable
public data class CommandRetirement(
    val key: String,
    val command: String,
    val selector: String? = null,
    val backup: String,
    @SerialName("retired_at") val retiredAt: String = "",
)

/** The retired commands, one JSON file in the state dir. Every call reads the file, so a daemon and the CLI agree. */
public class CommandRetirements(stateDir: Path) {
    private val file: Path = stateDir.resolve(COMMAND_RETIREMENTS_FILE)
    private val json = Json { ignoreUnknownKeys = true }

    public fun all(): List<CommandRetirement> {
        if (!Files.isRegularFile(file)) return emptyList()
        // A record that cannot be read retires nothing: every command launches and installs as before.
        return Cancellables.runCatchingCancellable {
            json.decodeFromString(ListSerializer(CommandRetirement.serializer()), Files.readString(file))
        }.fold(onSuccess = { it }, onFailure = { emptyList() })
    }

    /** The retirement of the head keyed or commanded [name], or null when it is not retired. */
    public fun of(name: String): CommandRetirement? = all().let { all ->
        all.firstOrNull { it.key == name } ?: all.firstOrNull { it.command == name }
    }

    /** Records [retirement], replacing an earlier record of the same key. */
    public fun retire(retirement: CommandRetirement) {
        write(all().filterNot { it.key == retirement.key } + retirement)
    }

    /** Forgets [key]: its command is back. */
    public fun restored(key: String) {
        val kept = all()
        if (kept.any { it.key == key }) write(kept.filterNot { it.key == key })
    }

    /** What a launch of the retired [retirement]'s command is told, before Claude Code starts. */
    public fun refusal(retirement: CommandRetirement): String {
        val pick = retirement.selector?.let { "/model $it" } ?: "/model"
        return "`${retirement.command}` is retired: run `claude` and type `$pick`, or bring it back with " +
            "`splice claude restore ${retirement.command}`"
    }

    private fun write(retirements: List<CommandRetirement>) {
        Files.createDirectories(file.parent)
        SecureFile.writeAtomic0600(
            file,
            json.encodeToString(ListSerializer(CommandRetirement.serializer()), retirements),
        )
    }
}
