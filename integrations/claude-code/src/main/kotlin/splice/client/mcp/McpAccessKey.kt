// NEW: v0.4.0 FEATURES.md §8 — head MCP connections carry a scoped bearer, never the management key.
package splice.client.mcp

import splice.core.auth.BearerScheme
import splice.core.auth.ScopedKey
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom

private const val MCP_SCOPE = "splice:mcp-access:v1"
private const val MCP_ROTATED_SCOPE = "splice:mcp-access:v2:"

// why: ties a published bearer to the management key that issued it, so a replaced management key is detectable.
private const val MCP_BINDING_SCOPE = "splice:mcp-binding:v1:"

// why: 256 fresh random bits separate each MCP incarnation before HMAC-SHA256 derives its capability.
private const val MCP_NONCE_BYTES = 32

/** Domain-separated MCP authority. Production instances share the persisted publication with the
 *  native front, read afresh for every bearer check and generated header. Bootstrap alone adopts
 *  v1; explicit rotation replaces it without changing management or session-turn authority, and a
 *  replaced management key replaces it at the next adoption. */
public class McpAccessKey(
    stateDir: Path? = null,
    /** The management key's file: an unbound publication older than it was issued under an earlier management key. */
    private val managementFile: Path? = null,
    private val source: McpBearer,
) : McpBearer {
    private val publication = stateDir?.let(::McpAccessPublication)

    @Volatile
    private var adopted = false

    override fun invoke(): String {
        val held = publication ?: return ScopedKey.derive(source(), MCP_SCOPE)
        if (!adopted) adopt(held)
        return held.read()
    }

    /** Metadata only, safe to return over the management API. */
    public fun publicationFile(): Path = checkNotNull(publication) { "MCP rotation requires persisted state" }.file

    /** Publishes one new incarnation under the unchanged management key. [expected] prevents a
     *  prepared header update from revoking a different incarnation. No old-bearer grace exists.
     *  The returned secret is for header regeneration only, never a response, argv or log value. */
    public fun rotate(expected: String): String {
        val published = checkNotNull(publication) { "MCP rotation requires persisted state" }
        if (!adopted) adopt(published)
        val replacement = incarnation()
        published.replace(expected, replacement, bindingOf(replacement))
        return replacement
    }

    /** An unreadable publication refuses every bearer: MCP fails closed, the rest of the daemon keeps serving. */
    public fun matchesBearer(header: String?): Boolean {
        val presented = BearerScheme.bearerToken(header)?.toByteArray(UTF_8) ?: return false
        val current = try {
            invoke()
        } catch (_: IOException) {
            return false
        }
        val expected = current.toByteArray(UTF_8)
        return presented.size == expected.size && MessageDigest.isEqual(presented, expected)
    }

    /** First use adopts the publication for the CURRENT management key, never construction: a publication that
     *  cannot be read turns MCP off where it is asked for (the hub's 503, the guard's refusal) while the daemon and
     *  its heads still start. A publication another management key issued is replaced, so replacing the management
     *  key revokes the MCP bearer, as it did when this bearer was derived statelessly. A lost publication after a
     *  rotation gets a fresh incarnation, never the revoked bootstrap bearer. */
    private fun adopt(held: McpAccessPublication) {
        synchronized(this) {
            if (adopted) return
            held.prepareDirectory()
            if (!held.present()) {
                val fenced = held.fenced()
                val fresh = if (fenced) incarnation() else ScopedKey.derive(source(), MCP_SCOPE)
                if (fenced) held.publish(fresh, bindingOf(fresh)) else held.create(fresh, bindingOf(fresh))
            } else {
                val key = held.read()
                val bound = held.binding()
                if (bound == null && issuedUnderCurrent(key, held)) {
                    held.bind(bindingOf(key))
                } else if (bound != bindingOf(key)) {
                    val fresh = incarnation()
                    held.publish(fresh, bindingOf(fresh))
                }
            }
            held.read()
            adopted = true
        }
    }

    /** A publication from before bindings existed belongs to the current management key when it is that key's v1
     *  derivation, or was published after the management key's file was minted: rotation always derives from the
     *  management key the daemon holds, and that file is only ever written when a key is minted. */
    private fun issuedUnderCurrent(key: String, held: McpAccessPublication): Boolean {
        if (key == ScopedKey.derive(source(), MCP_SCOPE)) return true
        val minted = managementFile ?: return false
        return try {
            Files.getLastModifiedTime(held.file, NOFOLLOW_LINKS) >= Files.getLastModifiedTime(minted, NOFOLLOW_LINKS)
        } catch (_: IOException) {
            false
        }
    }

    private fun incarnation(): String {
        val nonce = ByteArray(MCP_NONCE_BYTES).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        return ScopedKey.derive(source(), MCP_ROTATED_SCOPE + nonce)
    }

    private fun bindingOf(key: String): String = ScopedKey.derive(source(), MCP_BINDING_SCOPE + key)
}
