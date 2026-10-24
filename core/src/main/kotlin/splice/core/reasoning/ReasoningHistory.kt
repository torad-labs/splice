package splice.core.reasoning

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.HexFormat

/** A position in the client's visible history. Reasoning is stored beside it, never inside that history.
 * Text boundaries are deliberately absent from the digest: splitting or coalescing adjacent text preserves it.
 * Text hashes fixed UTF-16 code units, including unpaired surrogates, so chunk seams never change identity.
 * A build supplies a [copy] to its sequential continuation rounds. */
public class ReasoningHistory {
    private var digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    private var characters = 0L
    private var role = ""
    private var inText = false

    public val offset: Long get() = characters

    public val key: String
        get() = "$HISTORY_POSITION_PREFIX$offset:${HexFormat.of().formatHex(snapshot().digest())}"

    public fun copy(): ReasoningHistory = ReasoningHistory().also { it.restore(this) }

    /** Publish a reconciled final-output position without counting already streamed text twice. */
    public fun restore(position: ReasoningHistory) {
        digest = position.snapshot()
        characters = position.characters
        role = position.role
        inText = position.inText
    }

    public fun text(role: String, text: String) {
        if (text.isEmpty()) return
        changeRole(role)
        if (!inText) token("text")
        inText = true
        // Grammar: a text code unit is 0 + big-endian UTF-16; a token starts with 1 and its length.
        // This is independent of text-block boundaries and cannot confuse text with a role/tool token.
        text.forEach { character ->
            digest.update(0.toByte())
            digest.update((character.code ushr Byte.SIZE_BITS).toByte())
            digest.update(character.code.toByte())
        }
        characters += text.length
    }

    public fun block(role: String, kind: String, identity: JsonElement) {
        changeRole(role)
        token(kind)
        canonical(identity)
        inText = false
        characters++
    }

    private fun changeRole(value: String) {
        if (role == value) return
        token("role:$value")
        role = value
        inText = false
    }

    private fun token(value: String) {
        digest.update(1.toByte())
        for (shift in LENGTH_SHIFTS) digest.update((value.length ushr shift).toByte())
        value.forEach { character ->
            digest.update((character.code ushr Byte.SIZE_BITS).toByte())
            digest.update(character.code.toByte())
        }
    }

    /** Iterative canonical JSON: argument key order is irrelevant; changed values remain different. */
    private fun canonical(value: JsonElement) {
        val pending = ArrayDeque<JsonElement>()
        pending.add(value)
        while (pending.isNotEmpty()) {
            when (val next = pending.removeLast()) {
                is JsonObject -> {
                    token("object:${next.size}")
                    next.entries.sortedByDescending { it.key }.forEach { (key, child) ->
                        pending.add(child)
                        pending.add(JsonPrimitive(key))
                    }
                }
                is JsonArray -> {
                    token("array:${next.size}")
                    next.asReversed().forEach(pending::add)
                }
                is JsonPrimitive -> token(next.toString())
            }
        }
    }

    private fun snapshot(): MessageDigest = requireNotNull(digest.clone() as? MessageDigest) {
        "SHA-256 provider did not return a digest snapshot"
    }
}

/** Private cache indexes use this namespace; these are never upstream function_call ids. */
public const val HISTORY_POSITION_PREFIX: String = "splice-reasoning-at:"
private val LENGTH_SHIFTS = IntArray(Int.SIZE_BYTES) { (Int.SIZE_BYTES - 1 - it) * Byte.SIZE_BITS }
