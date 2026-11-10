// NEW (2026-11-10): how `splice upgrade` moves a release asset over the network. The 0.4.0 jar is about 473 MB, and the
// fetch this replaced read each asset into memory under a 300-second limit on the whole request: it needed about
// 12.6 Mbit/s sustained or the upgrade failed, and it held the jar in a JVM sized for a CLI.
//
// A DOWNLOAD IS JUDGED BY PROGRESS, NEVER BY ITS LENGTH. The client bounds the TCP connect; after that the only limit is
// a stall: no answer, or no byte of the body, for DownloadPacing.stallMs ends that leg (StallWatch). A slow download that
// keeps moving runs as long as it takes.
//
// A BROKEN LEG RESUMES. A leg that ends early (the connection dropped, or it stalled) is followed by one that asks for the
// rest with a Range request, tied to the first answer's ETag or Last-Modified through If-Range, so a file replaced
// meanwhile is sent whole rather than spliced onto the old one's bytes. A server that answers the resume with the whole
// file starts it over from zero. Legs go on while they bring bytes; DownloadPacing.fruitless legs in a row that bring
// none end the download with a sentence that says where it stopped and what to do.
//
// NOTHING IS HELD IN MEMORY. The bytes go to a TransferSink: a part file for an asset (UpgradeDownloadFiles.kt), which
// lands only on its published sha256.
package splice.lifecycle.upgrade

import splice.core.util.Cancellables
import splice.core.wire.HttpStatus
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// why: RFC 9110's answer to a Range request the server honored; only this status means the body starts at the offset.
private const val PARTIAL_CONTENT = 206

// why: RFC 9110's answer when the asked offset is past the file; the file changed or the part is wrong, so start over.
private const val RANGE_NOT_SATISFIABLE = 416

// why: a marker no read returns (a read gives a count or -1 at the end), meaning the watch closed the stream under it.
private const val DROPPED = -2

/** How one leg of a transfer ended. */
private sealed class Leg {
    data object Complete : Leg()

    data object Absent : Leg()

    /** It ended early; [what] says how, as the start of a sentence ("the connection dropped"). */
    data class Broke(val what: String) : Leg()
}

/** What the watch would end on a stall, and whether it did. */
private data class Held(val what: AutoCloseable, val stalled: Boolean = false)

/** Watches one transfer for progress. A leg that has gone [DownloadPacing.stallMs] with no answer and no byte is ended
 *  by closing what it is blocked on: the pending answer, or the body being read, which ends that read. */
internal class StallWatch(private val pacing: DownloadPacing) : AutoCloseable {
    private val looks = Executors.newSingleThreadScheduledExecutor { task ->
        Executors.defaultThreadFactory().newThread(task).apply {
            name = "splice-upgrade-stall"
            isDaemon = true
        }
    }
    private val held = AtomicReference<Held?>(null)

    @Volatile
    private var last = pacing.clock()

    /** True when the watch ended the current leg. */
    val stalled: Boolean get() = held.get()?.stalled == true

    init {
        looks.scheduleWithFixedDelay({ look() }, pacing.lookMs, pacing.lookMs, TimeUnit.MILLISECONDS)
    }

    /** A new leg: its window starts now, and nothing of the last leg's is held. */
    fun begin() {
        held.set(null)
        last = pacing.clock()
    }

    /** [what] is closed if the leg stalls from here on. */
    fun hold(what: AutoCloseable) = held.set(Held(what))

    fun progressed() {
        last = pacing.clock()
    }

    private fun look() {
        val now = held.get() ?: return
        if (now.stalled || pacing.clock() - last <= pacing.stallMs) return
        if (held.compareAndSet(now, now.copy(stalled = true))) {
            val closed = Cancellables.runCatchingCancellable { now.what.close() }
            Cancellables.discard(closed, "a stalled leg is over whether or not its close went cleanly")
        }
    }

    override fun close() {
        looks.shutdownNow()
    }
}

/** The answer to one request, or why there was none. */
private data class Sent(val reply: HttpResponse<InputStream>?, val failure: Throwable?)

