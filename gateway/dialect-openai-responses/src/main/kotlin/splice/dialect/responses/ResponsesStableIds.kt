// NEW: the two sha256-hex-prefix id derivations. Split out of ResponsesRequestBuilder.kt
// (2026-08-17, concentration campaign): it already has a real external consumer
// (ResponsesProvider.kt:54) and is a self-contained derivation with its own constants. Every
// member kept its identical name and argument list.
package splice.dialect.responses

import splice.core.wire.AnthropicRequest
import splice.core.wire.TextBlock
import java.security.MessageDigest

/**
 * The two sha256-hex-prefix id derivations. A type rather than the file-level functions they used to
 * be (Kotlin main sources carry no top-level functions); both members keep their old name and
 * argument list, so a call site only gained a receiver.
 */
internal class ResponsesStableIds {

    /** Codex-parity cache key: sha256 of the FIRST user message's text, stable per conversation. */
    public fun stablePromptCacheKey(body: AnthropicRequest): String? {
        val first = body.messages.firstOrNull { it.role == "user" } ?: return null
        val seed = first.content.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
        if (seed.isEmpty()) return null
        val md = SHA256.get()
        md.reset()
        val digest = md.digest(seed.toByteArray(Charsets.UTF_8))
        // Only the first HASH_PREFIX_LEN/2 bytes → HASH_PREFIX_LEN hex chars ("splice-" + 32).
        val hexChars = CharArray(HASH_PREFIX_LEN)
        var hi = 0
        for (i in 0 until HASH_PREFIX_BYTES) {
            val b = digest[i].toInt() and BYTE_MASK
            hexChars[hi++] = HEX_DIGITS[b ushr NIBBLE_BITS]
            hexChars[hi++] = HEX_DIGITS[b and NIBBLE_MASK]
        }
        return "splice-" + String(hexChars)
    }

    /** CHANGE 2's call_id (cache-prefix stability, 2026-07-25): a pure function of the tool NAME
     *  alone — same sha256-hex-prefix idiom as [stablePromptCacheKey] just above, so a deferred
     *  tool's declaration pair carries the identical call_id on every turn it is replayed on (no
     *  transcript position, no counters, no randomness — any of those would reintroduce the exact
     *  cache bust this feature exists to fix). */
    internal fun stableToolSearchCallId(toolName: String): String {
        val md = SHA256.get()
        md.reset()
        val digest = md.digest(toolName.toByteArray(Charsets.UTF_8))
        val hexChars = CharArray(CALL_ID_HEX_LEN)
        var hi = 0
        for (i in 0 until CALL_ID_HEX_LEN / 2) {
            val b = digest[i].toInt() and BYTE_MASK
            hexChars[hi++] = HEX_DIGITS[b ushr NIBBLE_BITS]
            hexChars[hi++] = HEX_DIGITS[b and NIBBLE_MASK]
        }
        return CALL_ID_PREFIX + String(hexChars)
    }
}

private const val HASH_PREFIX_LEN = 32
private const val HASH_PREFIX_BYTES = HASH_PREFIX_LEN / 2
private const val BYTE_MASK = 0xff
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0x0f

// FILE SCOPE ON PURPOSE: one shared hex table.
private val HEX_DIGITS = "0123456789abcdef".toCharArray()

// FILE SCOPE ON PURPOSE: MessageDigest is not thread-safe; a ThreadLocal avoids the provider lookup
// per turn without sharing. As a member it would be re-created per builder instance.
private val SHA256 = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

private const val CALL_ID_PREFIX = "ts_decl_"
private const val CALL_ID_HEX_LEN = 24
