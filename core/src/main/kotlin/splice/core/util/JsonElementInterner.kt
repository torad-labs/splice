// NEW: request-local payload tokens preserve exact JSON equality without repeated structural hashing.
package splice.core.util

import kotlinx.serialization.json.JsonElement
import java.util.IdentityHashMap

/** A stable structural hash: equal JSON elements must have equal hashes. */
public fun interface JsonElementHash {
    public operator fun invoke(element: JsonElement): Int
}

/**
 * Request-local identity for immutable JSON payloads. Tokens are comparable only within this interner.
 * Elements must not be mutated while retained, and the interner is confined to its request's caller.
 */
public class JsonElementInterner(
    private val structuralHash: JsonElementHash = JsonElementHash(JsonElement::hashCode),
) {
    private val tokens = IdentityHashMap<JsonElement, Token>()
    private val buckets = mutableMapOf<Int, MutableList<Entry>>()

    /** Returns the same token exactly when this interner has seen an equal [element]. */
    public fun token(element: JsonElement): Token = tokens[element] ?: intern(element).also { tokens[element] = it }

    private fun intern(element: JsonElement): Token {
        val bucket = buckets.getOrPut(structuralHash(element)) { mutableListOf() }
        return bucket.firstOrNull { it.element == element }?.token
            ?: Token().also { bucket += Entry(element, it) }
    }

    /** An opaque equality key that neither retains nor renders payload content. */
    public class Token internal constructor()

    private data class Entry(val element: JsonElement, val token: Token)
}
