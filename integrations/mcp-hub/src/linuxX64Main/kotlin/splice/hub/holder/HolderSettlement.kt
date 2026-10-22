package splice.hub.holder

import splice.hub.io.Bytes

/** Bounded exact-key evidence retained beside the holder's byte queues, never in the MCP server. */
internal class HolderSettlement(
    private val toServer: Bytes,
    private val toClient: Bytes,
) {
    private val settlements = HashMap<String, Settlement>()
    private val serverReceipts = ArrayDeque<ServerReceipt>()
    private val awaitingReverse = ArrayDeque<String>()
    private val notices = ArrayDeque<String>()
    private var serverQueued = 0L
    private var serverWritten = 0L
    private var protocolReady = false

    fun accept(line: String) {
        val command = HolderProtocol.command(line)
        if (command == null) queueServer(line) else control(command)
    }

    fun adopted() {
        if (protocolReady) notice(HolderProtocol.ready())
    }

    fun clientDelivered(line: String) {
        HolderProtocol.response(line)?.let(::settled)
        flushNotices()
    }

    fun serverFed(n: Int) {
        serverWritten += n
        toServer.drop(n)
        while (serverReceipts.firstOrNull()?.through?.let { it <= serverWritten } == true) {
            val receipt = serverReceipts.removeFirst()
            receipt.key?.let(::settled)
            if (receipt.announce && !protocolReady) {
                protocolReady = true
                notice(HolderProtocol.ready())
            }
        }
    }

    private fun control(command: HolderProtocol.Command) {
        when (command) {
            is HolderProtocol.Command.Recover -> recovered(command.round)
            is HolderProtocol.Command.Acknowledge -> settlements.remove(command.key)
            is HolderProtocol.Command.Reverse -> reverse(command.key)
        }
    }

    private fun recovered(round: String) {
        settlements.forEach { (key, state) -> notice(HolderProtocol.state(round, key, state.wire)) }
        notice(HolderProtocol.complete(round))
    }

    private fun reverse(key: String) {
        when (settlements[key]) {
            null -> if (settlements.size < MAX_SETTLEMENTS) {
                settlements[key] = Settlement.ARMED
                awaitingReverse.addLast(key)
            }
            Settlement.ARMED, Settlement.PENDING, Settlement.QUEUED, Settlement.SETTLED -> Unit
        }
    }

    private fun queueServer(line: String) {
        val reverse = awaitingReverse.firstOrNull()
        if (reverse != null && settlements[reverse] == Settlement.ARMED) {
            awaitingReverse.removeFirst()
            settlements[reverse] = Settlement.QUEUED
            appendServer(line, reverse, announce = false)
        } else {
            queueForward(line)
        }
    }

    private fun queueForward(line: String) {
        val forward = HolderProtocol.forward(line)
        if (admitsForward(forward)) {
            forward?.let { settlements.putIfAbsent(it, Settlement.PENDING) }
            appendServer(line, null, HolderProtocol.initialized(line))
        } else {
            notice(HolderProtocol.refused(requireNotNull(forward)))
        }
    }

    private fun admitsForward(key: String?): Boolean = when {
        key == null -> true
        settlements.containsKey(key) -> true
        else -> settlements.size < MAX_SETTLEMENTS
    }

    private fun appendServer(line: String, key: String?, announce: Boolean) {
        val bytes = (line + "\n").encodeToByteArray()
        toServer.append(bytes)
        serverQueued += bytes.size
        if (key != null || announce) serverReceipts.addLast(ServerReceipt(serverQueued, key, announce))
    }

    private fun settled(key: String) {
        if (settlements[key] != null && settlements[key] != Settlement.SETTLED) {
            settlements[key] = Settlement.SETTLED
            notice(HolderProtocol.settled(key))
        }
    }

    private fun notice(line: String) {
        if (notices.size < MAX_NOTICES) notices.addLast(line)
        flushNotices()
    }

    private fun flushNotices() {
        while (notices.isNotEmpty()) {
            val bytes = (notices.first() + "\n").encodeToByteArray()
            if (toClient.size + bytes.size > HOLDER_BUFFER_CAP) return
            notices.removeFirst()
            toClient.append(bytes)
        }
    }
}

private data class ServerReceipt(
    val through: Long,
    val key: String?,
    val announce: Boolean,
)

private enum class Settlement(val wire: String) {
    PENDING("pending"),
    ARMED("armed"),
    QUEUED("queued"),
    SETTLED("settled"),
}

private const val MAX_SETTLEMENTS = 512
private const val NOTICES_PER_SETTLEMENT = 2
private const val RECOVERY_COMPLETE_NOTICE = 1
private const val MAX_NOTICES = MAX_SETTLEMENTS * NOTICES_PER_SETTLEMENT + RECOVERY_COMPLETE_NOTICE
