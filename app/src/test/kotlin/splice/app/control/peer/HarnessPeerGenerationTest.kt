package splice.app.control.peer

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.harness.HarnessCatalog
import splice.core.harness.HarnessConfig
import splice.core.harness.HarnessPeerConfig
import splice.core.util.LogSink
import splice.sessions.note.PeerTarget
import splice.sessions.peer.PeerSender
import splice.sessions.peer.SendOutcome
import splice.sessions.peer.owner.PeerOwnerProcesses
import splice.sessions.peer.store.PeerRegistration
import splice.sessions.peer.store.PeerRequest
import splice.sessions.peer.store.PeerStore
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import java.nio.file.Files
import java.nio.file.Path

class HarnessPeerGenerationTest {
    @Test
    fun `a send admitted for an exited owner does not reach its same-name replacement`(@TempDir dir: Path): Unit =
        runBlocking {
            val output = dir.resolve("inbox")
            val store = PeerStore(dir)
            val drivers = drivers(store, output)
            val processes = PeerOwnerProcesses()
            val child = ProcessBuilder("/bin/cat").start()
            val old = try {
                val owner = checkNotNull(processes.capture(child.pid()))
                val request = PeerRequest("local-peer", dir.toString(), listOf("*"), owner, harness = "local-agent")
                assertInstanceOf(PeerRegistration.Registered::class.java, store.register(request, emptySet()))
                drivers.resolve(request.name)
            } finally {
                child.destroyForcibly()
                child.waitFor()
            }
            val newOwner = checkNotNull(processes.capture(ProcessHandle.current().pid()))
            val request = PeerRequest("local-peer", dir.toString(), listOf("*"), newOwner, harness = "local-agent")
            val replacement = assertInstanceOf(
                PeerRegistration.Registered::class.java,
                store.register(request, emptySet()),
            )
            val target = assertInstanceOf(PeerTarget.One::class.java, old.target).record
            assertEquals(target.sessionId, replacement.config.sessionId)
            val sender = PeerSender("/tmp/1.sock", "sender")
            val delivery = drivers.deliver(HarnessPeerMessage(target, "pinned", sender, "id", old.local))
            assertInstanceOf(SendOutcome.Refused::class.java, delivery)
            assertFalse(Files.exists(output), "the new owner received the old owner's message")
        }

    private fun drivers(store: PeerStore, output: Path): HarnessPeerDrivers {
        val config = HarnessConfig(
            name = "Local Agent",
            protocol = "acp",
            argv = listOf("/bin/false"),
            peer = HarnessPeerConfig("command", true, listOf("/usr/bin/tee", output.toString())),
        )
        val catalog = HarnessCatalog(mapOf("local-agent" to config))
        val registry = object : SessionSource {
            override fun read(): List<SessionRecord> = emptyList()
            override fun list(): SessionListing = SessionListing(read())
        }
        val services = PeerServiceSource { PeerServices() }
        return HarnessPeerDrivers(PeerParts(store, registry, null, services, LogSink {}, catalog))
    }
}
