// MCP hub calls stay available when the machine falls back to in-process hosting.
package splice.control.mcp.hub

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.mcp.McpServerSpec
import splice.core.util.JsonWire
import splice.core.wire.HttpStatus

public interface HubRequestCalls {
    /** `POST /api/mcp/hub/resolve`. */
    public fun resolve(body: String): McpHubReply

    /** `POST /api/mcp/hub/attest`: returns the live owner generation for exactly the named retained socket. */
    public fun attest(body: String): McpHubReply

    /** `POST /api/mcp/hub/start`. */
    public suspend fun start(body: String): McpHubReply

    /** `POST /api/mcp/hub/listing`. */
    public fun listing(body: String): McpHubReply

    /** `POST /api/mcp/hub/roots`: a shared server asked its client for roots. */
    public fun roots(body: String): McpHubReply
}

public interface HubReplacementCalls {
    /** `POST /api/mcp/hub/replacement/start`: starts the current configured tuple beside a retained holder. */
    public suspend fun replacementStart(body: String): McpHubReply

    /** `POST /api/mcp/hub/replacement/commit`: revalidates every affected canonical alias and launch before cutover. */
    public fun replacementCommit(body: String): McpHubReply

    /** `POST /api/mcp/hub/replacement/retire`: the daemon owner retires the exact old holder after front settlement. */
    public fun replacementRetire(body: String): McpHubReply

    /** `POST /api/mcp/hub/replacement/rollback`: restores an owner-confirmed live old generation before retirement began. */
    public fun replacementRollback(body: String): McpHubReply

    /** `POST /api/mcp/hub/replacement/exit`: confirms exactly one holder generation exited before relay ids settle. */
    public fun replacementExit(body: String): McpHubReply

    /** `POST /api/mcp/hub/replacement/pin/acquire`: durably excludes an exact old generation before front checkpoint. */
    public fun replacementPinAcquire(body: String): McpHubReply

    /** `POST /api/mcp/hub/replacement/pin/confirm`: binds a persisted front drain to its exact owner claim. */
    public fun replacementPinConfirm(body: String): McpHubReply

    /** `POST /api/mcp/hub/replacement/pin/release`: idempotently settles an exact persisted owner claim. */
    public fun replacementPinRelease(body: String): McpHubReply
}

public interface HubMaintenanceCalls {
    /** The host's idle sweep, at [now], with the MCP idle limit [idleMs]. */
    public fun sweep(now: Long, idleMs: Long)

    /** Each hub-served server, by the name its holder's record writes, to the launch ids of front-bound sessions. */
    public fun attached(): Map<String, Set<String>>

    /** Observes complete retained listings only for the caller's current explicitly shared declarations. */
    public fun sharedTools(specs: Map<String, McpServerSpec>): Map<String, JsonObject>
}

public interface McpHubCalls : HubRequestCalls, HubReplacementCalls, HubMaintenanceCalls

/** The hub on a machine it cannot run on: the daemon keeps hosting MCP in process. */
public class McpHubOff(reason: String) : McpHubCalls by HubOffCalls(reason)

private class HubOffCalls(reason: String) :
    McpHubCalls,
    HubRequestCalls by HubOffRequests(reason),
    HubReplacementCalls by HubOffReplacements(reason),
    HubMaintenanceCalls by HubOffMaintenance

private class HubOffReply(reason: String) {
    val reply = McpHubReply(
        HttpStatus.SERVICE_UNAVAILABLE,
        JsonWire.string(buildJsonObject { put("error", "the MCP hub is off on this machine: $reason") }),
    )
}

private class HubOffRequests(reason: String) : HubRequestCalls {
    private val unavailable = HubOffReply(reason).reply

    override fun resolve(body: String): McpHubReply = unavailable

    override fun attest(body: String): McpHubReply = unavailable

    override suspend fun start(body: String): McpHubReply = unavailable

    override fun listing(body: String): McpHubReply = unavailable

    override fun roots(body: String): McpHubReply = unavailable
}

private class HubOffReplacements(reason: String) : HubReplacementCalls {
    private val unavailable = HubOffReply(reason).reply

    override suspend fun replacementStart(body: String): McpHubReply = unavailable

    override fun replacementCommit(body: String): McpHubReply = unavailable

    override fun replacementRetire(body: String): McpHubReply = unavailable

    override fun replacementRollback(body: String): McpHubReply = unavailable

    override fun replacementExit(body: String): McpHubReply = unavailable

    override fun replacementPinAcquire(body: String): McpHubReply = unavailable

    override fun replacementPinConfirm(body: String): McpHubReply = unavailable

    override fun replacementPinRelease(body: String): McpHubReply = unavailable
}

private object HubOffMaintenance : HubMaintenanceCalls {
    override fun sweep(now: Long, idleMs: Long) {
        // Nothing to stop: with the hub off, no holder was started.
    }

    override fun attached(): Map<String, Set<String>> = emptyMap()

    override fun sharedTools(specs: Map<String, McpServerSpec>): Map<String, JsonObject> = emptyMap()
}
