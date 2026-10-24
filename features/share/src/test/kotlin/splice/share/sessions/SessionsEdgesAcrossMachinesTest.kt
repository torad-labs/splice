// NEW: LAN share, round 11 of PR #419's review: where sessions across machines met a slow disk, a dying mirror, a revoke
// and a switch turned off. The goal: the listener keeps answering turns and joins whatever a look at sessions waits on;
// a session another machine writes from is named here as that machine's looks name it, so a reply to that name reaches
// it; a machine revoked here leaves the listing at once, and a note written to it after is refused in words; and a
// machine whose Sessions switch goes off takes no note from this one from that moment. Two machines in one JVM, the rig
// in TwoMachinesTest.
package splice.share.sessions

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.sessions.peer.DeliveryStatus
import splice.share.ShareInfo
import splice.share.remote.RemoteJoined
import splice.share.remote.RemoteSessions
import splice.share.remote.RemoteTrust
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.listDirectoryEntries

// why: an answer the listener gives at once, given generous room on a loaded machine.
private const val ANSWER_S = 5L

internal class SessionsEdgesAcrossMachinesTest : TwoMachinesTest() {

    private fun http(info: ShareInfo): HttpClient =
        HttpClient.newBuilder().sslContext(RemoteTrust(info.fingerprint, "torad").context).build()

    /** The pid of the record named [name] in [registry]: a mirror's is its anchor's. */
    private fun pidOf(registry: Path, name: String): Long = registry.listDirectoryEntries("*.json")
        .map { Json.parseToJsonElement(Files.readString(it)).jsonObject }
        .single { it["name"]?.jsonPrimitive?.content == name }
        .getValue("pid").jsonPrimitive.content.toLong()

