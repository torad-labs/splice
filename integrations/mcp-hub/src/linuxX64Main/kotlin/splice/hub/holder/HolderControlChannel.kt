@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package splice.hub.holder

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.SOL_SOCKET
import platform.posix.close
import platform.posix.getsockopt
import splice.hub.front.Clock
import splice.hub.io.Bytes

/** Control is tentative until both sockets present the same generation-bound claim from the same Linux peer. */
internal class HolderControlChannel(
    private val listener: Listener,
    private val output: Bytes,
    private val settlement: HolderSettlement,
    identity: String,
) {
    private val pairing = HolderConnectionPair(identity)
    private val input = Bytes()
    private val scratch = ByteArray(CONTROL_CHUNK)
    private val clock = Clock()
    private var expiresAt: Long? = null
    private var waitingBy: Long? = null
    private var sent = 0
    private var client: Fd? = null
    private var waiting: Fd? = null
    private var pairedData: Fd? = null

    val listenerFd: Int get() = listener.fd.fd
    val waitingFd: Int get() = waiting?.fd ?: -1
    val clientFd: Int get() = client?.fd ?: -1
    val recorded: Boolean get() = pairing.recorded
    val events: Int get() = (if (client == null) 0 else POLLIN) or (if (output.size > sent) POLLOUT else 0)
    val pendingOutput: Boolean get() = client != null && output.size > 0

    fun accept() {
        listener.accept()?.let { next ->
            waiting?.let { close(it.fd) }
            waiting = next
            waitingBy = clock.now() + PAIR_BUDGET
        }
    }

    fun claim() {
        val next = waiting ?: return
        val n = next.readInto(scratch)
        if (n > 0) {
            waiting = null
            waitingBy = null
            client?.let(::drop)
            client = next
            expiresAt = clock.now() + PAIR_BUDGET
            output.append(HolderPairProtocol.offer(pairing.identity) + "\n")
            received(n)
        } else if (n == 0) {
            close(next.fd)
            waiting = null
            waitingBy = null
        }
    }

    fun serve() {
        val current = client ?: return
        if (!settlement.controlHealthy) return drop(current)
        if (output.size > sent) {
            val n = current.writeFrom(output.tail(sent))
            if (n < 0) return drop(current)
            sent += n
            val whole = output.wholeLines(sent)
            output.drop(whole)
            sent -= whole
            settlement.controlDelivered()
        }
        val n = current.readInto(scratch)
        if (n == 0) {
            drop(current)
        } else if (n > 0) {
            received(n)
        }
    }

    /** Replacing data closes only its bound control, never a tentative successor waiting to pair. */
    fun dataChanged(next: Fd?) {
        val previous = pairing.data
        pairing.replace(next)
        if (previous != null && pairedData === previous) client?.let(::drop)
    }

    fun dataLine(line: String): Boolean {
        val claim = HolderPairProtocol.claim(line)
        if (claim != null && client != null) {
            pairing.claimData(claim)
            paired()
        }
        return claim != null
    }

    fun expire() {
        val now = clock.now()
        if (waitingBy?.let { it <= now } == true) {
            waiting?.let { close(it.fd) }
            waiting = null
            waitingBy = null
        }
        if (expiresAt?.let { it <= now } == true || !settlement.controlHealthy) client?.let(::drop)
    }

    fun reset() {
        waiting?.let { close(it.fd) }
        waiting = null
        waitingBy = null
        client?.let(::drop)
    }

    fun remove() = listener.remove()

    private fun received(n: Int) {
        if (input.size + n > MAX_CONTROL_INPUT) {
            client?.let(::drop)
            return
        }
        input.append(scratch, n)
        var nl = input.indexOf(CONTROL_NL)
        while (nl >= 0 && client != null) {
            val line = input.text(0, nl)
            input.drop(nl + 1)
            val claim = HolderPairProtocol.claim(line)
            if (claim != null && pairedData == null) {
                pairing.claimControl(claim)
                paired()
            } else if (pairedData != null) {
                settlement.acceptControl(line)
            }
            nl = input.indexOf(CONTROL_NL)
        }
    }

    private fun paired() {
        val current = client ?: return
        if (pairedData != null || !pairing.matches(current)) return
        pairedData = pairing.data
        expiresAt = null
        output.append(HolderPairProtocol.paired(requireNotNull(pairing.token)) + "\n")
        settlement.controlChanged(true)
    }

    private fun drop(current: Fd) {
        if (client !== current) return
        close(current.fd)
        client = null
        pairedData = null
        expiresAt = null
        input.clear()
        sent = 0
        pairing.controlLost()
        settlement.controlChanged(false)
    }
}

/** One live data descriptor is the owner of the pair, not a socket path or a mutable current-link lookup. */
private class HolderConnectionPair(val identity: String) {
    var data: Fd? = null
        private set
    private var dataClaim: HolderPairClaim? = null
    private var controlClaim: HolderPairClaim? = null
    private val peers = HolderPeerCredentials()
    var recorded = false
        private set
    val token: String? get() = dataClaim?.token

    fun replace(next: Fd?) {
        data = next
        dataClaim = null
        recorded = false
    }

    fun claimData(claim: HolderPairClaim) {
        if (claim.identity == identity && dataClaim == null) dataClaim = claim
    }

    fun claimControl(claim: HolderPairClaim) {
        if (claim.identity == identity && controlClaim == null) controlClaim = claim
    }

    fun matches(control: Fd): Boolean {
        val current = data ?: return false
        val peer = peers.read(current)
        val matched = dataClaim != null && dataClaim == controlClaim && peer != null && peer == peers.read(control)
        if (matched) recorded = true
        return matched
    }

    fun controlLost() {
        controlClaim = null
        dataClaim = null
    }
}

/** SO_PEERCRED is three Linux 32-bit fields, read from the connected socket rather than from its pathname. */
private class HolderPeerCredentials {
    fun read(fd: Fd): List<Int>? = memScoped {
        val peer = allocArray<IntVar>(PEER_FIELDS)
        val size = alloc<UIntVar>()
        size.value = (PEER_FIELDS * Int.SIZE_BYTES).convert()
        val read = getsockopt(fd.fd, SOL_SOCKET, LINUX_SO_PEERCRED, peer, size.ptr)
        if (read == 0 && size.value.toInt() == PEER_FIELDS * Int.SIZE_BYTES) {
            List(PEER_FIELDS) { peer[it] }
        } else {
            null
        }
    }
}

private const val CONTROL_CHUNK = 64 * 1024
private const val MAX_CONTROL_INPUT = 128 * 1024
private const val PAIR_BUDGET = 60_000L
private const val PEER_FIELDS = 3
private const val LINUX_SO_PEERCRED = 17
private const val CONTROL_LINE_FEED: Byte = 10
private val CONTROL_NL = byteArrayOf(CONTROL_LINE_FEED)
