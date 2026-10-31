// Oct 28, 2026 (Marcos: "add the ability for anyone or anything to register to send and receive messages via splice
// peer"). Anything that registers by hand runs its register again whenever it starts, and a register that repeats one
// already there was refused ("a peer named '<name>' already exists"), so a restarted bot, script or harness had to
// revoke first and lost its journal's key. A register from the same owner, or from no owner where the peer has none,
// now answers the registration that is there: the same session id and key file, its cwd and send scope as the new
// register asks. A peer whose owner process ended passes to the new owner. A name another live process holds stays
// refused, and so does a peer with no owner asked for by a process.
package splice.sessions.peer.store

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.peer.codex.NativeProcess
import splice.sessions.peer.owner.PeerOwnerProcesses
import java.nio.file.Files
import java.nio.file.Path

private const val NAME = "grokbot"

class PeerRegisterAgainTest {
    private val processes = PeerOwnerProcesses()

    @Test
    fun `a register that repeats one answers that peer, with its key, and takes the new cwd and scope`(
        @TempDir dir: Path,
    ) = runBlocking {
        val store = PeerStore(dir.resolve("settings"))
        val first = registered(store.register(PeerRequest(NAME, dir.toString(), listOf("*")), emptySet()))
        val key = Files.readString(first.keyFile)

        val again = registered(store.register(PeerRequest(NAME, dir.toString(), listOf("*")), emptySet()))
        assertEquals(RegisterOutcome.SAME, again.outcome)
        assertEquals(first.config.sessionId, again.config.sessionId)
        assertEquals(key, Files.readString(again.keyFile), "the key stands")
        assertEquals(NAME, store.principal("Bearer ${key.trim()}")?.name, "and still opens the peer's routes")

        val elsewhere = Files.createDirectories(dir.resolve("elsewhere")).toString()
        val moved = registered(store.register(PeerRequest(NAME, elsewhere, listOf("temple")), emptySet()))
        assertEquals(RegisterOutcome.UPDATED, moved.outcome)
        assertEquals(first.config.sessionId, moved.config.sessionId)
        assertEquals(elsewhere, PeerStore(dir.resolve("settings")).get(NAME)?.cwd, "peer.json says the new cwd")
        assertEquals(listOf("temple"), moved.config.sendTo)
    }

    @Test
    fun `a peer passes from an owner that ended to the next, and a live owner's name stays refused`(
        @TempDir dir: Path,
    ) = runBlocking {
        val store = PeerStore(dir.resolve("settings"))
        val me = checkNotNull(processes.capture(ProcessHandle.current().pid()))
        val parent = checkNotNull(processes.capture(ProcessHandle.current().parent().orElseThrow().pid()))
        val ended = NativeProcess(me.pid, me.procStart + "0", me.pidDomain)
        val first = registered(store.register(PeerRequest(NAME, dir.toString(), listOf("*"), ended), emptySet()))

        val taken = registered(store.register(PeerRequest(NAME, dir.toString(), listOf("*"), me), emptySet()))
        assertEquals(RegisterOutcome.UPDATED, taken.outcome)
        assertEquals(first.config.sessionId, taken.config.sessionId)
        assertEquals(me, store.nativeOwners[NAME])

        val other = store.register(PeerRequest(NAME, dir.toString(), listOf("*"), parent), emptySet())
        assertEquals(PeerRefusal.TAKEN, assertInstanceOf(PeerRegistration.Refused::class.java, other).why)
        val unowned = store.register(PeerRequest("plain", dir.toString(), listOf("*")), emptySet())
        registered(unowned)
        val claimed = store.register(PeerRequest("plain", dir.toString(), listOf("*"), me), emptySet())
        assertEquals(PeerRefusal.TAKEN, assertInstanceOf(PeerRegistration.Refused::class.java, claimed).why)
        assertEquals(me, store.nativeOwners[NAME])
    }

    private fun registered(answer: PeerRegistration): PeerRegistration.Registered =
        assertInstanceOf(PeerRegistration.Registered::class.java, answer)
}
