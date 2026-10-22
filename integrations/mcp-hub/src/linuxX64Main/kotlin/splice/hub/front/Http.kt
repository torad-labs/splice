// The HTTP the front speaks: requests from sessions on one side, short calls to the daemon on the other, and the
// newline-delimited JSON of a holder's socket. Only what MCP's streamable HTTP and the daemon's endpoints need.
package splice.hub.front

import splice.hub.io.Bytes

/** One request a session sent. Header names are lower case. */
data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

/** A session's connection. One request at a time: the next is read only once this one is answered, or once it has
 *  become a stream, which then holds the connection until it closes. A request whose head does not pass [admits] is
 *  answered 401 and the connection closed before its body is read, so only a caller holding the bearer can make the
 *  front buffer a body. A request not whole within [REQUEST_TIMEOUT_MS] of the connection waiting for it is closed. */
class HttpConn(
    fd: Int,
    private val sys: Sys,
    private val clock: Clock,
    private val admits: (String, Map<String, String>) -> Boolean,
    private val onRequest: (HttpConn, Request) -> Unit,
) : Watched(fd) {
    val inbox = Bytes()
    private val outbox = Bytes()

    /** The monotonic time by which the request this connection is waiting for must have arrived whole. */
    private var requestBy = clock.now() + REQUEST_TIMEOUT_MS
    var busy = false
        private set
    var streaming = false
        private set
    var onGone: (() -> Unit)? = null

    /** The session whose stream this is, kept for an in-place upgrade. */
    var streamOf: String? = null

    /** Set once the front is stopping: each answer says Connection: close and the connection closes after it, so a
     *  client moves to the next front rather than reusing a connection this one is about to drop. */
    var closing = false

    /** Set while the front prepares an in-place upgrade: nothing new is read, so what arrives waits in the kernel. */
    var draining = false

    /** Whether everything this connection owes has been written. */
    fun quiet(): Boolean = !busy && outbox.size == 0

    /** A connection carried across an exec: [streamFor] marks a stream whose headers were already sent. */
    fun resume(saved: ByteArray, streamFor: String?) {
        inbox.append(saved)
        streamOf = streamFor
        streaming = streamFor != null
        busy = streaming
    }

    override fun events(): Int = (if (busy || draining) 0 else IN) or (if (outbox.size > 0) OUT else 0)

    override fun deadline(): Long? = if (idle) requestBy else null

    override fun due() = close()

    override fun ready(revents: Int) {
        if (revents and OUT != 0) {
            if (!sys.flush(fd, outbox)) return close()
            // the last answer of a stopping front has gone out: the connection goes with it
            if (doneStopping()) return close()
        }
        if (revents and IN != 0) {
            if (sys.readInto(fd, inbox) == 0) return close()
            parse()
        } else if (sys.gone(revents)) {
            close()
        }
    }

    override fun onClose() {
        onGone?.invoke()
    }

    private fun doneStopping(): Boolean = closing && quiet() && !streaming

    private val idle: Boolean get() = !busy && !draining && !streaming

    /** Reads the next request once its head and body are in; a head too large or malformed closes the connection. */
    fun parse() {
        val end = if (idle) inbox.indexOf(CRLF2) else return
        if (end < 0) {
            if (inbox.size > MAX_HEADERS) close()
            return
        }
        val head = Head(inbox.text(0, end))
        val length = head.length()
        val start = end + CRLF2.size
        when {
            !head.valid() || length > MAX_BODY -> close()
            !admits(head.path(), head.headers) -> {
                // no bearer: 401 and close, the body unread, so it never reaches the buffer
                closing = true
                inbox.drop(inbox.size)
                reply(UNAUTHORIZED)
            }
            inbox.size >= start + length -> {
                val body = inbox.text(start, start + length)
                inbox.drop(start + length)
                busy = true
                onRequest(this, Request(head.method(), head.path(), head.headers, body))
            }
        }
    }

    /** Answers the request in hand, then reads the next. */
    fun reply(status: Int, body: String = "", extra: Map<String, String> = emptyMap()) {
        if (closed) return
        val bytes = body.encodeToByteArray()
        outbox.append(head(status, bytes.size, extra))
        outbox.append(bytes)
        busy = false
        requestBy = clock.now() + REQUEST_TIMEOUT_MS
        when {
            !sys.flush(fd, outbox) -> close()
            closing && outbox.size == 0 -> close()
            else -> parse()
        }
    }

    /** Turns the request in hand into an event stream that holds this connection. */
    fun stream() {
        streaming = true
        outbox.append(
            "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n",
        )
        push(": stream open\n\n")
    }

    /** One chunk on the stream: an event, or a comment that keeps it alive. */
    fun push(event: String) {
        if (closed) return
        val bytes = event.encodeToByteArray()
        outbox.append("${bytes.size.toString(HEX)}\r\n")
        outbox.append(bytes)
        outbox.append("\r\n")
        if (!sys.flush(fd, outbox)) close()
    }

    fun message(json: String) = push("event: message\ndata: $json\n\n")

    private fun head(status: Int, size: Int, extra: Map<String, String>): String {
        val head = StringBuilder("HTTP/1.1 $status ${REASONS[status] ?: "Status"}\r\n")
        if (size > 0) head.append("Content-Type: application/json\r\n")
        extra.forEach { (k, v) -> head.append("$k: $v\r\n") }
        if (closing) head.append("Connection: close\r\n")
        head.append("Content-Length: $size\r\n\r\n")
        return head.toString()
    }
}

