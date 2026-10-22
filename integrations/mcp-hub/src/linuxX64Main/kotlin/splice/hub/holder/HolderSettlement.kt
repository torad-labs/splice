package splice.hub.holder

import splice.hub.io.Bytes

/** Bounded exact-key evidence retained beside the holder's byte queues, never in the MCP server. */
internal class HolderSettlement(
    private val toServer: Bytes,
    private val toControl: Bytes,
) {
    private val settlements = HashMap<String, Settlement>()
    private val serverReceipts = ArrayDeque<ServerReceipt>()
    private val awaitingReverse = ArrayDeque<ArmedReverse>()
    private val notices = ArrayDeque<String>()
    private var serverQueued = 0L
    private var serverWritten = 0L
    private var protocolReady = false
    private var controlAvailable = false

    fun acceptClient(line: String) {
        queueServer(line)
    }

    fun acceptControl(line: String) {
        HolderProtocol.command(line)?.let(::control)
    }

    fun controlChanged(available: Boolean) {
        controlAvailable = available
        if (available && protocolReady) notice(HolderControlWire.ready())
        if (available) flushNotices()
    }

    fun controlDelivered() {
        flushNotices()
    }

    fun clientDelivered(line: String) {
        HolderProtocol.response(line)?.let(::settled)
    }

    fun serverFed(n: Int) {
        serverWritten += n
        toServer.drop(n)
        while (serverReceipts.firstOrNull()?.through?.let { it <= serverWritten } == true) {
            val receipt = serverReceipts.removeFirst()
            receipt.key?.let(::settled)
            if (receipt.announce && !protocolReady) {
                protocolReady = true
                if (controlAvailable) notice(HolderControlWire.ready())
            }
        }
    }

    private fun control(command: HolderProtocol.Command) {
        when (command) {
            is HolderProtocol.Command.Recover -> {
                settlements.forEach { (key, state) ->
                    notice(HolderControlWire.state(command.round, key, state.wire))
                }
                notice(HolderControlWire.complete(command.round))
            }
            is HolderProtocol.Command.Acknowledge -> settlements.remove(command.key)
            is HolderProtocol.Command.Reverse -> reverse(command)
        }
    }

    private fun reverse(command: HolderProtocol.Command.Reverse) {
        when (settlements[command.key]) {
            null -> if (settlements.size < MAX_SETTLEMENTS) {
                settlements[command.key] = Settlement.ARMED
                awaitingReverse.addLast(ArmedReverse(command.key, command.identity))
                notice(HolderControlWire.armed(command.key))
            }
            Settlement.ARMED -> notice(HolderControlWire.armed(command.key))
            Settlement.PENDING, Settlement.QUEUED, Settlement.SETTLED -> Unit
        }
    }

    private fun queueServer(line: String) {
        val armed = awaitingReverse.firstOrNull()?.takeIf { settlements[it.key] == Settlement.ARMED }
        if (armed != null && HolderProtocol.reverseResponse(line, armed.identity)) {
            awaitingReverse.removeFirst()
            settlements[armed.key] = Settlement.QUEUED
            appendServer(line, armed.key, announce = false)
        } else {
            queueForward(line)
        }
    }

    private fun queueForward(line: String) {
        val forward = HolderProtocol.forward(line)
        val retained = forward?.let(settlements::containsKey) ?: true
        val room = settlements.size < MAX_SETTLEMENTS
        if (retained || room) {
            if (forward != null && !settlements.containsKey(forward)) {
                settlements[forward] = Settlement.PENDING
            }
            appendServer(line, null, HolderProtocol.initialized(line))
        } else {
            notice(HolderControlWire.refused(requireNotNull(forward)))
        }
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
            notice(HolderControlWire.settled(key))
        }
    }

    private fun notice(line: String) {
        if (notices.size < MAX_NOTICES) notices.addLast(line)
        flushNotices()
    }

    private fun flushNotices() {
        while (controlAvailable && notices.isNotEmpty()) {
            val bytes = (notices.first() + "\n").encodeToByteArray()
            if (toControl.size + bytes.size > HOLDER_BUFFER_CAP) return
            notices.removeFirst()
            toControl.append(bytes)
        }
    }
}

private data class ArmedReverse(val key: String, val identity: String)

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
