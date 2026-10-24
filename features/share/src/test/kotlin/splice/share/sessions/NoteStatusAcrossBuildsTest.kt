// NEW: LAN share, round 17 of PR #419's review. The goal: a session here that writes to another machine's session is
// told what became of the note there, as that machine answers it, whatever splice build it runs. A status it names that
// this build knows reaches the sender as that status; a note on its way or delivered tells it nothing, as Claude Code's
// own peers do; a denial there reaches it as a refusal saying it was denied, since a session drops a denied receipt,
// which comes with no token; and a status this build does not know is never read as on its way: the sender is told the
// note may not have arrived, with what the other machine said. Each machine installs betas on its own through the day,
// so a newer build answering a status an older one lacks is a matter of hours. The other machine is a stand-in
// answering as a build this one is not, over a certificate this machine pins as it pins a real one; the link, the
// mirror and the session that writes are splice's real code and Claude Code's own shapes.
package splice.share.sessions

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.testing.TestPorts
import splice.core.topology.RemoteConfig
import splice.core.topology.Topology
import splice.sessions.peer.DeliveryStatus
import splice.share.MachinesJoined
import splice.share.SessionsSwitch
import splice.share.ShareCertificate
import splice.share.ShareState
import splice.share.remote.RemoteLinks
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.random.Random

// why: a fixed seed, so a failing draw of statuses is the same draw next time.
private const val STATUS_SEED = 17

// why: enough notes that every known status, none, and several unknown ones each come up.
private const val STATUS_DRAWS = 30

// why: what this machine keys the stand-in by in `[remotes]`, and the machine part of its mirrors' names.
private const val NEWER = "newer"

// why: the one session the stand-in lists.
private const val FAR_ID = "far-1"
private const val FAR_NAME = "far"

// why: statuses a newer or an older build might answer that this one has no word for, and some no build would.
private val UNKNOWN_STATUSES = listOf("forwarded", "queued", "DELIVERED", "Held", "", " ", "délivré", "x".repeat(300))

internal class NoteStatusAcrossBuildsTest : TwoMachinesTest() {

    /** What the stand-in answers each note with, by the note's text: its status's wire word (none when null) and its
     *  detail. By text, since a note that gets no receipt may reach it after the next one is drawn. */
    private val answers = ConcurrentHashMap<String, Pair<String?, String>>()

    private var standIn: HttpsServer? = null

    @AfterEach
    fun stopStandIn() {
        standIn?.stop(0)
    }

    /** A machine of another build sharing on a port of its own: it lists one session, takes this machine's offer, has
     *  nothing in its outbox, and answers each note as [answers] holds for it. Its certificate's fingerprint, which
     *  this machine pins. */
    private fun newerBuild(port: Int): String {
        val tls = ShareCertificate(tmp.resolve("newer-tls.pem")).load(listOf("localhost"))
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(tls.keyStore, tls.password) }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.httpsConfigurator = HttpsConfigurator(context)
        server.createContext("/share/v1/sessions") { exchange -> answered(exchange) }
        server.start()
        standIn = server
        return tls.fingerprint
    }

    private fun answered(exchange: HttpExchange) {
        val asked = String(exchange.requestBody.readAllBytes())
        val path = exchange.requestURI.path
        val (code, body) = when {
            path.endsWith("/outbox") -> 503 to """{"error":"no outbox here"}"""
            path.endsWith("/message") -> 200 to buildJsonObject {
                val text = Json.parseToJsonElement(asked).jsonObject["text"]?.jsonPrimitive?.content
                val answer = answers[text] ?: (null to "")
                answer.first?.let { put("status", it) }
                put("detail", answer.second)
            }.toString()
            exchange.requestMethod == "GET" -> 200 to """{"sessions":[{"id":"$FAR_ID","name":"$FAR_NAME"}]}"""
            else -> 200 to """{"sessions":[]}"""
        }
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** mini's links once it joined the stand-in at [port], pinning [pin], with its Sessions switch on. */
    private fun joinedNewer(port: Int, pin: String): RemoteLinks {
        val store = store(mini)
        store.save(NEWER, "a-token")
        store.allowSessions(NEWER, true)
        val booted = Topology(remotes = mapOf(NEWER to RemoteConfig("https://127.0.0.1:$port", pin)))
        return RemoteLinks(booted, store, SessionsSwitch(ShareState(mini.paths), MachinesJoined { setOf(NEWER) }))
    }

    @Test
    fun `a note's status there reaches its sender, and one this build does not know is never read as on its way`() {
        val port = TestPorts.reserve()
        val links = joinedNewer(port, newerBuild(port))
        val here = mini.session("here")
        link(links)
        val mirror = "$NEWER/$FAR_NAME"
        assertTrue(mirror in listed(here) { mirror in it }, "the other machine's session is listed here")

        val random = Random(STATUS_SEED)
        val statuses = DeliveryStatus.entries.map { it.wire } + listOf<String?>(null) + UNKNOWN_STATUSES
        repeat(STATUS_DRAWS) { draw ->
            val status = statuses[random.nextInt(statuses.size)]
            val tag = "draw $draw"
            answers["note $draw"] = status to tag
            val known = DeliveryStatus.entries.firstOrNull { it.wire == status }

            here.send(mirror, "note $draw")

            val at = "$tag, the other machine answered ${status?.let { "\"${it.take(20)}\"" } ?: "no status"}"
            when {
                status == null || known == DeliveryStatus.DELIVERED -> Unit
                known == DeliveryStatus.DENIED -> here.told(at, DeliveryStatus.REFUSED, listOf("$NEWER denied it", tag))
                known != null -> {
                    assertEquals(Receipt(status, tag), here.receipt(SETTLE_MS / 1_000), "$at: the sender is told it")
                }
                else -> here.told(at, DeliveryStatus.DROPPED, listOf(tag))
            }
        }
        assertNull(here.receipt(1), "no note on its way or delivered told its sender anything")
    }

    /** The sender here was told [status], never left reading the note as on its way, in words that say each of
     *  [words]. */
    private fun ClaudeCodeSession.told(at: String, status: DeliveryStatus, words: List<String>) {
        val receipt = receipt(SETTLE_MS / 1_000)
        assertNotNull(receipt, "$at: the sender is told, never left reading it as on its way")
        assertEquals(status.wire, receipt?.status, "$at: as $status")
        words.forEach { assertTrue(it in receipt?.detail.orEmpty(), "$at: saying \"$it\": ${receipt?.detail}") }
    }
}