/** A request's head: the request line, then headers with their names lower-cased. A Content-Length that is not a plain
 *  non-negative number makes the head invalid: the connection closes rather than guess where the body ends. */
class Head(text: String) {
    private val lines = text.split("\r\n")
    private val parts = lines.first().split(" ")
    val headers: Map<String, String> = lines.drop(1).mapNotNull { line ->
        val colon = line.indexOf(':')
        if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
    }.toMap()

    /** What Content-Length declares: 0 when absent, -1 when present and not a non-negative number that fits an Int. */
    private val declared: Int = headers["content-length"]?.let { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: -1 } ?: 0

    fun valid(): Boolean = parts.size >= 2 && declared >= 0

    fun method(): String = parts[0]

    fun path(): String = parts[1].substringBefore('?')

    fun length(): Int = declared.coerceAtLeast(0)
}

/** A listening socket: each connection it accepts becomes an [HttpConn], up to [MAX_CONNECTIONS] at once; one more is
 *  accepted and dropped at once, so a flood of idle connections cannot grow without bound. */
class HttpListener(
    fd: Int,
    private val sys: Sys,
    private val loop: Loop,
    private val clock: Clock,
    private val admits: (String, Map<String, String>) -> Boolean,
    private val handler: (HttpConn, Request) -> Unit,
) :
    Watched(fd) {
    fun adopt(conn: Int): HttpConn = HttpConn(conn, sys, clock, admits, handler).also(loop::add)

    var accepting = true

    override fun events(): Int = if (accepting) IN else 0

    override fun ready(revents: Int) {
        while (true) {
            val conn = sys.accept(fd)
            if (conn < 0) return
            if (loop.all().count { it is HttpConn } >= MAX_CONNECTIONS) sys.release(conn) else adopt(conn)
        }
    }
}

/** One POST to the daemon's control port, answered by [done] with the status and body, or status 0 when the daemon
 *  could not be reached. Connection: close, so the answer ends at end of file. */
