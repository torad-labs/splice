// The ledger's hourly export (docs/specs/server-mode.md, F5, ledger-export), as `splice server usage export --now`
// answers it: each row lands in its UTC hour's file once, an hour is rewritten whole when a row joins it, a file taken
// from the target comes back, and a bucket gets one signed PUT per hour under its prefix, its refusal reported in the
// bucket's words and never with the secret.
package splice.server.export

import com.sun.net.httpserver.HttpServer
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.ServerExport
import splice.server.ServerAnswer
import splice.server.ledger.LedgerRow
import splice.server.ledger.ROUTE_MESSAGES
import splice.server.ledger.UsageLedger
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

internal class UsageExportTest {
    @TempDir lateinit var home: Path

    private var bucket: HttpServer? = null

    @AfterEach
    fun stop() {
        bucket?.stop(0)
    }

    private fun row(at: String, person: String) = LedgerRow(
        at = at,
        person = person,
        device = "spk_test",
        command = "claude-team",
        route = ROUTE_MESSAGES,
        status = 200,
        outcome = "ok",
        promptTokens = 1200,
        durationMs = 40,
    )

    private fun ledger(vararg rows: LedgerRow): UsageLedger = runBlocking {
        UsageLedger(home.resolve("ledger.jsonl"), Dispatchers.IO).also { l -> rows.forEach { l.add(it) } }
    }

    private fun export(config: ServerExport, ledger: UsageLedger, env: Map<String, String> = emptyMap()): UsageExport {
        val clock = Clock.fixed(Instant.parse("2026-11-16T15:00:00Z"), ZoneOffset.UTC)
        val runs = ExportClock(Dispatchers.IO, clock, waiter = {}, ticker = { false })
        return UsageExport(config, ledger, runs) {}.also { it.open(env::get) }
    }

    private fun files(dir: Path): Map<String, String> = Files.walk(dir).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { it.relativeTo(dir).toString() to it.readText() }
    }

    private fun people(text: String): List<String> =
        text.lines().filter(String::isNotBlank).map { Regex("\"person\":\"([^\"]+)\"").find(it)!!.groupValues[1] }

    @Test
    fun `each row lands once in its UTC hour's file, and exporting again changes nothing`() = runBlocking {
        val out = home.resolve("out")
        val ledger = ledger(
            row("2026-11-16T13:59:59Z", "ann@example.com"),
            row("2026-11-16T14:00:00Z", "bob@example.com"),
            row("2026-11-16T14:30:00Z", "ann@example.com"),
        )
        val export = export(ServerExport(to = "file://$out"), ledger)

        val answer = export.now()
        assertTrue(answer is ServerAnswer.Done, "$answer")
        val first = files(out)
        assertEquals(setOf("splice-usage/2026/11/16/13.jsonl", "splice-usage/2026/11/16/14.jsonl"), first.keys)
        assertEquals(listOf("ann@example.com"), people(first.getValue("splice-usage/2026/11/16/13.jsonl")))
        assertEquals(
            listOf("bob@example.com", "ann@example.com"),
            people(first.getValue("splice-usage/2026/11/16/14.jsonl")),
        )

        export.now()
        assertEquals(first, files(out))
    }

    @Test
    fun `a later row joins its hour whole, and a file taken from the folder comes back`() = runBlocking {
        val out = home.resolve("out")
        val ledger = ledger(row("2026-11-16T14:05:00Z", "ann@example.com"))
        val export = export(ServerExport(to = "file://$out"), ledger)
        export.now()

        ledger.add(row("2026-11-16T14:50:00Z", "cy@example.com"))
        export.now()
        val hour = out.resolve("splice-usage/2026/11/16/14.jsonl")
        assertEquals(listOf("ann@example.com", "cy@example.com"), people(hour.readText()))

        Files.delete(hour)
        export.now()
        assertEquals(listOf("ann@example.com", "cy@example.com"), people(hour.readText()))
    }

    @Test
    fun `a bucket gets one signed PUT per hour under its prefix`() = runBlocking {
        val puts = mutableMapOf<String, String>()
        val signed = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { bucket = it }
        server.createContext("/") { ex ->
            puts[ex.requestURI.rawPath] = ex.requestBody.readBytes().decodeToString()
            signed += ex.requestHeaders.getFirst("Authorization").orEmpty()
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        server.start()
        val config = ServerExport(
            to = "s3://team-usage/gateway",
            endpoint = "http://127.0.0.1:${server.address.port}",
            region = "us-east-1",
            pathStyle = true,
            accessKeyEnv = "KEY",
            secretKeyEnv = "SECRET",
        )
        val ledger = ledger(
            row("2026-11-16T14:05:00Z", "dee@example.com"),
            row("2026-11-16T14:06:00Z", "dee@example.com"),
        )

        val answer = export(config, ledger, mapOf("KEY" to "AKIDTEST", "SECRET" to "s3-secret")).now()

        assertTrue(answer is ServerAnswer.Done, "$answer")
        assertEquals(setOf("/team-usage/gateway/splice-usage/2026/11/16/14.jsonl"), puts.keys)
        assertEquals(listOf("dee@example.com", "dee@example.com"), people(puts.values.single()))
        assertTrue(
            signed.single().startsWith("AWS4-HMAC-SHA256 Credential=AKIDTEST/20261116/us-east-1/s3/aws4_request, "),
            signed.single(),
        )
    }

    @Test
    fun `a bucket that refuses is reported in its own words, never with the secret`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { bucket = it }
        server.createContext("/") { ex ->
            check(ex.requestBody.readBytes().isNotEmpty()) { "the PUT carried no hour" }
            val body = "<Error><Code>AccessDenied</Code><Message>Access Denied</Message></Error>".toByteArray()
            ex.sendResponseHeaders(403, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        val config = ServerExport(
            to = "s3://team-usage",
            endpoint = "http://127.0.0.1:${server.address.port}",
            pathStyle = true,
            accessKeyEnv = "KEY",
            secretKeyEnv = "SECRET",
        )
        val ledger = ledger(row("2026-11-16T14:05:00Z", "dee@example.com"))

        val answer = export(config, ledger, mapOf("KEY" to "AKIDTEST", "SECRET" to "s3-secret")).now()

        val refused = answer as ServerAnswer.Refused
        assertEquals(HttpStatusCode.BadGateway, refused.status)
        assertTrue("the bucket answered 403: AccessDenied: Access Denied" in refused.why, refused.why)
        assertFalse("s3-secret" in refused.why)
    }

    @Test
    fun `a bucket whose key is not in the environment names the variables to set`() = runBlocking {
        val config =
            ServerExport(to = "s3://team-usage", accessKeyEnv = "USAGE_S3_KEY", secretKeyEnv = "USAGE_S3_SECRET")
        val ledger = ledger(row("2026-11-16T14:05:00Z", "dee@example.com"))

        val refused = export(config, ledger).now() as ServerAnswer.Refused

        assertTrue("USAGE_S3_KEY and USAGE_S3_SECRET must both be set" in refused.why, refused.why)
    }
}
