// MCP lifecycle request decoding, selective regeneration and replies belong to the control API.
// McpMount retains registration, management authentication and the host's bound-resource lifecycle.
package splice.app.control.api.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.app.MCP_HUB_FRONT_PORT
import splice.client.mcp.McpAccessKey
import splice.client.mcp.McpHeaderRegeneration
import splice.client.mcp.McpHeaderUpdate
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Path

/** Secrets only arrive in a management-authenticated request body, never argv or a response.
 *  Not a data class: request diagnostics must not stringify a previous bearer. */
@Serializable
private class McpAccessRequest(
    @SerialName("header_files") val headerFiles: List<String> = emptyList(),
    @SerialName("previous_key") val previousKey: String? = null,
) {
    fun revokedBearer(): String? {
        val bearer = previousKey ?: return null
        if (bearer.length != KEY_LENGTH) return null
        return bearer.takeIf { it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
    }

    override fun toString(): String = "MCP access lifecycle request (credentials redacted)"
}

/** Management-only MCP lifecycle. Publication is the revocation point. A later header failure is
 *  reported with rotated=true and never rolls the key back. No restart or native-front signal is sent. */
internal class McpAccessRoutes(private val key: McpAccessKey) {
    private val mutation = Mutex()

    suspend fun rotate(call: ApplicationCall) {
        val request = request(call) ?: return
        if (request.previousKey != null) return refuse(call, "rotation does not accept previous_key")
        mutation.withLock {
            var rotated = false
            try {
                val previous = key()
                val updates = prepare(call, request, previous)
                val replacement = key.rotate(previous)
                rotated = true
                publish(call, updates, replacement, rotated)
            } catch (e: IOException) {
                failure(call, rotated, "MCP publication or header preflight failed: ${SafeFailureText.render(e)}")
            } catch (_: IllegalArgumentException) {
                failure(call, rotated, "MCP header preflight requires valid JSON and file paths")
            } catch (_: UnsupportedOperationException) {
                failure(call, rotated, "MCP state requires owner-only filesystem permissions")
            }
        }
    }

    /** Retries selective regeneration after a partial rotation, without rotating again. The revoked
     *  bearer is a match operand only; it never authenticates this route or any later MCP call. */
    suspend fun headers(call: ApplicationCall) {
        val request = request(call) ?: return
        val previous = request.revokedBearer()
            ?: return refuse(call, "header regeneration requires previous_key")
        mutation.withLock {
            try {
                val replacement = key()
                if (previous == replacement) return@withLock refuse(call, "previous_key is still active")
                publish(call, prepare(call, request, previous), replacement, rotated = false)
            } catch (e: IOException) {
                failure(call, false, "MCP publication or header preflight failed: ${SafeFailureText.render(e)}")
            } catch (_: IllegalArgumentException) {
                failure(call, false, "MCP header preflight requires valid JSON and file paths")
            } catch (_: UnsupportedOperationException) {
                failure(call, false, "MCP state requires owner-only filesystem permissions")
            }
        }
    }

    private suspend fun request(call: ApplicationCall): McpAccessRequest? = try {
        Json.decodeFromString<McpAccessRequest>(call.receiveText().ifBlank { "{}" })
    } catch (_: IllegalArgumentException) {
        refuse(call, "expected a JSON object with header_files and optional previous_key")
        null
    }

    private fun prepare(call: ApplicationCall, request: McpAccessRequest, previous: String): List<McpHeaderUpdate> {
        val regeneration = McpHeaderRegeneration(
            previous,
            setOf(
                "http://127.0.0.1:${call.request.local.localPort}/mcp/",
                "http://127.0.0.1:$MCP_HUB_FRONT_PORT/mcp/",
            ),
            key.publicationFile().parent.resolve("header-backups"),
        )
        val files = request.headerFiles.map { Path.of(it).toAbsolutePath().normalize() }.distinct()
        return files.map(regeneration::prepare)
    }

    private suspend fun publish(
        call: ApplicationCall,
        updates: List<McpHeaderUpdate>,
        replacement: String,
        rotated: Boolean,
    ) {
        var failed = false
        val files = updates.map { update ->
            val error = try {
                update.publish(replacement)
                null
            } catch (e: IOException) {
                "header publication failed (${SafeFailureText.render(e)}); retry regeneration, not rotation"
            } catch (_: UnsupportedOperationException) {
                "header publication requires owner-only filesystem permissions"
            }
            if (error != null) failed = true
            buildJsonObject {
                put("file", update.file.toString())
                put("matched_headers", update.matchedHeaders)
                put("updated", error == null && update.matchedHeaders > 0)
                update.backupFile?.let { put("backup_file", it.toString()) }
                error?.let { put("error", it) }
            }
        }
        val body = buildJsonObject {
            put("rotated", rotated)
            put("access_key_file", key.publicationFile().toString())
            putJsonArray("files") { files.forEach { add(it) } }
        }
        reply(call, body, if (failed) HttpStatusCode.InternalServerError else HttpStatusCode.OK)
    }

    private suspend fun refuse(call: ApplicationCall, message: String) =
        reply(call, buildJsonObject { put("error", message) }, HttpStatusCode.BadRequest)

    private suspend fun failure(call: ApplicationCall, rotated: Boolean, message: String) {
        val body = buildJsonObject {
            put("rotated", rotated)
            put("error", message)
        }
        reply(call, body, HttpStatusCode.InternalServerError)
    }

    private suspend fun reply(call: ApplicationCall, body: JsonObject, status: HttpStatusCode) {
        call.response.headers.append("Cache-Control", "no-store")
        call.respondText(body.toString(), ContentType.Application.Json, status)
    }
}

// why: a revoked HMAC-SHA256 bearer is 32 bytes encoded as 64 lowercase hex characters, never arbitrary text.
private const val KEY_LENGTH = 64
