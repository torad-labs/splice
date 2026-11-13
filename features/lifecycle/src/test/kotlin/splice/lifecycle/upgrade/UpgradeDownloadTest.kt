// A release asset over a real local HTTP server (2026-11-10): the 0.4.0 jar is about 473 MB, and the fetch it replaced
// failed any download that took over 300 s. Time here is a clock each test steps by hand, so "takes longer than five
// minutes" and "a minute with no byte" are what the download sees, not what the test waits.
package splice.lifecycle.upgrade

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import splice.core.util.ElapsedClock
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

internal class UpgradeDownloadTest {
    private val now = AtomicLong(0)
    private val pacing = DownloadPacing(stallMs = 60_000, lookMs = 5, pauseMs = 0, clock = ElapsedClock { now.get() })
    private val requests = AtomicInteger(0)
    private val ranges: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val ifRanges: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val released = CountDownLatch(1)
    private val pool = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val body = Random(7).nextBytes(1_000_000)

    @AfterEach
    fun stop() {
        released.countDown()
        server.stop(0)
        pool.shutdownNow()
    }

    /** Serves /splice.jar with [answer], given the exchange and which request this is (1 for the first). */
    private fun serve(answer: (HttpExchange, Int) -> Unit): String {
        server.executor = pool
        server.createContext("/splice.jar") { exchange ->
            ranges += exchange.requestHeaders.getFirst("Range")
            ifRanges += exchange.requestHeaders.getFirst("If-Range")
            cutsAllowed { answer(exchange, requests.incrementAndGet()) }
            exchange.close()
        }
        server.start()
        return "http://127.0.0.1:${server.address.port}/splice.jar"
    }

    /** Runs a server answer that may end by cutting the connection, which is what some answers are for. */
    private fun cutsAllowed(answer: () -> Unit) {
        try {
            answer()
        } catch (_: IOException) {
            // The connection was cut on purpose ([cut]) or by the client closing a stalled body.
        }
    }

    private fun whole(exchange: HttpExchange, bytes: ByteArray = body, chunk: Int = bytes.size) {
        exchange.responseHeaders.add("ETag", "\"v1\"")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        for (from in bytes.indices step chunk) {
            exchange.responseBody.write(bytes, from, minOf(chunk, bytes.size - from))
            exchange.responseBody.flush()
        }
    }

    /** The first [count] bytes of a 200, then the connection is closed under it. */
    private fun cut(exchange: HttpExchange, count: Int) {
        exchange.responseHeaders.add("ETag", "\"v1\"")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.write(body, 0, count)
        exchange.responseBody.flush()
        throw IOException("cut")
    }

    private fun rest(exchange: HttpExchange) {
        val from = exchange.requestHeaders.getFirst("Range").removePrefix("bytes=").removeSuffix("-").toInt()
        exchange.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
        exchange.sendResponseHeaders(206, (body.size - from).toLong())
        exchange.responseBody.write(body, from, body.size - from)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }

    private fun files(dir: Path): List<String> =
        Files.list(dir).use { all -> all.map { it.fileName.toString() }.toList() }

    /** What the person sees, with [onBytes] run after each received chunk. */
    private class Seen(private val lines: ProgressLines, private val onBytes: (Long) -> Unit) : DownloadProgress {
        val notes: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun received(done: Long, total: Long?) {
            lines.received(done, total)
            onBytes(done)
        }

        override fun note(line: String) {
            notes += line
            lines.note(line)
        }
    }

    @Test
    fun `a slow download that never stalls completes, however long it takes`(@TempDir dir: Path) {
        val big = Random(9).nextBytes(2_000_000)
        val url = serve { exchange, _ -> whole(exchange, big, chunk = 16 * 1024) }
        val printed = mutableListOf<String>()
        val lines = ProgressLines(TerminalOutput { printed += it }, "splice.jar", ElapsedClock { now.get() })
        // Every chunk arrives 40 s after the one before: never a minute without a byte, but minutes in all.
        val seen = Seen(lines) { now.addAndGet(40_000) }
        val target = dir.resolve("splice.jar")
        val got = JdkUpgradeFetch(pacing).download(url, target, sha(big), seen)
        assertEquals(Downloaded.Landed, got)
        assertTrue(big.contentEquals(Files.readAllBytes(target)))
        assertTrue(now.get() > 300_000, "the download ran ${now.get() / 1000}s, past the old 300 s limit")
        assertEquals(listOf("splice.jar"), files(dir), "the part file was renamed, nothing else left")
        val first = printed.first()
        assertTrue(first.contains("splice.jar") && first.endsWith("downloading 2.0 MB"), "$printed")
        assertTrue(printed.drop(1).any { it.contains(" of 2.0 MB (") && it.endsWith("%)") }, "$printed")
        assertEquals(1, requests.get(), "nothing broke, so nothing was asked again")
    }

