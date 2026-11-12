// NEW: v0.4.0 FEATURES.md §8 — head MCP connections carry a scoped bearer, never the management key.
package splice.client.mcp

import splice.core.auth.BearerScheme
import splice.core.auth.ScopedKey
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
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
    /** Where a replaced publication and each episode of an unreadable one are said: the daemon log. */
    private val log: LogSink = LogSink {},
    private val source: McpBearer,
) : McpBearer {
    private val publication = stateDir?.let(::McpAccessPublication)

    @Volatile
    private var adopted = false

    // the reason MCP bearers are being refused, said once per episode and cleared when the key reads again
    @Volatile
    private var refusing: String? = null

    override fun invoke(): String {
        val held = publication ?: return ScopedKey.derive(source(), MCP_SCOPE)
        return try {
            if (!adopted) adopt(held)
            held.read().also { if (refusing != null) episode(null) }
        } catch (e: IOException) {
            episode(SafeFailureText.render(e))
            throw e
        }
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

    /** Key rotation (Oct 26, 2026): the management key was just replaced in this process, so the publication it bound
     *  is revoked now, not at the next start, and a fresh one is published under the new key. A source with no
     *  persisted state derives its bearer from the key and follows it with nothing to do. */
    public fun managementReplaced() {
        val held = publication ?: return
        synchronized(this) {
            adopted = false
            adopt(held)
        }
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
            val fenced = held.carryFence()
            if (held.present()) keepOrRevoke(held) else publishFirst(held, fenced)
            held.read()
            adopted = true
        }
    }

    /** No publication: the bootstrap bearer on a first run, a fresh incarnation when a rotation fenced the bootstrap. */
    private fun publishFirst(held: McpAccessPublication, fenced: Boolean) {
        if (!fenced) {
            val bootstrap = ScopedKey.derive(source(), MCP_SCOPE)
            held.create(bootstrap, bindingOf(bootstrap))
            return
        }
        val fresh = incarnation()
        held.publish(fresh, bindingOf(fresh))
        log("[mcp-access] the rotated access key was missing; published a fresh one\n")
    }

    /** A publication the current management key issued is kept, and bound if it predates bindings; any other is
     *  revoked and replaced, out loud. */
    private fun keepOrRevoke(held: McpAccessPublication) {
        val key = held.read()
        val bound = held.binding()
        if (bound == null && issuedUnderCurrent(key, held)) {
            held.bind(bindingOf(key))
            return
        }
        if (bound == bindingOf(key)) return
        val fresh = incarnation()
        held.publish(fresh, bindingOf(fresh))
        val why = if (bound == null) "predates the management key" else "came from another management key"
        log(
            "[mcp-access] the MCP access key $why and is revoked; published a fresh one. Sessions " +
                "holding the old key reach their hosted MCP servers again after a relaunch\n",
        )
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

    /** Says each change of [reason], null meaning the key reads again; a steady state says nothing. */
    private fun episode(reason: String?) {
        if (reason == refusing) return
        refusing = reason
        log(
            if (reason == null) {
                "[mcp-access] the MCP access key reads again; MCP bearers are accepted\n"
            } else {
                "[mcp-access] refusing every MCP bearer and hosting no MCP server: $reason\n"
            },
        )
    }

    private fun incarnation(): String {
        val nonce = ByteArray(MCP_NONCE_BYTES).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        return ScopedKey.derive(source(), MCP_ROTATED_SCOPE + nonce)
    }

    private fun bindingOf(key: String): String = ScopedKey.derive(source(), MCP_BINDING_SCOPE + key)
}
