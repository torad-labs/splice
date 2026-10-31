package splice.app.control.peer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.sessions.peer.codex.NativeProcess
import splice.sessions.peer.owner.PeerOwnerProcesses

class PeerOwnerInputsTest {
    private val inputs = PeerOwnerInputs()

    @Test
    fun `management owner input requires the live process's whole kernel identity`() {
        val owner = checkNotNull(PeerOwnerProcesses().capture(ProcessHandle.current().pid()))
        assertEquals(PeerOwnerInput.Owned(owner), inputs.parse(body(owner)))
        assertEquals(PeerOwnerInput.Invalid, inputs.parse(body(owner.copy(procStart = owner.procStart + "0"))))
        assertEquals(PeerOwnerInput.Invalid, inputs.parse(body(owner.copy(pidDomain = owner.pidDomain + "-foreign"))))
        assertEquals(PeerOwnerInput.Invalid, inputs.parse(body(owner.copy(pid = -1))))
        assertEquals(
            PeerOwnerInput.Invalid,
            inputs.parse(buildJsonObject { put("owner", buildJsonObject { put("pid", owner.pid) }) }),
        )
    }

    @Test
    fun `an absent owner stays persistent while malformed owner input is refused`() {
        assertEquals(PeerOwnerInput.Persistent, inputs.parse(JsonObject(emptyMap())))
        assertEquals(PeerOwnerInput.Invalid, inputs.parse(buildJsonObject { put("owner", JsonPrimitive("unknown")) }))
    }

    private fun body(owner: NativeProcess): JsonObject = buildJsonObject {
        put(
            "owner",
            buildJsonObject {
                put("pid", owner.pid); put("procStart", owner.procStart); put("pidDomain", owner.pidDomain)
            },
        )
    }
}
