// Mint the configured mark, a public 8-character ID and a 40-character secret (~238 bits).
// Only the SHA-256 of the complete key goes on disk. Verification accepts any secret of SERVER_KEY_SECRET_MIN_LENGTH
// or more, so a key imported from an issuer that minted shorter secrets keeps working.
package splice.server.keys

import splice.core.topology.SERVER_KEY_ID_LENGTH
import splice.core.topology.SERVER_KEY_MINTED_LENGTH
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat

internal class KeyMint(private val random: SecureRandom, private val mark: String) {
    private val lower = ('a'..'z') + ('0'..'9')
    private val mixed = lower + ('A'..'Z')

    /** A new prefix that is none of [taken], so a prefix names one key. */
    fun prefix(taken: Set<String>): String =
        generateSequence { "$mark${letters(lower, SERVER_KEY_ID_LENGTH)}" }.first { it !in taken }

    /** A new key under [prefix]: given to its person once, and kept only as its [hash]. */
    fun key(prefix: String): String = "${prefix}_${letters(mixed, SERVER_KEY_MINTED_LENGTH)}"

    /** The SHA-256 of [key], in hex: what a presented key is looked up by, so the lookup's time says nothing of it. */
    fun hash(key: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray()))

    private fun letters(from: List<Char>, count: Int): String =
        buildString { repeat(count) { append(from[random.nextInt(from.size)]) } }
}
