// NEW: the request-write bound's send-queue table on macOS (the Temple's bundling queue, via splice-lead, Oct 13).
// Linux reads what the upstream has not acknowledged from /proc/net/tcp (ProcNetTcp, unchanged); macOS has no such
// file, so RequestWriteBound fell back to bounding the write CALL there, and a request that fit in the socket buffers
// waited for the turn cap. macOS's netstat lists every TCP connection's Send-Q to any user, no root needed: the bytes
// in the socket's send buffer that the peer has not acknowledged (so_snd.sb_cc), the same count as Linux's tx_queue,
// `write_seq - snd_una`. UNTESTED ON A MAC: written and compiled on Linux, where nothing selects it.
package splice.upstream.transport

import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

// why: macOS's own netstat, by absolute path, so a PATH entry cannot stand in for it.
private const val NETSTAT = "/usr/sbin/netstat"

// why: a listing of a few hundred connections takes tens of milliseconds; a netstat still running after a second is
// stuck, and the shared watch ticker must not wait on it longer than four ticks.
private const val NETSTAT_WAIT_MS = 1_000L

// why: Proto, Recv-Q, Send-Q, Local Address, Foreign Address, (state): the first five are read.
private const val NETSTAT_FIELDS = 5

// why: Send-Q is netstat's third column, counted from zero.
private const val NETSTAT_SEND_Q = 2

// why: the local address follows Send-Q.
private const val NETSTAT_LOCAL = 3

// why: the foreign address follows the local one.
private const val NETSTAT_FOREIGN = 4

// why: netstat pads its columns with runs of spaces.
private val WHITESPACE = Regex("\\s+")

// why: a numeric IPv4 or IPv6 literal, the only address -n prints; anything else is skipped, never resolved.
private val LITERAL = Regex("[0-9A-Fa-f:.]+")

/** One run of netstat's TCP listing, line by line; throws [IOException] when it did not finish or failed. */
internal fun interface NetstatRun {
    fun lines(): List<String>
}

/** `/usr/sbin/netstat -n -W -p tcp`. Its output is read whole while a watcher kills a run past [NETSTAT_WAIT_MS], so a
 *  listing larger than the pipe never deadlocks against the wait, and a stuck one never holds the watch's ticker. */
internal class NetstatListing : NetstatRun {
    override fun lines(): List<String> {
        val process = ProcessBuilder(NETSTAT, "-n", "-W", "-p", "tcp")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        Thread.ofVirtual().name("splice-netstat-bound").start {
            if (!process.waitFor(NETSTAT_WAIT_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        if (!process.waitFor(NETSTAT_WAIT_MS, TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
            throw IOException("netstat did not finish cleanly")
        }
        return lines
    }
}

/**
 * macOS's table, read from netstat once per watch tick. A row's address is `<address>.<port>`, IPv6 with an optional
 * `%<interface>` scope, and is spelled here as [SocketKeys] spells a Java socket's, so the watch matches it the same
 * way it matches Linux's rows. A row it cannot read is skipped, never guessed at; a run that fails says null, as a
 * missing table does, and [log] hears once when the listing stops reading and once when it reads again.
 */
internal class MacNetstat(
    private val log: LogSink,
    private val run: NetstatRun = NetstatListing(),
) : SendQueues {
    @Volatile
    private var readingLast: Boolean? = null

    override fun read(): SendQueueTable? {
        val read = Cancellables.runCatchingCancellable { table(run.lines()) }
        noted(read.exceptionOrNull())
        return read.getOrNull()
    }

    private fun table(lines: List<String>): SendQueueTable {
        val unacked = HashMap<SocketKey, Long>()
        lines.forEach { line ->
            val fields = line.trim().split(WHITESPACE)
            if (fields.size < NETSTAT_FIELDS || !fields[0].startsWith("tcp")) return@forEach
            val queued = fields[NETSTAT_SEND_Q].toLongOrNull() ?: return@forEach
            val local = end(fields[NETSTAT_LOCAL]) ?: return@forEach
            val remote = end(fields[NETSTAT_FOREIGN]) ?: return@forEach
            unacked[SocketKey(local, remote)] = queued
        }
        return SendQueueTable(unacked)
    }

    /** `<address>.<port>` as [SocketKeys.end] spells it, or null for a wildcard or anything that is not a literal. */
    private fun end(field: String): String? {
        val host = field.substringBeforeLast('.', "").substringBefore('%')
        val port = field.substringAfterLast('.', "").toIntOrNull()
        if (port == null || !LITERAL.matches(host)) return null // LITERAL needs one character at least
        // A literal never asks a resolver: getByName parses it, and LITERAL lets nothing else through.
        return try {
            SocketKeys.end(InetAddress.getByName(host).address, port)
        } catch (_: UnknownHostException) {
            null // a malformed literal is a row this table skips
        }
    }

    private fun noted(failure: Throwable?) {
        val before = readingLast
        readingLast = failure == null
        if (failure != null && before != false) {
            log(
                "[upstream] netstat could not list TCP send queues (${SafeFailureText.render(failure)}); " +
                    "a request is cut only while its write waits in the kernel, until it lists them again\n",
            )
        } else if (failure == null && before == false) {
            log(
                "[upstream] netstat lists TCP send queues again; " +
                    "a request the upstream stops acknowledging is cut again\n",
            )
        }
    }
}
