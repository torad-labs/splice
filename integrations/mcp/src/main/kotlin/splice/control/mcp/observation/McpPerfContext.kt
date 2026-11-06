package splice.control.mcp.observation

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** An actual hosted tools/call; payloads and JSON-RPC IDs never enter observation context. */
internal class McpPerfContext(val invocation: McpPerfInvocation) : AbstractCoroutineContextElement(McpPerfContextKey)

internal object McpPerfContextKey : CoroutineContext.Key<McpPerfContext>
