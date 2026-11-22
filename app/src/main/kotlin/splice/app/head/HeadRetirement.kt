// NEW: head-catalog (gw-retire). `splice claude retire` and `splice claude restore` on the daemon, which holds the
// heads, their live sessions and the lane `claude` is on. Retiring a head moves its command link and everything
// splice laid in its own Claude folder (HeadFolderLayout) into a dated backup (HeadBackup) and records it
// (CommandRetirements), so no install lays the link again and a launch of the command is refused saying how to come
// back. The head stays in splice.toml: its provider and login keep serving the Splice gateway, and its models stay in
// `claude`'s list under the same selectors. A head a live session runs on is refused, the sessions named, and nothing
// is stopped. The head `claude` runs on has no link or folder of its own: retiring it puts `claude` on the Splice
// gateway the way `splice claude door` does, and its backup records the lane it was on. Restoring puts back every
// entry exactly as it was, and refuses a head where a path changed since, naming it. Not HeadRemoval, which drops a
// head from splice.toml.
package splice.app.head

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.app.desktop.lane.HEAD_LANE
import splice.client.HeadFolderLayout
import splice.core.config.StatePaths
import splice.core.retired.CommandRetirement
import splice.core.retired.CommandRetirements
import splice.core.topology.CLAUDE_COMMAND
import splice.core.util.SafeFailureText
import splice.sessions.registry.SessionRecord
import java.io.IOException
import java.nio.file.Path
import java.time.LocalDateTime

/** One head as retiring reads it: its [key], the [command] a person types, its own Claude [folder] (null for the head
 *  `claude` runs on, which has none), the [selector] its pinned model runs as in `claude`, and whether its command
 *  starts on the Splice [gateway] (LaunchSpec.startsOnGateway). */
internal data class RetiringHead(
    val key: String,
    val command: String,
    val folder: Path?,
    val selector: String?,
    val gateway: Boolean,
)

/** The launchable heads, read at each ask. */
internal fun interface RetiringHeads {
    operator fun invoke(): List<RetiringHead>
}