class DaemonCall(
    private val sys: Sys,
    private val path: String,
    private val key: String,
    private val body: String,
    private val done: (Int, String) -> Unit,
    fd: Int,
) : Watched(fd) {
    private val outbox = Bytes()
    private val inbox = Bytes()
    private var connected = false
    private var answered = false

    init {
        val bytes = body.encodeToByteArray()
        outbox.append(
            "POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $key\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n",
        )
        outbox.append(bytes)
        if (fd < 0) finish(0, "")
    }

    override fun events(): Int = if (!connected || outbox.size > 0) OUT else IN

    override fun ready(revents: Int) {
        if (!connected && revents and OUT != 0) {
            if (sys.connectFailed(fd)) return finish(0, "")
            connected = true
        }
        if (outbox.size > 0 && !sys.flush(fd, outbox)) return finish(0, "")
        if (revents and IN != 0 || sys.gone(revents)) {
            if (sys.readInto(fd, inbox) == 0) answer()
        }
    }

    private fun answer() {
        val end = inbox.indexOf(CRLF2)
        if (end < 0) return finish(0, "")
        val head = inbox.text(0, end)
        val status = head.substringAfter(' ').substringBefore(' ').toIntOrNull() ?: 0
        val raw = inbox.text(end + CRLF2.size, inbox.size)
        finish(status, if (head.lowercase().contains("transfer-encoding: chunked")) dechunk(raw) else raw)
    }

    private fun dechunk(raw: String): String {
        val out = StringBuilder()
        var rest = raw
        var size = chunkSize(rest)
        while (size > 0) {
            val start = rest.indexOf("\r\n") + 2
            out.append(rest, start, start + size)
            rest = rest.substring(start + size + 2)
            size = chunkSize(rest)
        }
        return out.toString()
    }

    /** The size line of the next chunk; 0 at the last chunk or where no size line can be read. */
    private fun chunkSize(rest: String): Int = rest.substringBefore("\r\n").trim().toIntOrNull(HEX) ?: 0

    private fun finish(status: Int, text: String) {
        if (answered) return
        answered = true
        if (fd >= 0) close()
        done(status, text)
    }
}

/** The front's connection to one holder: whole lines each way. */
class HolderLink(
    fd: Int,
    val socket: String,
    private val sys: Sys,
    private val onLine: (String) -> Unit,
    private val onLost: () -> Unit,
) : Watched(fd) {
    val inbox = Bytes()
    private val outbox = Bytes()

    override fun events(): Int = IN or (if (outbox.size > 0) OUT else 0)

    override fun ready(revents: Int) {
        if (revents and OUT != 0 && !sys.flush(fd, outbox)) return close()
        if (revents and IN != 0 || sys.gone(revents)) {
            if (sys.readInto(fd, inbox) == 0) return close()
            lines()
        }
    }

    private fun lines() {
        var nl = inbox.indexOf(NL)
        while (nl >= 0) {
            val line = inbox.text(0, nl)
            inbox.drop(nl + 1)
            if (line.isNotBlank()) onLine(line)
            nl = inbox.indexOf(NL)
        }
    }

    fun send(line: String) {
        outbox.append(line)
        outbox.append(NL)
        if (!sys.flush(fd, outbox)) close()
    }

    override fun onClose() = onLost()
}

private val CRLF2 = "\r\n\r\n".encodeToByteArray()
private val NL = "\n".encodeToByteArray()
private const val MAX_HEADERS = 64 * 1024
private const val MAX_BODY = 64 * 1024 * 1024

// Far above what the sessions on one machine hold open (a connection per session, and one more for a stream), so it
// stops only a flood of idle connections from growing without bound.
private const val MAX_CONNECTIONS = 256

// Two minutes: far past the few seconds a client keeps an idle connection for, so a client is always the first to close
// one it is done with and never meets a connection this front closed under its next request.
private const val REQUEST_TIMEOUT_MS = 120_000L
private const val HEX = 16
const val OK = 200
const val ACCEPTED = 202
const val BAD_REQUEST = 400
const val UNAUTHORIZED = 401
const val NOT_FOUND = 404
const val NOT_ALLOWED = 405
const val UNAVAILABLE = 503
private val REASONS = mapOf(
    OK to "OK",
    ACCEPTED to "Accepted",
    BAD_REQUEST to "Bad Request",
    UNAUTHORIZED to "Unauthorized",
    NOT_FOUND to "Not Found",
    NOT_ALLOWED to "Method Not Allowed",
    UNAVAILABLE to "Service Unavailable",
)