/** One GET of [url] into [sink], in as many legs as it takes (the header of this file). */
internal class HttpTransfer(
    private val client: HttpClient,
    private val pacing: DownloadPacing,
    private val url: String,
    private val sink: TransferSink,
    private val progress: DownloadProgress,
) {
    private val words = FetchWords()
    private val stallS = pacing.stallSeconds
    private var total: Long? = null
    private var validator: String? = null
    private var answered = false
    private var gained = 0L

    /** True once the whole file is in the sink, false when the server says there is none; [UpgradeFetchFailed] when the
     *  first request gets no answer, the server refuses it, or the legs stop bringing bytes. */
    fun run(): Boolean = StallWatch(pacing).use { watch -> legs(watch) }

    private fun legs(watch: StallWatch): Boolean {
        var fruitless = 0
        var leg = leg(watch)
        while (leg is Leg.Broke) {
            fruitless = if (gained > 0) 0 else fruitless + 1
            if (fruitless >= pacing.fruitless) {
                throw UpgradeFetchFailed(
                    "${leg.what} at ${amount()}, and $fruitless tries in a row got no further; " +
                        "check the connection, then try again",
                )
            }
            val next = if (sink.size > 0) "resuming at ${amount()}" else "trying again from the start"
            progress.note("${leg.what}; $next")
            pacing.pause()
            leg = leg(watch)
        }
        return leg is Leg.Complete
    }

    private fun leg(watch: StallWatch): Leg {
        gained = 0L
        watch.begin()
        val resuming = sink.size > 0
        val pending = client.sendAsync(request(resuming), HttpResponse.BodyHandlers.ofInputStream())
        watch.hold { pending.cancel(true) }
        val sent = pending.handle { reply, failure -> Sent(reply, failure) }.join()
        val reply = sent.reply ?: return unanswered(sent.failure, watch)
        answered = true
        return reply.body().use { body ->
            watch.hold(body)
            answer(reply, body, resuming, watch)
        }
    }

    private fun request(resuming: Boolean): HttpRequest {
        val request = HttpRequest.newBuilder(URI(url)).GET()
        if (resuming) {
            request.header("Range", "bytes=${sink.size}-")
            validator?.let { request.header("If-Range", it) }
        }
        return request.build()
    }

    /** No answer: on the first request that is the fetch's failure, as before; on a resume it is one more broken leg. */
    private fun unanswered(failure: Throwable?, watch: StallWatch): Leg {
        val cause = (failure as? CompletionException)?.cause ?: failure
        val why = if (watch.stalled) "no answer for ${stallS}s" else words.transport(cause)
        if (!answered) {
            val retry = if (watch.stalled) "; check the connection, then try again" else ""
            throw UpgradeFetchFailed("$why$retry")
        }
        return Leg.Broke("the download stalled ($why)")
    }

    private fun answer(reply: HttpResponse<InputStream>, body: InputStream, resuming: Boolean, watch: StallWatch): Leg =
        when (val status = reply.statusCode()) {
            HttpStatus.OK -> {
                if (resuming) {
                    sink.restart()
                    progress.note("the server sent the whole file again; starting over")
                }
                learn(reply)
                pump(body, watch)
            }
            PARTIAL_CONTENT -> resumed(reply, body, resuming, watch)
            HttpStatus.NOT_FOUND -> Leg.Absent
            RANGE_NOT_SATISFIABLE -> Leg.Broke("the server refused to resume").also { sink.restart() }
            else -> refused(status, resuming)
        }

    /** A 206 is used only when it starts exactly where the sink ends; anything else starts the file over. */
    private fun resumed(reply: HttpResponse<InputStream>, body: InputStream, asked: Boolean, watch: StallWatch): Leg {
        val range = reply.headers().firstValue("content-range").orElse("")
        val start = range.substringAfter("bytes ", "").substringBefore('-').toLongOrNull()
        if (!asked || start != sink.size) {
            sink.restart()
            return Leg.Broke("the server resumed from the wrong place")
        }
        total = total ?: range.substringAfter('/', "").toLongOrNull()
        return pump(body, watch)
    }

    /** What the first full answer says about the file: its length, and what a resume must match (If-Range takes a
     *  strong ETag or a date, never a weak ETag). */
    private fun learn(reply: HttpResponse<InputStream>) {
        val headers = reply.headers()
        total = headers.firstValueAsLong("content-length").takeIf { it.isPresent }?.asLong
        val etag = headers.firstValue("etag").orElse(null)?.takeUnless { it.startsWith("W/") }
        validator = etag ?: headers.firstValue("last-modified").orElse(null)
    }

    /** A refusing status ends the fetch, except a server error or rate limit on a resume, which is a broken leg. */
    private fun refused(status: Int, resuming: Boolean): Leg {
        val transient = status >= HttpStatus.INTERNAL_SERVER_ERROR || status == HttpStatus.TOO_MANY_REQUESTS
        if (resuming && transient) return Leg.Broke("the server answered HTTP $status")
        throw UpgradeFetchFailed("HTTP $status (${words.status(status)})")
    }

    private fun pump(body: InputStream, watch: StallWatch): Leg {
        val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
        var read = next(body, buffer)
        while (read >= 0) {
            sink.write(buffer, read)
            gained += read
            watch.progressed()
            progress.received(sink.size, total)
            read = next(body, buffer)
        }
        return ended(read, watch)
    }

    /** The count read, -1 at the end of the body, or [DROPPED] when the read failed. A failed read is how a dropped
     *  connection and a stall both end a leg, so it is never a reason to stop the download. */
    private fun next(body: InputStream, buffer: ByteArray): Int = try {
        body.read(buffer)
    } catch (_: IOException) {
        DROPPED
    }

    private fun ended(read: Int, watch: StallWatch): Leg {
        val expected = total
        return when {
            expected != null && sink.size >= expected -> Leg.Complete
            watch.stalled -> Leg.Broke("the download stalled (no data for ${stallS}s)")
            read == DROPPED -> Leg.Broke("the connection dropped")
            expected != null -> Leg.Broke("the connection closed early")
            else -> Leg.Complete
        }
    }

    private fun amount(): String {
        val done = words.size(sink.size)
        return total?.let { "$done of ${words.size(it)}" } ?: done
    }
}
