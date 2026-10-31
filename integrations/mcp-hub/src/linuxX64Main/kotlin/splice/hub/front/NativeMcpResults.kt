package splice.hub.front

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Writes the same schema-1 carrier the daemon reads. The native module deliberately has no JVM dependencies. */
class NativeMcpResults(files: HubFiles, private val clock: Clock) {
    private val directory = McpResultDirectory(files.toolResultsDir)
    private val report = files::say

    internal fun capture(identity: McpResultIdentity?, answer: JsonObject) {
        if (identity == null) return
        val outcome = McpResultOutcomes.fromReply(answer)
        try {
            directory.locked {
                if (outcome == null) {
                    invalidate(
                        identity.callbackId,
                    )
                } else {
                    publish(identity, outcome)
                }
            }
        } catch (_: IllegalStateException) {
            report("raw MCP result capture refused; no complete receipt was published")
        } catch (_: IllegalArgumentException) {
            report("raw MCP result capture refused; its retained record was malformed")
        } catch (_: CharacterCodingException) {
            report("raw MCP result capture refused; its retained bytes are invalid UTF-8")
        }
    }

    private fun invalidate(callbackId: String) {
        directory.prune(clock.epoch())
        val name = McpResultHash.filename(callbackId)
        if (directory.read(name) != null) directory.write(name, Rpc.encode(conflict(callbackId, clock.epoch())))
    }

    private fun publish(identity: McpResultIdentity, outcome: JsonObject) {
        val now = clock.epoch()
        directory.prune(now)
        val name = McpResultHash.filename(identity.callbackId)
        val record = buildJsonObject {
            put("schema", 1)
            identity.state().forEach { (key, value) -> put(key, value) }
            put("outcome", outcome)
            put(RESULT_CAPTURED_AT, now)
        }
        val previous = directory.read(name)?.let { JsonText.objectOrNull(it) ?: error("malformed MCP receipt") }
        check(previous?.get("conflict") != JsonPrimitive(true)) { "conflicting MCP captures" }
        if (previous == null) {
            directory.write(name, Rpc.encode(record))
        } else if (previous - RESULT_CAPTURED_AT != record - RESULT_CAPTURED_AT) {
            directory.write(name, Rpc.encode(conflict(identity.callbackId, now)))
            report("raw MCP result capture refused; the callback has conflicting captures")
        }
        directory.prune(now)
    }

    private fun conflict(callbackId: String, now: Long): JsonObject = buildJsonObject {
        put("schema", 1)
        put("callbackId", callbackId)
        put("conflict", true)
        put(RESULT_CAPTURED_AT, now)
    }
}

private const val RESULT_CAPTURED_AT = "capturedAtMs"
