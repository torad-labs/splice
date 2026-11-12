package splice.hub.front

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Allocation survives an empty ledger. A key leaves the process only in the checkpoint that also reserves it. */
internal class RelayWireKeys {
    private val forward = WireSequence()
    private val reverse = WireSequence()

    fun forward(): String? = forward.next()?.let { "f:$it" }

    fun reverse(): String? = reverse.next()?.let { "s:$it" }

    fun restore(saved: JsonObject?) {
        val first = Rpc.str(saved, "forward")
        val second = Rpc.str(saved, "reverse")
        require(saved == null || first != null && second != null) { "the persisted wire-key counters are incomplete" }
        forward.restore(first)
        reverse.restore(second)
    }

    fun retained(key: String) {
        when {
            key.startsWith("f:") -> forward.retain(key.removePrefix("f:"))
            key.startsWith("s:") -> reverse.retain(key.removePrefix("s:"))
        }
    }

    fun state(): JsonObject = buildJsonObject {
        put("forward", forward.highWater)
        put("reverse", reverse.highWater)
    }
}

/** Decimal strings stay compatible with old holders without wrapping a machine integer. */
private class WireSequence {
    var highWater = MIGRATION_FLOOR
        private set

    fun restore(value: String?) {
        if (value != null) retain(value)
    }

    fun retain(value: String) {
        val canonical = value.trimStart('0').ifEmpty { "0" }
        when {
            value.isEmpty() || value.any { it !in '0'..'9' } -> highWater = EXHAUSTED
            canonical.length > MAX_SEQUENCE_DIGITS -> highWater = EXHAUSTED
            canonical.length > highWater.length -> highWater = canonical
            canonical.length == highWater.length && canonical > highWater -> highWater = canonical
        }
    }

    fun next(): String? {
        val digits = highWater.toCharArray()
        var at = digits.lastIndex
        while (at >= 0 && digits[at] == '9') {
            digits[at] = '0'
            at--
        }
        val incremented = if (at < 0) {
            "1" + digits.concatToString()
        } else {
            digits[at] = digits[at] + 1
            digits.concatToString()
        }
        if (incremented.length > MAX_SEQUENCE_DIGITS) return null
        highWater = incremented
        return incremented
    }
}

// Legacy keys used ++Long and therefore could never reach this namespace.
private const val MIGRATION_FLOOR = "100000000000000000000"
private const val MAX_SEQUENCE_DIGITS = 64
private const val EXHAUSTED = "9999999999999999999999999999999999999999999999999999999999999999"
