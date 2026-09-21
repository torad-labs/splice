// NEW: v0.4.0 FEATURES.md §8 — head MCP connections carry a scoped bearer, never the management key.
package splice.client.mcp

import splice.core.auth.BearerScheme
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val ALGORITHM = "HmacSHA256"
private const val MCP_SCOPE = "splice:mcp-access:v1"

/** Domain separation keeps the management secret out of generated client configs. A deliberate
 *  management-key rotation revokes this derived capability too; no second on-disk secret is needed. */
public class McpAccessKey(private val source: McpBearer) : McpBearer {
    override fun invoke(): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(source().toByteArray(UTF_8), ALGORITHM))
        return mac.doFinal(MCP_SCOPE.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) }
    }

    public fun matchesBearer(header: String?): Boolean {
        val presented = BearerScheme.bearerToken(header)?.toByteArray(UTF_8) ?: return false
        val expected = invoke().toByteArray(UTF_8)
        return presented.size == expected.size && MessageDigest.isEqual(presented, expected)
    }
}