internal class HeadRetirement(
    private val heads: RetiringHeads,
    statePaths: StatePaths,
    private val facts: HeadRemovalFacts,
    private val links: WrapperLinks,
    private val lane: RetiringLane,
    private val home: Path,
) {
    private val lock = Mutex()
    private val records = CommandRetirements(statePaths.stateDir)
    private val backup = HeadBackup(statePaths.headBackupsDir)

    /** `splice claude retire <command|key>...`, or every head the install still has with [all]. */
    suspend fun retire(names: List<String>, all: Boolean): JsonObject = lock.withLock {
        val known = heads()
        val chosen = if (all) {
            known.filter { records.of(it.key) == null }.map { it.key to it }
        } else {
            names.map { it to find(known, it) }
        }
        val said = mutableListOf<String>()
        if (chosen.isEmpty()) said.add("no custom head is left to retire: `claude` on the Splice gateway is the way")
        val ok = chosen.map { (name, head) -> retireOne(name, head, said) }.all { it }
        answer(ok, said)
    }

    /** `splice claude restore <command|key>...`, or every retired head with [all]. */
    suspend fun restore(names: List<String>, all: Boolean): JsonObject = lock.withLock {
        val known = heads()
        val chosen = if (all) records.all().map { it.command to it } else names.map { it to records.of(it) }
        val said = mutableListOf<String>()
        if (chosen.isEmpty()) said.add("no head is retired")
        val ok = chosen.map { (name, record) ->
            when {
                record != null -> restoreOne(record, said)
                find(known, name) != null -> true.also { said.add("`$name` is not retired: nothing to restore") }
                else -> false.also { said.add("no head named `$name`") }
            }
        }.all { it }
        answer(ok, said)
    }

    /** What `splice claude check` reads of the daemon: the lane `claude` is on, and each launchable head with its
     *  command, its own folder, whether its launch starts on the Splice gateway, and whether it is retired. */
    fun launchFacts(): JsonObject = buildJsonObject {
        lane.lane()?.let { put("lane", it) }
        putJsonArray("heads") {
            heads().forEach { head ->
                addJsonObject {
                    put("key", head.key)
                    put("command", head.command)
                    put("folder", head.folder?.toString())
                    put("gateway", head.gateway)
                    put("retired", records.of(head.key) != null)
                }
            }
        }
    }

    private suspend fun retireOne(name: String, head: RetiringHead?, said: MutableList<String>): Boolean {
        val live = head?.let { facts.runningOn(it.key) }.orEmpty()
        return when {
            head == null -> false.also { said.add("no head named `$name`") }
            records.of(head.key) != null -> true.also { said.add("`${head.command}` is already retired") }
            live.isNotEmpty() -> false.also { said.add(liveRefusal(head, live)) }
            else -> retired(head, said)
        }
    }

    /** [head] backed up, removed and recorded; false once why it could not be is said, with nothing changed. */
    private suspend fun retired(head: RetiringHead, said: MutableList<String>): Boolean {
        val onLane = lane.lane().takeIf { head.command == CLAUDE_COMMAND }
        val contents = head.folder?.let { HeadFolderLayout.contents(it, home) }
        val plan = RetirePlan(
            head.folder,
            links.own(head.command),
            contents?.laid.orEmpty().map { it.path to it.kind },
            contents?.state,
        )
        val done = try {
            backup.retire(head.key, head.command, plan, onLane)
        } catch (failure: IOException) {
            val why = SafeFailureText.render(failure)
            said.add("`${head.command}` is not retired: its backup could not be written ($why); nothing of it changed")
            return false
        }
        val at = LocalDateTime.now().toString()
        records.retire(CommandRetirement(head.key, head.command, head.selector, done.backup.toString(), at))
        said.add("retired `${head.command}`: its command and splice's settings are in the backup at ${done.backup}")
        if (onLane == HEAD_LANE) said.add(lane.toGateway())
        contents?.others?.forEach { said.add("left as it is, not splice's: $it") }
        done.stuck.forEach { said.add("$it could not be removed; the backup holds it too") }
        if (head.command != CLAUDE_COMMAND) said.add(modelsStay(head))
        return true
    }

    private suspend fun modelsStay(head: RetiringHead): String {
        val selectors = lane.selectors(head.key)
        val pick = head.selector?.let { "/model $it" } ?: "/model"
        return if (selectors.isEmpty()) {
            "`${head.command}`'s models stay with its head on the Splice gateway: run `claude` and type `$pick`"
        } else {
            "`${head.command}`'s models stay in `claude`'s list on the Splice gateway as " +
                "${selectors.joinToString(", ")}: run `claude` and type `$pick`"
        }
    }

    private fun liveRefusal(head: RetiringHead, live: List<SessionRecord>): String {
        val sessions = live.joinToString(", ") { listOfNotNull(it.sessionId, it.name).joinToString(" ") }
        return "`${head.command}` is not retired: a live session still runs on it ($sessions). Nothing of it " +
            "changed and no session was stopped; retire it once they end"
    }

    private suspend fun restoreOne(record: CommandRetirement, said: MutableList<String>): Boolean {
        val dir = Path.of(record.backup)
        val manifest = backup.manifest(dir)
        val refusals = if (manifest == null) {
            listOf("its backup at $dir cannot be read")
        } else {
            backup.conflicts(manifest).map { "$it changed since it was retired, so restore leaves it" }
        }
        refusals.forEach { said.add("`${record.command}` stays retired: $it") }
        return manifest != null && refusals.isEmpty() && restored(record, dir, manifest, said)
    }

    /** [record] put back from the backup at [dir]; false once why it could not be is said. */
    private suspend fun restored(
        record: CommandRetirement,
        dir: Path,
        manifest: BackupManifest,
        said: MutableList<String>,
    ): Boolean {
        try {
            backup.restore(dir, manifest)
        } catch (failure: IOException) {
            val why = SafeFailureText.render(failure)
            said.add("`${record.command}` stays retired: its backup could not be put back ($why)")
            return false
        }
        records.restored(record.key)
        if (manifest.lane == HEAD_LANE) said.add(lane.toHead())
        said.add("restored `${record.command}` from its backup at $dir, which stays where it is")
        return true
    }

    private fun find(known: List<RetiringHead>, name: String): RetiringHead? =
        known.firstOrNull { it.key == name } ?: known.firstOrNull { it.command == name }

    private fun answer(ok: Boolean, said: List<String>): JsonObject = buildJsonObject {
        put("ok", ok)
        putJsonArray("said") { said.forEach { add(JsonPrimitive(it)) } }
    }
}

/** The lane `claude` is on, as retiring the head `claude` runs on moves it. */
internal interface RetiringLane {
    /** The lane now (door or head), or null before the daemon has opened the Splice gateway. */
    fun lane(): String?

    /** `claude` put on the Splice gateway as `splice claude door` puts it, said. */
    suspend fun toGateway(): String

    /** `claude` put back on its head as `splice claude head` puts it, said. */
    suspend fun toHead(): String

    /** The selectors [key]'s models run as in `claude`'s list, as `splice claude --json` lists them. */
    suspend fun selectors(key: String): List<String>
}
