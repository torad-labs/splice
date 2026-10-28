package splice.app.control.peer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import splice.sessions.peer.codex.NativeProcess
import splice.sessions.peer.owner.PeerOwnerProcesses

/** Registration distinguishes an intentionally persistent peer from an invalid claimed native owner. */
internal sealed class PeerOwnerInput {
    data object Persistent : PeerOwnerInput()
    data class Owned(val process: NativeProcess) : PeerOwnerInput()
    data object Invalid : PeerOwnerInput()
}

internal class PeerOwnerInputs {
    private val processes = PeerOwnerProcesses()

    fun parse(body: JsonObject?): PeerOwnerInput {
        if (body?.containsKey("owner") != true) return PeerOwnerInput.Persistent
        return owned(body["owner"] as? JsonObject)
    }

    private fun owned(owner: JsonObject?): PeerOwnerInput {
        val pid = (owner?.get("pid") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0L }
        val start = PeerReplies.string(owner, "procStart")?.takeIf(String::isNotBlank)
        val domain = PeerReplies.string(owner, "pidDomain")?.takeIf(String::isNotBlank)
        val process = pid?.let { id -> start?.let { begun -> domain?.let { NativeProcess(id, begun, it) } } }
        return process?.takeIf(processes::live)?.let { PeerOwnerInput.Owned(it) } ?: PeerOwnerInput.Invalid
    }
}
