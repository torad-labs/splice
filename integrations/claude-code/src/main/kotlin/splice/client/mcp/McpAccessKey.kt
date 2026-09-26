// NEW: v0.4.0 FEATURES.md §8 — head MCP connections carry a scoped bearer, never the management key.
package splice.client.mcp

import splice.core.auth.BearerScheme
import splice.core.auth.ScopedKey
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

private const val MCP_SCOPE = "splice:mcp-access:v1"

/** Domain separation keeps the management secret out of generated client configs. A deliberate
 *  management-key rotation revokes this derived capability too; no second on-disk secret is needed. */
public class McpAccessKey(private val source: McpBearer) : McpBearer {
    override fun invoke(): String = ScopedKey.derive(source(), MCP_SCOPE)

    public fun matchesBearer(header: String?): Boolean {
        val presented = BearerScheme.bearerToken(header)?.toByteArray(UTF_8) ?: return false
        val expected = invoke().toByteArray(UTF_8)
        return presented.size == expected.size && MessageDigest.isEqual(presented, expected)
    }
}
