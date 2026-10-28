package splice.app.cli.peer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.sessions.peer.owner.PeerOwnerProcesses
import java.nio.file.Path

/** Registration includes kernel-proved native ancestry; it never mistakes its short-lived shell for the harness. */
internal object PeerRegistrationBody {
    fun build(name: String, values: Map<String, String>): JsonObject = buildJsonObject {
        put("name", name)
        PeerOwnerProcesses().ancestor(setOf("codex", "claude"))?.let {
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
