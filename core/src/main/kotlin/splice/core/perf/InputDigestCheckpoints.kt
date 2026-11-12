package splice.core.perf

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Separate canonical domains for the input array and every other request property, including reasoning effort. */
public interface InputPrefixDigests {
    public fun input(value: JsonArray): String
    public fun properties(value: JsonObject): String
}

/** Measured-input ownership explicitly releases bounded evidence on replacement or eviction.
 * Verification still encodes every current byte once, comparing inline rather than trusting tree equality. */
public class InputDigestCheckpoints(budget: InputDigestCheckpointBudget) : InputPrefixDigests, AutoCloseable {
    private val input = DigestPrefixCheckpoint(budget)
    private val properties = DigestPrefixCheckpoint(budget)
    private val recording = object : InputPrefixDigests {
        override fun input(value: JsonArray): String = input.hex(value, remember = true)
        override fun properties(value: JsonObject): String = properties.hex(value, remember = true)
    }

    public fun capture(request: JsonObject): InputPrefix? = InputDigest.capture(request, recording)

    override fun input(value: JsonArray): String = input.hex(value, remember = false)

    override fun properties(value: JsonObject): String = properties.hex(value, remember = false)

    override fun close() {
        input.close()
        properties.close()
    }
}