    @Test
    fun `a download that stops moving fails with a sentence that says where it stalled`(@TempDir dir: Path) {
        val arrived = Semaphore(0)
        val url = serve { exchange, request ->
            if (request == 1) {
                exchange.responseHeaders.add("ETag", "\"v1\"")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body, 0, 100_000)
                exchange.responseBody.flush()
                assertTrue(arrived.tryAcquire(30, TimeUnit.SECONDS), "the client never read the first bytes")
            }
            // A minute and a second with nothing more: after the first bytes, then before any answer at all.
            now.addAndGet(61_000)
            released.await(30, TimeUnit.SECONDS)
        }
        val seen = Seen(ProgressLines(TerminalOutput { }, "splice.jar")) { done ->
            if (done >= 100_000) arrived.release()
        }
        val target = dir.resolve("splice.jar")
        val failed = assertThrows<UpgradeFetchFailed> {
            JdkUpgradeFetch(pacing).download(url, target, sha(body), seen)
        }
        assertEquals(
            "the download stalled (no answer for 60s) at 0.1 MB of 1.0 MB, and 3 tries in a row got no further; " +
                "check the connection, then try again",
            failed.why,
        )
        val firstNote = seen.notes.first()
        assertTrue(firstNote.startsWith("the download stalled (no data for 60s); resuming at 0.1 MB"), "${seen.notes}")
        assertEquals(4, requests.get(), "the first leg, then three that brought nothing")
        assertEquals("bytes=100000-", ranges[1], "each retry asked for the rest, not the whole file")
        assertEquals(emptyList<String>(), files(dir), "neither the asset nor its part file is left")
    }

    @Test
    fun `a cut connection resumes from where it stopped`(@TempDir dir: Path) {
        val url = serve { exchange, request -> if (request == 1) cut(exchange, 400_000) else rest(exchange) }
        val seen = Seen(ProgressLines(TerminalOutput { }, "splice.jar")) { }
        val target = dir.resolve("splice.jar")
        assertEquals(Downloaded.Landed, JdkUpgradeFetch(pacing).download(url, target, sha(body), seen))
        assertTrue(body.contentEquals(Files.readAllBytes(target)))
        assertEquals(2, requests.get())
        val from = ranges[1]?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull()
        assertTrue(from != null && from in 1..400_000, "the second request resumed: ${ranges[1]}")
        assertEquals("\"v1\"", ifRanges[1], "the resume is tied to the file the first answer sent")
        assertTrue(seen.notes.single().contains("; resuming at "), "${seen.notes}")
        assertEquals(listOf("splice.jar"), files(dir))
    }

    @Test
    fun `a server that cannot resume sends the whole file again and the download starts over`(@TempDir dir: Path) {
        val url = serve { exchange, request -> if (request == 1) cut(exchange, 400_000) else whole(exchange) }
        val seen = Seen(ProgressLines(TerminalOutput { }, "splice.jar")) { }
        val target = dir.resolve("splice.jar")
        assertEquals(Downloaded.Landed, JdkUpgradeFetch(pacing).download(url, target, sha(body), seen))
        assertTrue(body.contentEquals(Files.readAllBytes(target)), "no byte of the first leg was kept twice")
        assertTrue(seen.notes.any { it.contains("starting over") }, "${seen.notes}")
    }

    @Test
    fun `a server that cannot resume and drops at the same place every time ends with a sentence`(@TempDir dir: Path) {
        val url = serve { exchange, _ -> cut(exchange, 400_000) }
        val seen = Seen(ProgressLines(TerminalOutput { }, "splice.jar")) { }
        val target = dir.resolve("splice.jar")
        val failed = assertThrows<UpgradeFetchFailed> {
            JdkUpgradeFetch(pacing).download(url, target, sha(body), seen)
        }
        assertTrue(
            failed.why.endsWith("3 tries in a row got no further; check the connection, then try again"),
            failed.why,
        )
        assertTrue(seen.notes.any { it.contains("starting over") }, "${seen.notes}")
        assertEquals(emptyList<String>(), files(dir), "neither the asset nor its part file is left")
    }

    @Test
    fun `a wrong checksum is refused and leaves no file`(@TempDir dir: Path) {
        val url = serve { exchange, _ -> whole(exchange) }
        val target = dir.resolve("splice.jar")
        val got = JdkUpgradeFetch(pacing).download(url, target, sha("other".toByteArray()), QuietDownload())
        assertEquals(Downloaded.Mismatch(sha(body)), got)
        assertFalse(Files.exists(target))
        assertEquals(emptyList<String>(), files(dir), "the part file was removed too")
    }

    @Test
    fun `a stage over http refuses an asset whose sum is wrong and stages nothing of it`(@TempDir dir: Path) {
        server.executor = pool
        val sums = "${sha("not the jar".toByteArray())}  splice.jar\n${sha(body)}  splice-launch\n"
        server.createContext("/v9/sha256sums.txt") { exchange ->
            exchange.sendResponseHeaders(200, sums.length.toLong())
            exchange.responseBody.write(sums.toByteArray())
            exchange.close()
        }
        server.createContext("/v9/splice.jar") { exchange ->
            cutsAllowed { whole(exchange) }
            exchange.close()
        }
        server.start()
        val printed = mutableListOf<String>()
        val release = UpgradeRelease(
            TerminalOutput { printed += it },
            JdkUpgradeFetch(pacing),
            UpgradeProcess { _, _ -> UpgradeExit(0, "") },
            "java",
        )
        val staging = dir.resolve("staging")
        val staged = release.stage("http://127.0.0.1:${server.address.port}/v9", staging, attest = false)
        val reason = (staged as Upgraded.Refused).reason
        assertTrue(reason.startsWith("sha256 verification FAILED for splice.jar (expected "), reason)
        assertEquals(emptyList<String>(), files(staging), "no jar and no part file in staging")
        assertTrue(printed.any { it.endsWith("downloading 1.0 MB") }, "$printed")
    }

    @Test
    fun `a stage takes this platform's jar when the release lists it, and the jar of all four when it does not`(
        @TempDir dir: Path,
    ) {
        server.executor = pool
        val own = "this platform's jar".toByteArray()
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sums = "${sha(body)}  splice.jar\n${sha(own)}  splice-linux-amd64.jar\n${sha(body)}  splice-launch\n"
        val files = mapOf(
            "/v9/sha256sums.txt" to sums.toByteArray(),
            "/v9/splice-linux-amd64.jar" to own,
            "/v9/splice.jar" to body,
            "/v9/splice-launch" to body,
        )
        files.forEach { (path, bytes) ->
            server.createContext(path) { exchange ->
                asked += exchange.requestURI.path
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}/v9"
        val process = UpgradeProcess { _, _ -> UpgradeExit(0, "splice 0.3.9") }
        val quiet = TerminalOutput { }
        val listed = dir.resolve("listed")
        UpgradeRelease(quiet, JdkUpgradeFetch(pacing), process, "java", PlatformJarName("Linux", "amd64"))
            .stage(base, listed, attest = false)
        assertEquals(own.decodeToString(), Files.readString(listed.resolve("splice.jar")), "it lands as the one jar")
        assertFalse("/v9/splice.jar" in asked, "the jar of all four is not downloaded: $asked")

        asked.clear()
        val unlisted = dir.resolve("unlisted")
        UpgradeRelease(quiet, JdkUpgradeFetch(pacing), process, "java", PlatformJarName("Mac OS X", "x86_64"))
            .stage(base, unlisted, attest = false)
        assertArrayEquals(body, Files.readAllBytes(unlisted.resolve("splice.jar")), "a platform with no jar of its own")
        assertTrue("/v9/splice.jar" in asked, "$asked")
    }
}
