package splice.control.mcp.hub

import splice.control.mcp.APP_MCP_SLICE
import java.nio.file.Path

internal object HubStorage {
    const val DIRECTORY = "mcp-hub"
    const val FRONT_STATE = "front.json"
    const val LISTINGS = "mcp-listings"
}

internal object HubResponses {
    const val OK = 200

    // why: mutating hub control operations return no body after their durable state transition completes.
    const val NO_CONTENT = 204
}

public data class McpHubReply(val status: Int, val body: String?)

public data class McpHubPaths(
    val stateDir: Path,
    val holder: Path,
    val home: Path,
    val slice: String = APP_MCP_SLICE,
)
