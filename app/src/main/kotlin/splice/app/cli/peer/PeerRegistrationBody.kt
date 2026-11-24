package splice.app.cli.peer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.util.EnvReader
import splice.sessions.peer.codex.NativeProcess
import splice.sessions.peer.owner.PeerOwnerProcesses
import java.nio.file.Path

internal const val PEER_HARNESS_PROGRAMS_ENV: String = "SPLICE_PEER_HARNESS_PROGRAMS"

/** Registration includes kernel-proved native ancestry; it never mistakes its short-lived shell for the harness.
 *  `--owner <pid>` names the owner instead, for a bot, script or harness that registers by hand. */
internal object PeerRegistrationBody {
    /** Extra harness binary names configured via `SPLICE_PEER_HARNESS_PROGRAMS` (comma-separated; plain shells ignored). */
    fun extraPrograms(env: EnvReader, processes: PeerOwnerProcesses = PeerOwnerProcesses()): Set<String> =
        env(PEER_HARNESS_PROGRAMS_ENV)
            ?.split(",")
            ?.map(String::trim)
            ?.filter { it.isNotEmpty() && !processes.isPlainShell(it) }
            ?.toSet()
            .orEmpty()

    /** The register body, or null when `--owner` names no running process. */
    fun build(
        name: String,
        values: Map<String, String>,
        env: EnvReader = EnvReader(System::getenv),
        processes: PeerOwnerProcesses = PeerOwnerProcesses(),
    ): JsonObject? {
        val asked = values["--owner"]
        val owner = if (asked == null) {
            processes.harnessAncestor(extraPrograms(env))?.process
        } else {
            asked.toLongOrNull()?.let(processes::capture) ?: return null
        }
        return buildOwned(name, values, owner)
    }

    /** Builds a `/api/peers` registration body with an already-resolved [owner] (null for persistent peers). When
     *  [nativeInbox] is true, the daemon skips spawning a `peer-host` helper and routes sends through the owner's own
     *  live session inbox socket. */
    fun buildOwned(
        name: String,
        values: Map<String, String>,
        owner: NativeProcess?,
        nativeInbox: Boolean = false,
        harness: String? = null,
    ): JsonObject = buildJsonObject {
        put("name", name)
        if (nativeInbox) put("nativeInbox", true)
        harness?.let { put("harness", it) }
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