    /** A note from mini's session [fromId] named [fromName] to torad's session [to], as mini's carrier sends one. */
    private fun note(joining: Pair<ShareInfo, RemoteJoined.Joined>, to: String, fromId: String, fromName: String) {
        val (info, joined) = joining
        val body = """{"from":{"id":"$fromId","name":"$fromName"},"text":"from a mirror that died"}"""
        val asked = HttpRequest.newBuilder(URI.create("https://127.0.0.1:$port/share/v1/sessions/$to/message"))
            .header("Authorization", "Bearer ${joined.token}")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofMillis(SETTLE_MS))
            .build()
        http(info).send(asked, HttpResponse.BodyHandlers.ofString())
    }

    /** [request] sent, once torad's guard let it through: when torad last heard from mini moved. */
    private fun admitted(request: () -> CompletableFuture<*>): CompletableFuture<*> = runBlocking {
        val before = heardFrom(MINI)
        val sent = request()
        val until = System.currentTimeMillis() + SETTLE_MS
        while (heardFrom(MINI) == before && System.currentTimeMillis() < until) delay(POLL_MS)
        sent
    }

    @Test
    fun `the listener still answers while two looks at this machine's sessions wait on a slow registry`() {
        val (info, joined) = joined()
        // A record whose read waits, as on a slow or network disk: a named pipe, read only once something writes it.
        val slow = torad.registry.resolve("99999.json")
        assertEquals(0, ProcessBuilder("mkfifo", slow.toString()).start().waitFor(), "a named pipe was made")
        val look = HttpRequest.newBuilder(URI.create("https://127.0.0.1:$port/share/v1/sessions"))
            .header("Authorization", "Bearer ${joined.token}")
            .build()
        val looks = List(2) { admitted { http(info).sendAsync(look, HttpResponse.BodyHandlers.ofString()) } }
        try {
            val asked = HttpRequest.newBuilder(URI.create("https://127.0.0.1:$port/share/v1/info"))
                .timeout(Duration.ofSeconds(ANSWER_S))
                .build()
            val answered = runCatching { http(info).send(asked, HttpResponse.BodyHandlers.ofString()).statusCode() }

            assertEquals(200, answered.getOrNull(), "its description answered: ${answered.exceptionOrNull()}")
        } finally {
            // The slow disk answers until every look has read past it, however late a look reached it: opened both
            // ways, the pipe never waits for a reader, and each close ends the reads that were waiting on it.
            runBlocking {
                val until = System.currentTimeMillis() + SETTLE_MS
                while (looks.any { !it.isDone } && System.currentTimeMillis() < until) {
                    RandomAccessFile(slow.toFile(), "rw").use { it.write("{}".toByteArray()) }
                    delay(POLL_MS)
                }
            }
            Files.deleteIfExists(slow)
            looks.forEach { look -> look.handle { _, _ -> }.get(SETTLE_MS, TimeUnit.MILLISECONDS) }
        }
    }

    @Test
    fun `a note from a session whose mirror died here is written from a mirror named as the next look names it`() {
        val rig = torad.session("rig-lead")
        val rigId = torad.mirrors.local().single { it.name == "rig-lead" }.id
        val joining = joined()
        assertEquals(200, offer(listOf("aaaaaaaa-1" to "agent", "bbbbbbbb-2" to "agent"), joining))
        listed(rig) { "$MINI/agent-bbbbbbbb" in it }
        // The mirror's anchor exits, as when something kills its /bin/cat.
        ProcessHandle.of(pidOf(torad.registry, "$MINI/agent-bbbbbbbb")).ifPresent { anchor ->
            anchor.destroyForcibly()
            anchor.onExit().get(SETTLE_MS, TimeUnit.MILLISECONDS)
        }

        note(joining, rigId, "bbbbbbbb-2", "agent")

        val got = checkNotNull(rig.heard(SETTLE_MS / 1_000)) { "torad's session took the note" }
        assertEquals("$MINI/agent-bbbbbbbb", got.fromName, "named as mini's looks name it, beside its namesake")
    }

    @Test
    fun `a machine revoked here leaves this machine's listing at once`() {
        val rig = torad.session("rig-lead")
        mini.session("splice")
        offered(links(joined().second))
        listed(rig) { "$MINI/splice" in it }

        assertTrue(listener.revoke(MINI), "mini was revoked")

        val left = listed(rig) { agents -> agents.none { it.startsWith("$MINI/") } }
        assertTrue(left.none { it.startsWith("$MINI/") }, "mini's sessions left torad's listing: $left")
    }

    @Test
    fun `a note written to the mirror of a machine revoked from another process is refused, and its sender told`() {
        val rig = torad.session("rig-lead")
        mini.session("splice")
        offered(links(joined().second))
        listed(rig) { "$MINI/splice" in it }
        // `splice share revoke` runs in a process of its own: it changes the book, and the mirrors last their time.
        assertTrue(listener.clients.revoke(MINI), "mini was revoked")

        rig.send("$MINI/splice", "after the revoke")

        val receipt = checkNotNull(rig.receipt(SETTLE_MS / 1_000)) { "torad's session heard what became of it" }
        assertEquals(DeliveryStatus.REFUSED.wire, receipt.status, "$receipt")
        assertTrue(MINI in receipt.detail.orEmpty(), "it names the machine: $receipt")
    }

    @Test
    fun `a note to a machine's mirror after its Sessions switch went off here is refused, and its sender told`() {
        val rig = torad.session("rig-lead")
        val splice = mini.session("splice")
        // A link whose next look is past the test's wait: the mirror its first look made stays up after the switch.
        checkNotNull(RemoteSessions(links(joined().second), mini.mirrors, { }, SLOW_LOOK_MS).start(scope))
        listed(splice) { "$toradName/rig-lead" in it }

        store(mini).allowSessions(toradName, false)
        splice.send("$toradName/rig-lead", "after the switch went off")

        val receipt = checkNotNull(splice.receipt(SETTLE_MS / 1_000)) { "mini's session heard what became of it" }
        assertEquals(DeliveryStatus.REFUSED.wire, receipt.status, "$receipt")
        assertTrue("Sessions" in receipt.detail.orEmpty(), "it says the Sessions switch is off: $receipt")
        assertNull(rig.heard(1), "and nothing reached torad's session")
    }
}
