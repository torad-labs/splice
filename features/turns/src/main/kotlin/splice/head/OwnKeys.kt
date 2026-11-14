// NEW (Nov 14, 2026): splice's own keys as a head finds them in what a caller sends. Moved out of ClientAuth unchanged,
// so the headers a provider's forward_headers names are checked by the same code as the caller's login.
package splice.head

import java.security.MessageDigest

/** splice's own keys, as a head finds them in a caller's request: the turn key a launched session holds, the
 *  management key, and a key a rotation retired. Compared in constant time. */
internal class OwnKeys(private val tokens: HeadDeps.HeadTokens) {
    // Splits each forwarded value into scheme/credential/parameter tokens for the own-key check.
    // The FULL RFC 7235 delimiter class, not just whitespace (third DR-30 redo): auth-params
    // delimit with "=", DQUOTE and "," — `Digest response=<key>` hid the key from a whitespace
    // split while the raw header still forwarded verbatim. ";" and "'" ride along for legacy
    // scheme syntax. The mgmt key is splice-authored hex, so no delimiter can occur INSIDE it:
    // this split can surface the key but never break it.
    private val authDelimiterRe = Regex("[\\s,=;'\"]+")

    /** True when [value], split as an auth header is, holds one of splice's keys as any of its tokens. */
    fun carriedIn(value: String): Boolean = value.split(authDelimiterRe).any {
        isTurnKey(it) || isManagementKey(it) || tokens.retired(it)
    }

    fun isTurnKey(presented: String?): Boolean = constantTimeEquals(presented, tokens.inferenceToken)

    fun isManagementKey(presented: String?): Boolean = constantTimeEquals(presented, tokens.operatorToken)

    /** Constant-time compare against one of this head's own keys. Length is checked first because
     *  [MessageDigest.isEqual] is only constant-time for equal-length inputs. */
    private fun constantTimeEquals(presented: String?, expected: String): Boolean {
        val presentedBytes = presented?.toByteArray(Charsets.UTF_8) ?: return false
        val expectedBytes = expected.toByteArray(Charsets.UTF_8)
        if (presentedBytes.size != expectedBytes.size) return false
        return MessageDigest.isEqual(presentedBytes, expectedBytes)
    }
}
