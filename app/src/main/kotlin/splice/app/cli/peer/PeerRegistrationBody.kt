package splice.app.cli.peer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.sessions.peer.owner.PeerOwnerProcesses
import java.nio.file.Path

/** Registration includes kernel-proved native ancestry; it never mistakes its short-lived shell for the harness.
 *  `--owner <pid>` names the owner instead, for a bot, script or harness that registers by hand. */
internal object PeerRegistrationBody {
    /** The register body, or null when `--owner` names no running process. */
    fun build(name: String, values: Map<String, String>): JsonObject? {
        val processes = PeerOwnerProcesses()
        val asked = values["--owner"]
        val owner = if (asked == null) {
            processes.ancestor(setOf("codex", "claude"))
        } else {
            asked.toLongOrNull()?.let(processes::capture) ?: return null
        }
        return buildJsonObject {
            put("name", name)
            owner?.let {
                putJsonObject("owner") {
                    put("pid", it.pid); put("procStart", it.procStart); put("pidDomain", it.pidDomain)
                }
            }
            values["--cwd"]?.let { put("cwd", Path.of(it).toAbsolutePath().normalize().toString()) }
            values["--send-to"]?.let { list ->
                val names = list.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                putJsonArray("sendTo") { names.forEach { add(JsonPrimitive(it)) } }
            }
        }
    }
}
