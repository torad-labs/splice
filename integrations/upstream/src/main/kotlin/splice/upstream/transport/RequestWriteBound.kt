// NEW: V4-272 (2026-09-26) — a request's write is bounded by the head's firstByteTimeout and named when
// it stalls. Its own file rather than a tail of UpstreamTransport.kt: the client construction there
// installs it with two lines and needs nothing else from it.
//
// V4-289 (review of V4-272, 2026-09-26): V4-272 bounded the write CALL with OkHttp's write timeout, and
// that held for one case only, a plain-HTTP body bigger than the socket buffers. Four others failed:
//   FITS IN THE BUFFERS  a write returns once its bytes are in the kernel, so a 200 KiB request the
//                        upstream never took waited out the turn cap (the film incident's 195,768
//                        bytes in one send queue is this case). The watch now reads what the upstream
//                        ACKNOWLEDGED (SendQueues.kt), not what the write call returned.
//   TLS                  okio's timeout closes the SSLSocket, whose close waits with no limit for the TLS
//                        record lock the stalled writer holds (SO_LINGER unset), on okio's ONE watchdog
//                        thread, so every OkHttp timeout in the process stopped with it (okhttp#6558).
//                        OkHttp's write timeout is now OFF for a request body, and the watch cuts with
//                        Call.cancel(), which closes the raw socket and takes no TLS lock.
//   STALE POOL           the retry took another idle connection from the JVM-wide pool ktor's clients
//                        share, on the same dead path; each client now has its own pool (UpstreamTransport)
//                        and a stall evicts its idle connections, so the retry dials a new one.
//   SLOW LINK            the timeout ran per 64 KiB write, so a link slower than that was cut while bytes
//                        still flowed; the watch counts every byte the upstream acknowledges.
package splice.upstream.transport

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.Response
import splice.core.util.Cancellables
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * V4-272: a request's write is bounded by [boundMs], the head's firstByteTimeout, and named when it
 * stalls. Before this the bound was the whole-turn cap: the idle watchdog is armed only after a 2xx, so
 * a request the upstream stopped taking (a wifi drop left 195,768 bytes in one send queue for 264 s,
 * film home 2026-09-26) waited out the cap in silence.
 *
 * V4-289: the bound is on what the upstream TAKES. From the moment the request starts on a connection
 * until its response headers arrive, [StallWatch] checks the socket: while the kernel holds bytes the
 * upstream has not acknowledged, and it acknowledges none of them for [boundMs], the call is cut and
 * named [RequestWriteStalled], and this client's idle connections are evicted so the retry dials a new
 * one. A request the upstream keeps taking is never cut, however slowly, and one it has taken whole
 * waits for its headers as long as the read timeout allows (a long prefill is not a stall). Where the
 * kernel's queue cannot be read (macOS) the watch bounds the write call alone: a write waiting in the
 * kernel with no byte accepted for [boundMs] is cut the same way, and a request that fits in the
 * buffers waits for the turn cap, as it did before this row.
 *
 * A NETWORK interceptor, because only there is the connection known; [untimedWrite] is its application
 * half, since only an application interceptor may change a call's timeouts.
 */
internal class RequestWriteBound(
    private val boundMs: Long,
    private val pool: ConnectionPool,
    private val ledger: SocketLedger,
    private val queues: SendQueues,
    private val watch: StallWatch = sharedWatch,
) : Interceptor {

    /** OkHttp's own write timeout, off for a request with a body: the watch bounds that write, and okio's
     *  timeout would close an SSLSocket from its watchdog thread and wait there for the TLS record lock. */
    val untimedWrite: Interceptor = Interceptor { chain ->
        val request = chain.request()
        val untimed = if (request.body == null) chain else chain.withWriteTimeout(0, TimeUnit.MILLISECONDS)
        untimed.proceed(request)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val socket = chain.connection()?.socket()?.let(ledger::find)
        if (request.body == null || socket == null) return chain.proceed(request)
        val watched = watch.start(WatchedWrite(socket, boundMs, chain.call(), queues))
        val response = try {
            chain.proceed(request)
        } catch (failure: IOException) {
            throw named(watched, failure)
        } finally {
            watch.stop(watched)
        }
        if (!watched.cut) return response
        response.close()
        throw named(watched, IOException("the headers arrived as the stalled request was cut"))
    }

    /** [failure] as a stalled write when the watch cut the call, after evicting this client's idle
     *  connections, which sit on the same path; otherwise [failure] itself. */
    private fun named(watched: WatchedWrite, failure: IOException): IOException {
        if (!watched.cut) return failure
        pool.evictAll()
        return RequestWriteStalled(boundMs, failure)
    }
}

