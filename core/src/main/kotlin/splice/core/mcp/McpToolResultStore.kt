package splice.core.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import splice.core.config.StatePaths
import splice.core.util.Cancellables
import splice.core.util.WallClock
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Private cross-process receipts preserve raw MCP values; only an exact owned callback identity may read one. */
public class McpToolResultStore(
    paths: StatePaths,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val limits: Limits = Limits(),
) {
    public data class Limits(
        val maxItems: Int = 2_000,
        val maxTotalBytes: Long = MCP_RESULT_DEFAULT_TOTAL_BYTES,
        // A native call may carry a 64 MiB result and independent 64 MiB arguments, plus bounded identity fields.
        val maxEntryBytes: Long = MCP_RESULT_DEFAULT_ENTRY_BYTES,
        val maxAgeMs: Long = TimeUnit.DAYS.toMillis(1),
    )

    @Serializable
    public data class Receipt(
        val schema: Int,
        val callbackId: String,
        val server: String,
        val tool: String,
        val arguments: JsonObject,
        val outcome: Outcome,
        val capturedAtMs: Long,
    )

    @Serializable
    public sealed class Outcome {
        @Serializable
        @SerialName("RawResult")
        public data class RawResult(val value: JsonObject) : Outcome()

        @Serializable
        @SerialName("RpcError")
        public data class RpcError(val error: JsonObject) : Outcome()
    }

    public sealed class Read {
        public data class Found(val receipt: Receipt) : Read()
        public data object Missing : Read()
        public data class Refused(val reason: String) : Read()
    }

    public sealed class Capture {
        public data object Stored : Capture()
        public data class Refused(val reason: String) : Capture()
    }

    private val files = McpToolResultFiles(paths.mcpToolResultsDir, limits)

    init {
        require(limits.maxItems > 0 && limits.maxAgeMs > 0)
        require(limits.maxEntryBytes in 1..limits.maxTotalBytes)
        require(limits.maxEntryBytes < Int.MAX_VALUE)
    }

    /** Publishes a whole result atomically before its existing transport reply is returned to the client. */
    public fun capture(
        callbackId: String,
        server: String,
        tool: String,
        arguments: JsonObject,
        result: JsonObject,
    ): Capture = captureOutcome(callbackId, server, tool, arguments, Outcome.RawResult(result))

    /** Retains a real JSON-RPC error packet separately from raw CallToolResult values and generic client text. */
    public fun captureRpcError(
        callbackId: String,
        server: String,
        tool: String,
        arguments: JsonObject,
        error: JsonObject,
    ): Capture = captureOutcome(callbackId, server, tool, arguments, Outcome.RpcError(error))

    private fun captureOutcome(
        callbackId: String,
        server: String,
        tool: String,
        arguments: JsonObject,
        outcome: Outcome,
    ): Capture {
        val invalid = invalid(callbackId, server, tool, outcome)
        if (invalid != null) return refused(callbackId, invalid)
        val receipt = Receipt(1, callbackId, server, tool, arguments, outcome, clock())
        val bytes = Json.encodeToString(receipt).toByteArray(Charsets.UTF_8)
        if (bytes.size > limits.maxEntryBytes) {
            return refused(
                callbackId,
                "raw MCP result exceeds the complete-entry byte bound",
            )
        }
        return Cancellables.runCatchingCancellable {
            files.withLock {
                files.prune(clock())
                val path = files.path(callbackId)
                val previous = load(path)
                if (previous != null && previous.copy(capturedAtMs = receipt.capturedAtMs) != receipt) {
                    files.publish(path, Json.encodeToString(Conflict(1, callbackId, true, clock())), clock())
                    Capture.Refused("the callback already has a different raw MCP result")
                } else {
                    if (previous == null) files.publish(path, bytes.toString(Charsets.UTF_8), receipt.capturedAtMs)
                    files.prune(clock())
                    Capture.Stored
                }
            }
        }.getOrElse { Capture.Refused(failure(it)) }
    }

    /** A transport failure or malformed reply must not let an earlier result prove this reused callback. */
    public fun invalidate(callbackId: String): Capture = refused(callbackId, "MCP call returned no complete raw result")

    private fun refused(callbackId: String, reason: String): Capture {
        if (!McpToolResultIdentity.ownedId(callbackId)) return Capture.Refused(reason)
        return Cancellables.runCatchingCancellable {
            files.withLock {
                files.prune(clock())
                val path = files.path(callbackId)
                if (files.present(path)) {
                    files.publish(path, Json.encodeToString(Conflict(1, callbackId, true, clock())), clock())
                }
                Capture.Refused(reason)
            }
        }.getOrElse { Capture.Refused(failure(it)) }
    }

    /** Missing and expired receipts are explicit absence; malformed or mismatched receipts never become text. */
    public fun read(callbackId: String, expectedArguments: JsonObject): Read {
        if (!McpToolResultIdentity.ownedId(callbackId)) return Read.Refused("not an owned code-mode callback id")
        return Cancellables.runCatchingCancellable {
            files.withLock { matched(callbackId, expectedArguments) }
        }.getOrElse { Read.Refused(failure(it)) }
    }

    private fun matched(callbackId: String, expectedArguments: JsonObject): Read {
        val path = files.path(callbackId)
        val receipt = load(path) ?: return Read.Missing
        return when {
            receipt.callbackId != callbackId -> Read.Refused("the raw MCP result belongs to another callback")
            receipt.arguments != expectedArguments -> Read.Refused("the raw MCP arguments differ")
            clock() - receipt.capturedAtMs >= limits.maxAgeMs -> {
                Files.deleteIfExists(path)
                Read.Missing
            }
            invalid(receipt.callbackId, receipt.server, receipt.tool, receipt.outcome) != null ->
                Read.Refused("the raw MCP result is malformed")
            else -> Read.Found(receipt)
        }
    }

    private fun load(path: Path): Receipt? {
        val bytes = files.bytes(path) ?: return null
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        val fields = Json.parseToJsonElement(text) as? JsonObject
        require(fields?.get("conflict") != kotlinx.serialization.json.JsonPrimitive(true)) {
            "the callback has conflicting raw MCP captures"
        }
        val record = Json.decodeFromString<Receipt>(text)
        require(record.schema == 1) { "unsupported raw MCP result schema" }
        return record
    }

    private fun invalid(callbackId: String, server: String, tool: String, outcome: Outcome): String? = when {
        !McpToolResultIdentity.ownedId(callbackId) -> "not an owned code-mode callback id"
        server.isBlank() || tool.isBlank() -> "MCP result requires its actual server and tool"
        else -> invalidOutcome(outcome)
    }

    private fun invalidOutcome(outcome: Outcome): String? = when (outcome) {
        is Outcome.RawResult -> {
            if (outcome.value["content"] is JsonArray) null else "raw MCP result requires its complete content array"
        }
        is Outcome.RpcError -> {
            if (validRpcError(outcome.error)) null else "raw MCP RPC error requires its complete typed code and message"
        }
    }

    private fun validRpcError(error: JsonObject): Boolean {
        val code = error["code"] as? JsonPrimitive
        val message = error["message"] as? JsonPrimitive
        return when {
            code == null || code.isString -> false
            code.intOrNull == null -> false
            else -> message?.isString == true
        }
    }

    // Serialization exceptions can include private JSON excerpts. Only their fixed class name may cross this port.
    private fun failure(error: Throwable): String = "raw MCP receipt unavailable (${failureKind(error)})"

    private fun failureKind(error: Throwable): String = when (error) {
        is SerializationException -> "serialization"
        is CharacterCodingException -> "encoding"
        is IOException -> "I/O"
        is IllegalArgumentException -> "invalid-fields"
        is IllegalStateException -> "invalid-state"
        else -> "storage"
    }

    @Serializable
    private data class Conflict(val schema: Int, val callbackId: String, val conflict: Boolean, val capturedAtMs: Long)
}

// why: a bounded 256 MiB retained-receipt store evicts old complete results before admitting new ones.
private const val MCP_RESULT_DEFAULT_TOTAL_BYTES = 256L * 1024 * 1024

// why: one receipt can retain both 64 MiB wire arguments and result, plus 64 KiB of identity/envelope overhead.
private const val MCP_RESULT_DEFAULT_ENTRY_BYTES = 128L * 1024 * 1024 + 65_536
