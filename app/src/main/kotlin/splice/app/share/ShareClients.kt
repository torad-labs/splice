// NEW: LAN share, phase 1 (docs/specs/lan-share.md). The machines that joined this one, each with its own
// token. The token itself is shown once, in the join's answer; this file keeps only its SHA-256 beside the
// name and the address the machine joined from, in <state>/share/clients.json through the one 0600 writer.
// Read again on every request, the KeyStore's rule: the file is a few lines, and a `splice share revoke` run
// in another process takes effect on the next turn with no restart.
package splice.app.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.util.PathProbe
import splice.core.util.SecureFile
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.HexFormat

/** One joined machine. [tokenSha256] is the hex SHA-256 of its token; the token is never stored. */
@Serializable
internal data class ShareClient(
    val name: String,
    @SerialName("token_sha256") val tokenSha256: String,
    val address: String,
    @SerialName("joined_at") val joinedAt: String,
)

@Serializable
internal data class ShareClientBook(val clients: List<ShareClient> = emptyList())

internal class ShareClients(private val file: Path) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val random = SecureRandom()
    private val hex = HexFormat.of()

    /** A new token for [name], replacing any it held: a machine that imports again gets a fresh one and the
     *  old one stops working. */
    @Synchronized
    fun join(name: String, address: String): String {
        val token = hex.formatHex(ByteArray(SHARE_TOKEN_BYTES).also(random::nextBytes))
        val joined = ShareClient(name, digest(token), address, Instant.now().toString())
        write(ShareClientBook(read().clients.filterNot { it.name == name } + joined))
        return token
    }

    /** The machine [token] belongs to, compared in constant time; null for no token or an unknown one. */
    fun holder(token: String?): ShareClient? {
        val presented = token?.let { digest(it).toByteArray(UTF_8) } ?: return null
        return read().clients.firstOrNull { MessageDigest.isEqual(it.tokenSha256.toByteArray(UTF_8), presented) }
    }

    /** False when no machine has that name. */
    @Synchronized
    fun revoke(name: String): Boolean {
        val book = read()
        val kept = book.clients.filterNot { it.name == name }
        if (kept.size == book.clients.size) return false
        write(ShareClientBook(kept))
        return true
    }

    fun all(): List<ShareClient> = read().clients

    /** A missing file is no machines. One that does not parse also reads as none: a damaged file must lock
     *  machines out, never let an unknown one in. */
    private fun read(): ShareClientBook {
        val text = PathProbe.text(file) ?: return ShareClientBook()
        return Cancellables.runCatchingCancellable { json.decodeFromString(ShareClientBook.serializer(), text) }
            .fold(onSuccess = { it }, onFailure = { ShareClientBook() })
    }

    private fun write(book: ShareClientBook) {
        SecureFile.writeAtomic0600(file, json.encodeToString(ShareClientBook.serializer(), book) + "\n")
    }

    private fun digest(token: String): String =
        hex.formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(UTF_8)))
}

// why: 256 random bits, the strength of the SHA-256 it is stored as; the token is all a joined machine presents,
// and the listener faces a whole network.
private const val SHARE_TOKEN_BYTES = 32
