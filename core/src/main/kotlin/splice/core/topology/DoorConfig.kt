// NEW: custom-provider (issue #680, cp-schema) — the `[door]` table of splice.toml. Today it holds the door's named
// commands: a team's wrapper starts Claude Code on the door through one, on its own head's model and picker, without
// moving the person's plain `claude` lane (cp-command). The words are read as strings and checked by [DoorChecks], so a
// bad value is one finding among all of them, never a decode error that hides the rest.
package splice.core.topology

import kotlinx.serialization.Serializable

@Serializable
public data class DoorConfig(
    /** `[door.commands.<name>]`: the commands a wrapper starts Claude Code on the door through, by name. */
    val commands: Map<String, DoorCommandConfig> = emptyMap(),
)

/** One `[door.commands.<name>]`: the head whose pinned model a session of the command starts on, and whose models its
 *  /model lists ([models] = "head", the default) or the door's whole picker ("door"). */
@Serializable
public data class DoorCommandConfig(
    val head: String = "",
    val models: String? = null,
) {
    /** True when a session of this command lists the door's whole picker, false for its head's rows alone. */
    public val listsDoor: Boolean get() = models == DOOR_MODELS_DOOR
}

/** The [DoorCommandConfig.models] word for the head's own rows, the default. */
public const val DOOR_MODELS_HEAD: String = "head"

/** The [DoorCommandConfig.models] word for the door's whole picker. */
public const val DOOR_MODELS_DOOR: String = "door"

/** Why a `[door.commands.*]` table cannot run: its head, its word, its name. Each finding names its dotted path. */
public class DoorChecks(private val topology: Topology) {
    public fun findings(): List<TopologyFinding> = topology.door.commands.flatMap { (name, command) ->
        listOfNotNull(head(name, command), models(name, command), name(name))
    }

    private fun head(name: String, command: DoorCommandConfig): TopologyFinding? {
        val path = "door.commands.$name.head"
        val head = topology.heads[command.head]
        val why = when {
            command.head.isBlank() -> "names no head; set head to the key of a [heads.*] table"
            head == null -> "names a head splice.toml does not declare; name one of its [heads.*] tables"
            topology.providers[head.provider]?.auth?.kind == AuthKind.Client.wire ->
                "names a head whose provider forwards the caller's own Claude login, which is never on the door; " +
                    "name a head on another provider"
            else -> null
        }
        return why?.let { TopologyFinding(path, it) }
    }

    private fun models(name: String, command: DoorCommandConfig): TopologyFinding? =
        command.models?.takeUnless { it == DOOR_MODELS_HEAD || it == DOOR_MODELS_DOOR }?.let {
            TopologyFinding(
                "door.commands.$name.models",
                "must be \"$DOOR_MODELS_HEAD\" (its head's models) or \"$DOOR_MODELS_DOOR\" (the door's whole picker)",
            )
        }

    /** A name another command already runs as: Claude Code's own, or a head's. Two door commands cannot share one,
     *  since each is a key of the same table. */
    private fun name(name: String): TopologyFinding? {
        val why = when {
            name == CLAUDE_COMMAND -> CLAUDE_TAKEN
            topology.heads.any { (key, head) -> (head.claude.command ?: key) == name } ->
                "is a head's command already; give this one another name"
            else -> null
        }
        return why?.let { TopologyFinding("door.commands.$name", it) }
    }
}

// What a door command named `claude` is told: that name is Claude Code's own, which the door already runs.
private const val CLAUDE_TAKEN = "is Claude Code's own command, which the door already runs; give this one another name"
