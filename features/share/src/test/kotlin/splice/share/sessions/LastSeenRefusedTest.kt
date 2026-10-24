// NEW: LAN share, round 13 of PR #419's review. The goal: a joined machine's request is answered whatever happens to
// the bookkeeping beside it. Each guarded request counts toward that machine's last seen, which the daemon writes about
// once a minute; a disk that refuses that write is said in the log, and the request, a relayed turn among them, is
// answered as it would have been. torad's activity file is a folder with a file in it, so no write can replace it,
// over TwoMachinesTest's real listener.
package splice.share.sessions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.share.ShareState
import splice.share.remote.RemoteTrust
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

internal class LastSeenRefusedTest : TwoMachinesTest() {

    @Test
    fun `a last seen the disk will not take fails no request`() {
        val activity = ShareState(torad.paths).clientsFile.resolveSibling("activity.json")
        Files.createDirectories(activity)
        Files.writeString(activity.resolve("in-the-way"), "")
        val (info, joined) = joined()
        val http = HttpClient.newBuilder().sslContext(RemoteTrust(info.fingerprint, "torad").context).build()
        val health = HttpRequest.newBuilder(URI.create("https://127.0.0.1:$port/share/v1/health"))
            .header("Authorization", "Bearer ${joined.token}")
            .GET()
            .build()

        val answered = (1..2).map { http.send(health, HttpResponse.BodyHandlers.ofString()) }

        assertEquals(listOf(200, 200), answered.map { it.statusCode() }, answered.joinToString { it.body() })
    }
}
