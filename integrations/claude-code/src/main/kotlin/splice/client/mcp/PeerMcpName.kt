// The name splice's peer server goes by in every client it is given to, and in the one word that hands it to a launch
// of Claude Code: a relaunch knows that word as splice's (LaunchRecipeArgs), so it is named here, beside the launch's
// other readers, rather than in the peer code alone.
package splice.client.mcp

/** The MCP server a session reaches its peers through: `mcp__splice-peers__*` in Claude Code. */
public const val PEER_MCP_SERVER: String = "splice-peers"
