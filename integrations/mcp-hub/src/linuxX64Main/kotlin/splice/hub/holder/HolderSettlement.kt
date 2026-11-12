package splice.hub.holder

import splice.hub.io.Bytes

/** Owns the byte boundary between durable evidence, the child pipe and the paired data socket. */
internal class HolderSettlement(
    private val toServer: Bytes,
    toControl: Bytes,
) {
    private val evidence = HolderSettlementEvidence()
    private val notices = HolderReceiptNotices(toControl)
    private val serverReceipts = ArrayDeque<ServerReceipt>()
    private var serverQueued = 0L
    private var serverWritten = 0L
    private var protocolReady = false
    private var initializationQueued = false
    private var barrier: String? = null
    private var recovering: String? = null
    val controlHealthy: Boolean get() = !notices.failed

    fun acceptClient(line: String, recorded: Boolean) {
        when {
            HolderPairProtocol.privateFrame(line) -> if (recorded) privateData(line)
            HolderProtocol.responseIdentity(line) != null -> {
                if (!recorded && !evidence.hasReverse) queueForward(line, recorded = false)
            }
            line.isNotBlank() -> queueForward(line, recorded)
        }
    }

    fun acceptControl(line: String) {
        when (val command = HolderProtocol.command(line)) {
            is HolderProtocol.Command.Recover -> {
                recovering = command.round
                recover()
            }
            is HolderProtocol.Command.Acknowledge -> evidence.acknowledge(command.key)
            is HolderProtocol.Command.Reverse -> {
                if (evidence.arm(command)) {
                    notices.add(HolderControlWire.armed(command.key))
                } else if (evidence.absent(command.key)) {
                    notices.add(HolderControlWire.refused(command.key))
                }
            }
            null -> Unit
        }
    }

    fun controlChanged(available: Boolean) {
        notices.connected(available)
        barrier = null
        recovering = null
        if (available && protocolReady) notices.add(HolderControlWire.ready())
    }

    fun controlDelivered() = notices.flush()

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
                notices.add(HolderControlWire.ready())
            }
        }
        if (serverReceipts.isEmpty() || serverWritten >= HOLDER_BUFFER_CAP) {
            serverReceipts.forEach { it.through -= serverWritten }
            serverQueued -= serverWritten
            serverWritten = 0
        }
    }

    private fun privateData(line: String) {
        val response = HolderPairProtocol.response(line)
        val rejection = HolderAdmissionWire.rejected(line)
        val round = HolderPairProtocol.barrierRound(line)
        when {
            round != null -> {
                barrier = round
                recover()
            }
            response != null -> {
                if (room(response.text) && evidence.queue(response)) appendServer(response.text, response.key)
            }
            rejection != null -> reject(rejection)
        }
    }

    private fun recover() {
        val round = recovering?.takeIf { it == barrier } ?: return
        evidence.snapshot().forEach { (key, state) -> notices.add(HolderControlWire.state(round, key, state)) }
        notices.add(HolderControlWire.complete(round))
        recovering = null
        barrier = null
    }

    private fun queueForward(line: String, recorded: Boolean) {
        val key = HolderProtocol.forward(line)?.takeIf { recorded }
        when {
            !room(line) -> notices.fail()
            key == null -> appendServer(line, null)
            else -> when (evidence.admit(key)) {
                ForwardRetention.ADMITTED -> appendServer(line, null)
                ForwardRetention.DUPLICATE -> evidence.terminal(key)?.let { notices.add(HolderControlWire.settled(it)) }
                ForwardRetention.FULL -> notices.add(HolderControlWire.refused(key))
            }
        }
    }

    private fun reject(line: String) {
        if (room(line) && !evidence.conflicts(line)) appendServer(line, null)
    }

    private fun room(line: String): Boolean =
        toServer.size.toLong() + line.encodeToByteArray().size + 1 <= HOLDER_BUFFER_CAP

    private fun appendServer(line: String, key: String?) {
        val bytes = (line + "\n").encodeToByteArray()
        toServer.append(bytes)
        serverQueued += bytes.size
        val announce = !initializationQueued && HolderProtocol.initialized(line)
        if (announce) initializationQueued = true
        if (key != null || announce) serverReceipts.addLast(ServerReceipt(serverQueued, key, announce))
    }

    private fun settled(key: String) {
        if (evidence.settle(key)) notices.add(HolderControlWire.settled(key))
    }
}