/**
 * The one ticker every client's watched requests share, so the kernel's table is read once a tick for
 * all of them and only while one is watched. Its thread is a named virtual one; a tick that fails is the
 * next tick's to redo, never the end of the ticker.
 */
internal class StallWatch(private val tickMs: Long) {
    private val watched: MutableSet<WatchedWrite> = ConcurrentHashMap.newKeySet()
    private val ticker: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("splice-request-write-watch").factory())
            .also { it.scheduleWithFixedDelay(::tick, tickMs, tickMs, TimeUnit.MILLISECONDS) }
    }

    fun start(write: WatchedWrite): WatchedWrite {
        watched += write
        val _ = ticker
        return write
    }

    fun stop(write: WatchedWrite) {
        write.stop()
        watched -= write
    }

    private fun tick() {
        if (watched.isEmpty()) return
        val now = System.nanoTime()
        val checked = Cancellables.runCatchingBestEffort {
            watched.groupBy { it.queues }.forEach { (queues, writes) ->
                val table = queues.read()
                writes.forEach { it.check(table, now) }
            }
        }
        Cancellables.discard(checked, "a tick that failed is the next tick's to redo; the ticker outlives it")
    }
}

/** One request's watch, from its start on a connection to its response headers. */
internal class WatchedWrite(
    private val socket: CountedSocket,
    boundMs: Long,
    private val call: Call,
    val queues: SendQueues,
) {
    private val boundNs = TimeUnit.MILLISECONDS.toNanos(boundMs)
    private val state = AtomicReference(Watch.WATCHING)

    // Read and written by the ticker thread only.
    private var taken = Long.MIN_VALUE
    private var progressAt = System.nanoTime()

    /** Whether the watch cut the call for a stall. */
    val cut: Boolean get() = state.get() == Watch.CUT

    fun stop() {
        val _ = state.compareAndSet(Watch.WATCHING, Watch.STOPPED)
    }

    /**
     * One tick against [table]. The upstream has taken more when the bytes written less the bytes it has
     * not acknowledged grew. Nothing is pending when it has acknowledged everything, and then nothing can
     * stall. Where [table] does not list the socket, the kernel accepting more of a waiting write is the
     * only progress there is to see, and a write that is not waiting is nothing pending.
     */
    fun check(table: SendQueueTable?, now: Long) {
        val queued = table?.unacked(socket)
        val takenNow = socket.bytesWritten - (queued ?: 0L)
        val pending = if (queued != null) queued > 0 else socket.isWriting
        if (!pending || takenNow > taken) {
            taken = maxOf(taken, takenNow)
            progressAt = now
            return
        }
        if (now - progressAt >= boundNs && state.compareAndSet(Watch.WATCHING, Watch.CUT)) call.cancel()
    }

    private enum class Watch { WATCHING, STOPPED, CUT }
}

// why: how often the watch reads the kernel's table while a request is watched: a stall is cut within
// this much of its bound, and four reads a second of a table this size cost nothing measurable.
private const val TICK_MS = 250L

// FILE SCOPE ON PURPOSE, like UpstreamTransport's nodelayLogged: one ticker for the process, however many
// heads build a client, so the table is read once a tick for all of them.
private val sharedWatch = StallWatch(TICK_MS)
