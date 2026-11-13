// NEW: Nov 12, 2026 — the price table follows the project between releases.
//
// ONE REQUEST AN HOUR, AND IT SAYS NOTHING ABOUT THE MACHINE. The daemon reads published-rates.tsv from the main branch
// of splice's repository: an anonymous GET with no token, no cookie and no detail of this machine, its accounts or its
// sessions; the client name is "splice" with its version. `[daemon] refresh_prices = false` turns it off, read from
// splice.toml at every look, so turning it off needs no restart.
//
// WHAT COMES BACK IS DATA. It is read by the reader the jar's own copy is read by (RateTable): a file that does not
// read whole, is larger than a price table can be, or is older than the table in use is left aside with a logged line
// and the table in use stays. A price changes what a turn is said to cost and what a budget counts; nothing in the
// file names a host, a command or a path.
//
// THE COPY IS KEPT, so the next start prices with it before its first look, and so `splice status` can say when the
// table was last refreshed and from where.
package splice.lifecycle.prices

import splice.core.GATEWAY_VERSION
import splice.core.model.PublishedRates
import splice.core.model.RateRefresh
import splice.core.model.RateTable
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.net.HttpURLConnection.HTTP_OK
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** The host the table is fetched from, as `splice status` and the docs name it. */
public const val PRICE_REFRESH_HOST: String = "raw.githubusercontent.com"

private const val PRICE_REFRESH_URL =
    "https://$PRICE_REFRESH_HOST/torad-labs/splice/main/core/src/main/resources/published-rates.tsv"

// why: the first look comes once the daemon has settled, soon enough that a start after weeks off prices its first
// hour with today's table.
private const val FIRST_LOOK_SECONDS = 60L

// why: a price changes a few times a year and a new model a few times a month; an hour keeps either at most an hour
// away, the cadence the model lists and the update look already keep.
private const val LOOK_EVERY_SECONDS = 3_600L

// why: the table is under 9 KiB with its 63 rows (Nov 12, 2026). A quarter of a mebibyte leaves room for more than
// twenty times as many and still refuses a response that is not a price table before it is held in memory.
private const val MAX_TABLE_BYTES = 262_144

// why: a host that does not answer is given up on well inside the hour; the next look asks again.
private const val CONNECT_SECONDS = 10L

// why: the whole answer is one small file; twenty seconds is slow for it on any line a daemon works on.
private const val ANSWER_SECONDS = 20L

/** Whether splice.toml lets the daemon fetch the table now; null when the file cannot be read, which skips that look. */
public fun interface PriceRefreshSettings {
    public fun on(): Boolean?
}

/** The file's text as the host serves it now. A refusal, an oversized answer or a host that does not answer throws. */
public fun interface PriceFetch {
    @Throws(IOException::class)
    public fun text(): String
}

/** Keeps the price table in use ([PublishedRates]) as new as the one on the project's main branch. [copy] is where
 *  the last fetched table is kept in splice's state folder. */
public class PriceRefresh(
    private val copy: Path,
    private val settings: PriceRefreshSettings,
    private val log: LogSink,
    private val fetch: PriceFetch = HttpPriceFetch(),
) {
    private val lifecycle = Any()
    private var looks: ScheduledExecutorService? = null

    /** Before any price is read at a start: the copy an earlier look kept, taken when it is not older than the
     *  table in this build. Never throws: a copy that cannot be read leaves the build's table in use. */
    public fun seed() {
        if (!Files.isRegularFile(copy)) return
        val text = Cancellables.runCatchingCancellable { Files.readString(copy) }
            .onFailure { log("[prices] the kept copy could not be read: ${LogSafe.str(SafeFailureText.render(it))}\n") }
            .getOrNull() ?: return
        when (val read = PublishedRates.take(text)) {
            is RateRefresh.Taken -> if (PublishedRates.refreshed()) {
                log("[prices] using the kept copy: ${LogSafe.str(said(read.table))}\n")
            }
            is RateRefresh.Ignored -> log("[prices] the kept copy is left aside: ${LogSafe.str(read.why)}\n")
        }
    }

    /** Schedules the looks on one daemon thread, which never keeps the JVM up and ends at [close]. */
    public fun start() {
        // A look that throws would cancel every later one, so each is folded into a logged line.
        val look = Runnable {
            Cancellables.runCatchingCancellable { look() }
                .onFailure { log("[prices] not refreshed: ${LogSafe.str(SafeFailureText.render(it))}\n") }
        }
        synchronized(lifecycle) {
            if (looks != null) return
            looks = Executors.newSingleThreadScheduledExecutor { task ->
                Executors.defaultThreadFactory().newThread(task).apply {
                    name = "splice-price-refresh"
                    isDaemon = true
                }
            }.also { it.scheduleWithFixedDelay(look, FIRST_LOOK_SECONDS, LOOK_EVERY_SECONDS, TimeUnit.SECONDS) }
        }
    }

    /** The daemon is stopping or has handed over: no further look. One in flight is interrupted. */
    public fun close() {
        synchronized(lifecycle) { looks.also { looks = null } }?.shutdownNow()
    }

    /** One look: the switch, the fetch, and the table taken when it reads whole and is not older. A table that was
     *  taken is kept, also when it says what the table in use says, so the copy's time is the last good look. */
    internal fun look() {
        if (settings.on() != true) return
        val text = fetch.text()
        val before = PublishedRates.table()
        when (val read = PublishedRates.take(text)) {
            is RateRefresh.Taken -> {
                SecureFile.writeAtomic0600(copy, text)
                if (read.table !== before) log("[prices] refreshed: ${LogSafe.str(said(read.table))}\n")
            }
            is RateRefresh.Ignored -> log("[prices] the fetched table is left aside: ${LogSafe.str(read.why)}\n")
        }
    }

    private fun said(table: RateTable): String = "the table of ${table.updated}, ${table.rows} models"
}

/** The anonymous GET. A redirect is not followed: the file is served where it is asked for, and an answer that
 *  points elsewhere is a refusal. */
internal class HttpPriceFetch : PriceFetch {
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
            .build()
    }

    override fun text(): String {
        val request = HttpRequest.newBuilder(URI(PRICE_REFRESH_URL))
            .timeout(Duration.ofSeconds(ANSWER_SECONDS))
            .header("User-Agent", "splice/$GATEWAY_VERSION")
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("the look was interrupted", interrupted)
        }
        return response.body().use { body ->
            val status = response.statusCode()
            if (status != HTTP_OK) throw IOException("$PRICE_REFRESH_HOST answered $status")
            val bytes = body.readNBytes(MAX_TABLE_BYTES + 1)
            val over = bytes.size > MAX_TABLE_BYTES
            if (over) throw IOException("$PRICE_REFRESH_HOST sent more than $MAX_TABLE_BYTES bytes")
            String(bytes, Charsets.UTF_8)
        }
    }
}