/** Acks release only terminal evidence. Queued child bytes and armed identities cannot be erased by an ack. */
private class HolderSettlementEvidence {
    private val records = HashMap<String, HolderSettlementRecord>()
    private var identityBytes = 0
    private var forwardHighWater = "0"
    val hasReverse: Boolean get() = records.values.any { it.identity != null }

    fun admit(key: String): ForwardRetention {
        val number = key.removePrefix("f:").trimStart('0').ifEmpty { "0" }
        val old = number.length < forwardHighWater.length ||
            number.length == forwardHighWater.length && number <= forwardHighWater
        return when {
            key in records || old -> ForwardRetention.DUPLICATE
            records.size >= MAX_SETTLEMENTS -> ForwardRetention.FULL
            else -> {
                forwardHighWater = number
                records[key] = HolderSettlementRecord(Settlement.PENDING)
                ForwardRetention.ADMITTED
            }
        }
    }

    fun arm(command: HolderProtocol.Command.Reverse): Boolean {
        val prior = records[command.key]
        val room = records.size < MAX_SETTLEMENTS && identityBytes + command.identity.length <= MAX_IDENTITY_BYTES
        if (prior == null && room) {
            records[command.key] = HolderSettlementRecord(Settlement.ARMED, command.identity)
            identityBytes += command.identity.length
        }
        val current = records[command.key]
        return current?.state == Settlement.ARMED && current.identity == command.identity
    }

    fun queue(response: HolderReverseResponse): Boolean {
        val record = records[response.key] ?: return false
        val matches = record.state == Settlement.ARMED &&
            record.identity?.let { HolderProtocol.reverseResponse(response.text, it) } == true
        if (matches) record.state = Settlement.QUEUED
        return matches
    }

    fun settle(key: String): Boolean {
        val record = records[key] ?: return false
        val allowed = record.state == Settlement.PENDING || record.state == Settlement.QUEUED
        if (allowed) record.state = Settlement.SETTLED
        return allowed
    }

    fun conflicts(line: String): Boolean {
        val identity = HolderProtocol.responseIdentity(line)
        return identity != null && records.values.any { it.identity == identity }
    }

    fun absent(key: String): Boolean = key !in records

    fun terminal(key: String): String? = key.takeIf { records[it]?.state == Settlement.SETTLED }

    fun acknowledge(key: String) {
        val record = records[key]?.takeIf { it.state == Settlement.SETTLED } ?: return
        records.remove(key)
        identityBytes -= record.identity?.length ?: 0
    }

    fun snapshot(): Map<String, String> = records.mapValues { it.value.state.wire }
}

/** Losing notices is allowed only with a lost control connection; recovery reconstructs the complete evidence set. */
private class HolderReceiptNotices(private val output: Bytes) {
    private val waiting = ArrayDeque<ByteArray>()
    private var retainedBytes = 0
    private var available = false
    var failed = false
        private set

    fun connected(next: Boolean) {
        available = next
        if (!next) {
            waiting.clear()
            output.clear()
            retainedBytes = 0
            failed = false
        }
    }

    fun fail() {
        failed = true
    }

    fun add(line: String) {
        if (!available || failed) return
        val bytes = (line + "\n").encodeToByteArray()
        if (waiting.size >= MAX_NOTICES || retainedBytes.toLong() + output.size + bytes.size > MAX_CONTROL_BYTES) {
            fail()
        } else {
            waiting.addLast(bytes)
            retainedBytes += bytes.size
            flush()
        }
    }

    fun flush() {
        while (waiting.isNotEmpty()) {
            if (!available || failed) return
            val bytes = waiting.first()
            if (output.size.toLong() + bytes.size > MAX_CONTROL_BYTES) return
            waiting.removeFirst()
            retainedBytes -= bytes.size
            output.append(bytes)
        }
    }
}

private data class HolderSettlementRecord(var state: Settlement, val identity: String? = null)

private data class ServerReceipt(var through: Long, val key: String?, val announce: Boolean)

private enum class ForwardRetention { ADMITTED, DUPLICATE, FULL }

private enum class Settlement(val wire: String) {
    PENDING("pending"),
    ARMED("armed"),
    QUEUED("queued"),
    SETTLED("settled"),
}

private const val MAX_SETTLEMENTS = 512
private const val MAX_IDENTITY_BYTES = 1024 * 1024
private const val MAX_CONTROL_BYTES = 1024 * 1024
private const val MAX_NOTICES = 1025
