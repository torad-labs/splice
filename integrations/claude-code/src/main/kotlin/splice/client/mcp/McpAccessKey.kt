// NEW: v0.4.0 FEATURES.md §8 — head MCP connections carry a scoped bearer, never the management key.
package splice.client.mcp

import splice.core.auth.BearerScheme
import splice.core.auth.ScopedKey
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom

private const val MCP_SCOPE = "splice:mcp-access:v1"
private const val MCP_ROTATED_SCOPE = "splice:mcp-access:v2:"
private const val MCP_NONCE_BYTES = 32

/** Domain-separated MCP authority. Production instances share the persisted publication with the
 *  native front, read afresh for every bearer check and generated header. Bootstrap alone adopts
 *  v1; explicit rotation replaces it without changing management or session-turn authority. */
public class McpAccessKey(
    stateDir: Path? = null,
    private val source: McpBearer,
) : McpBearer {
    private val publication = stateDir?.let(::McpAccessPublication)

    init {
        publication?.bootstrap(McpBearer { ScopedKey.derive(source(), MCP_SCOPE) })
    }

    override fun invoke(): String = publication?.read() ?: ScopedKey.derive(source(), MCP_SCOPE)

    /** Metadata only, safe to return over the management API. */
    public fun publicationFile(): Path = checkNotNull(publication) { "MCP rotation requires persisted state" }.file

    /** Publishes one new incarnation under the unchanged management key. [expected] prevents a
     *  prepared header update from revoking a different incarnation. No old-bearer grace exists.
     *  The returned secret is for header regeneration only, never a response, argv or log value. */
    public fun rotate(expected: String): String {
        val published = checkNotNull(publication) { "MCP rotation requires persisted state" }
        val nonce = ByteArray(MCP_NONCE_BYTES).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val replacement = ScopedKey.derive(source(), MCP_ROTATED_SCOPE + nonce)
        published.replace(expected, replacement)
        return replacement
    }

    public fun matchesBearer(header: String?): Boolean {
        val presented = BearerScheme.bearerToken(header)?.toByteArray(UTF_8) ?: return false
        val expected = invoke().toByteArray(UTF_8)
        return presented.size == expected.size && MessageDigest.isEqual(presented, expected)
    }
}
