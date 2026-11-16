// NEW: server mode (docs/specs/server-mode.md, decision 4). A key is `spk_<8 lowercase alphanumerics>_<40
// alphanumerics>`: the first two parts are the prefix that names the key everywhere, and the whole key is kept only as
// its SHA-256, so no file of the server ever holds a key or the secret part of one.
package splice.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat

internal class KeyMint(private val random: SecureRandom) {
    private val lower = ('a'..'z') + ('0'..'9')
    private val mixed = lower + ('A'..'Z')

    // A lookahead, so every place a key could start is tried: a run that only looks like a key cannot swallow the
    // first letters of a real one that follows it.
    private val shaped = Regex("(?=($KEY_MARK[a-z0-9]{$ID_LENGTH}_[A-Za-z0-9]{$SECRET_LENGTH}))")

    /** A new prefix that is none of [taken], so a prefix names one key. */
    fun prefix(taken: Set<String>): String =
        generateSequence { "$KEY_MARK${letters(lower, ID_LENGTH)}" }.first { it !in taken }

    /** A new key under [prefix]: given to its person once, and kept only as its [hash]. */
    fun key(prefix: String): String = "${prefix}_${letters(mixed, SECRET_LENGTH)}"

    /** The SHA-256 of [key], in hex: what a presented key is looked up by, so the lookup's time says nothing of it. */
    fun hash(key: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray()))

    /** Every run of [text] that has a key's shape, wherever it sits: after a scheme, inside a parameter, or alone. */
    fun shapedIn(text: String): Sequence<String> = shaped.findAll(text).map { it.groupValues[1] }

    private fun letters(from: List<Char>, count: Int): String =
        buildString { repeat(count) { append(from[random.nextInt(from.size)]) } }
}

/** What every key starts with. */
internal const val KEY_MARK = "spk_"

// why: decision 4 fixes the visible part at 8 characters, which names 36^8 keys, far past any team's count.
private const val ID_LENGTH = 8

// why: decision 4 asks for 32 or more; 40 characters of 62 carry 238 bits, beyond guessing at any request rate.
private const val SECRET_LENGTH = 40
