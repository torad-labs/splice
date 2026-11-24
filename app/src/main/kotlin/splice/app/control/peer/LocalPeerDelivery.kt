package splice.app.control.peer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.harness.HarnessPeerProtocol
import splice.core.process.ProcessTree
import splice.core.wire.HttpStatus
import splice.sessions.note.PeerWire
import splice.sessions.peer.PeerSubmission
import splice.sessions.peer.SendOutcome
import splice.sessions.peer.owner.PeerOwnerProcesses
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// why: a stuck program or loopback listener must release the sending request promptly.
private const val LOCAL_DELIVERY_MS = 3_000L

// why: bound memory independently of registrations while allowing different peers to deliver concurrently.
private const val LOCAL_DELIVERY_LANES = 256

/** Command and loopback HTTP consume framed data, never a credential or shell command. */
internal class LocalPeerDelivery(private val io: CoroutineDispatcher) : HarnessPeerDriver {
    private val processes = PeerOwnerProcesses()
    private val locks = Array(LOCAL_DELIVERY_LANES) { Mutex() }
    private val deadline = Duration.ofMillis(LOCAL_DELIVERY_MS)
    private val client by lazy {
        HttpClient.newBuilder().connectTimeout(deadline).proxy(ProxySelector.of(null)).build()
    }

    override suspend fun deliver(message: HarnessPeerMessage): SendOutcome {
        val binding = message.local ?: return SendOutcome.Failed("the peer has no local delivery binding")
        val lock = locks[message.target.sessionId.hashCode().mod(locks.size)]
        if (!lock.tryLock()) return SendOutcome.Failed("the local peer delivery is busy")
        return try {
            runInterruptible(io) {
                if (processes.live(binding.owner)) {
                    handOff(message, binding)
                } else {
                    SendOutcome.Failed("the peer owner has exited")
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private fun handOff(message: HarnessPeerMessage, binding: LocalPeerBinding): SendOutcome {
        val address = message.sender.replyAddress.takeIf(PeerWire::attributeSafe)
        val framed = PeerWire.envelope(message.text, address, message.sender.name)
        val id = message.id ?: UUID.randomUUID().toString()
        return when (binding.config.driver) {
            HarnessPeerProtocol.COMMAND -> command(binding.config.deliverCmd, framed, id)
            HarnessPeerProtocol.WEBHOOK -> {
                val uri = binding.config.loopbackWebhook()
                    ?: return SendOutcome.Failed("the peer's webhook is not an HTTP loopback endpoint")
                webhook(uri, framed, id)
            }
            HarnessPeerProtocol.NATIVE, HarnessPeerProtocol.CODEX, HarnessPeerProtocol.ACP, null ->
                SendOutcome.Failed("the peer has no local delivery protocol")
        }
    }

    private fun command(argv: List<String>, framed: String, id: String): SendOutcome = try {
        val builder = ProcessBuilder(argv)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment().clear()
        runCommand(builder.start(), framed, id)
    } catch (_: IOException) {
        SendOutcome.Failed("the peer delivery command could not start")
    }

    private fun runCommand(process: Process, framed: String, id: String): SendOutcome {
        val end = System.nanoTime() + deadline.toNanos()
        val input = FutureTask {
            try {
                process.outputStream.use { it.write(framed.encodeToByteArray()) }
                true
            } catch (_: IOException) {
                false
            }
        }
        Thread.ofVirtual().start(input)
        return try {
            when {
                !process.waitFor(LOCAL_DELIVERY_MS, TimeUnit.MILLISECONDS) ->
                    SendOutcome.Failed("the peer delivery command timed out")
                process.exitValue() != 0 -> SendOutcome.Failed("the peer delivery command refused the message")
                input.get(end - System.nanoTime(), TimeUnit.NANOSECONDS) ->
                    SendOutcome.Submitted(id, PeerSubmission.DELIVERED)
                else -> SendOutcome.Failed("the peer delivery command did not accept the message")
            }
        } catch (_: TimeoutException) {
            SendOutcome.Failed("the peer delivery command timed out")
        } finally {
            if (process.isAlive) ProcessTree(process).kill()
            input.cancel(true)
        }
    }

    private fun webhook(uri: URI, framed: String, id: String): SendOutcome = try {
        val body = buildJsonObject {
            put("msgId", id)
            put("content", framed)
        }
        val request = HttpRequest.newBuilder(pinLoopback(uri)).timeout(deadline)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().close()
        when (response.statusCode()) {
            HttpStatus.ACCEPTED -> SendOutcome.Submitted(id, PeerSubmission.QUEUED)
            in HttpStatus.OK..HttpStatus.MAX_SUCCESS -> SendOutcome.Submitted(id, PeerSubmission.DELIVERED)
            else -> SendOutcome.Failed("the peer webhook refused the message (HTTP ${response.statusCode()})")
        }
    } catch (_: HttpTimeoutException) {
        SendOutcome.Failed("the peer webhook timed out")
    } catch (_: IOException) {
        SendOutcome.Failed("the peer webhook could not be reached")
    }

    /** No host lookup, proxy or redirect can move this POST off loopback. */
    private fun pinLoopback(uri: URI): URI {
        if (!uri.host.equals("localhost", ignoreCase = true)) return uri
        val authority = uri.rawAuthority.replaceFirst(uri.host, "127.0.0.1")
        return URI.create(uri.toASCIIString().replaceFirst(uri.rawAuthority, authority))
    }
}
