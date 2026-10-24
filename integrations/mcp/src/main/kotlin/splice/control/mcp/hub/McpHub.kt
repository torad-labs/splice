package splice.control.mcp.hub

import kotlinx.coroutines.CoroutineDispatcher
import splice.client.mcp.McpSharing
import splice.control.mcp.JsonRpcCodec
import splice.control.mcp.McpHandshakeBudget
import splice.core.util.LogSink

/** The daemon-facing MCP hub composes focused target, request, replacement, and holder owners. */
public class McpHub(
    sharing: McpSharing,
    servers: McpLaunchServers,
    places: McpLaunchPlaces,
    paths: McpHubPaths,
    startBound: McpHandshakeBudget,
    io: CoroutineDispatcher,
    log: LogSink,
) : McpHubCalls by HubAssembly(sharing, servers, places, paths, startBound, io, log).calls

private class HubAssembly(
    sharing: McpSharing,
    servers: McpLaunchServers,
    places: McpLaunchPlaces,
    paths: McpHubPaths,
    startBound: McpHandshakeBudget,
    io: CoroutineDispatcher,
    log: LogSink,
) {
    private val codec = JsonRpcCodec()
    private val tuple = HubTuple(paths.home)
    private val holders = HubHolders(paths.stateDir.resolve(HubStorage.DIRECTORY), paths.holder, paths.slice, io, log)
    private val listings = HubListings(paths.stateDir.resolve(HubStorage.LISTINGS), log)
    private val front = HubFront(paths.stateDir.resolve(HubStorage.DIRECTORY).resolve(HubStorage.FRONT_STATE))
    private val targets = HubTargets(sharing, servers, places, tuple, holders, listings)
    private val sweep = HubSweep(sharing, servers, places, tuple, holders, front)
    private val requests = HubRequests(targets, holders, listings, sharing, startBound, log, sweep)
    private val replacements = McpHubReplacements(codec, targets, tuple, holders, front, startBound)
    private val pins = McpHubDrainPins(codec, targets, holders, front)
    val calls: McpHubCalls = HubMcpCalls(
        HubRequestDelegates(requests),
        HubReplacementDelegates(replacements, pins),
        HubMaintenanceDelegates(requests, front),
    )
}

private class HubMcpCalls(
    requests: HubRequestCalls,
    replacements: HubReplacementCalls,
    maintenance: HubMaintenanceCalls,
) :
    McpHubCalls,
    HubRequestCalls by requests,
    HubReplacementCalls by replacements,
    HubMaintenanceCalls by maintenance

private class HubRequestDelegates(private val requests: HubRequests) : HubRequestCalls {
    override fun resolve(body: String): McpHubReply = requests.resolve(body)

    override fun attest(body: String): McpHubReply = requests.attest(body)

    override suspend fun start(body: String): McpHubReply = requests.start(body)

    override fun listing(body: String): McpHubReply = requests.listing(body)

    override fun roots(body: String): McpHubReply = requests.roots(body)
}

private class HubReplacementDelegates(
    private val replacements: McpHubReplacements,
    private val pins: McpHubDrainPins,
) : HubReplacementCalls {
    override suspend fun replacementStart(body: String): McpHubReply = replacements.start(body)

    override fun replacementCommit(body: String): McpHubReply = replacements.commit(body)

    override fun replacementRetire(body: String): McpHubReply = replacements.retire(body)

    override fun replacementRollback(body: String): McpHubReply = replacements.rollback(body)

    override fun replacementExit(body: String): McpHubReply = replacements.exit(body)

    override fun replacementPinAcquire(body: String): McpHubReply = pins.acquire(body)

    override fun replacementPinConfirm(body: String): McpHubReply = pins.confirm(body)

    override fun replacementPinRelease(body: String): McpHubReply = pins.release(body)
}

private class HubMaintenanceDelegates(
    private val requests: HubRequests,
    private val front: HubFront,
) : HubMaintenanceCalls {
    override fun sweep(now: Long, idleMs: Long) = requests.sweep(now, idleMs)

    override fun attached(): Map<String, Set<String>> = requests.attached(front)
}
