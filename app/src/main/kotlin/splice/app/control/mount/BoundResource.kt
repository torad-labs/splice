// NEW: a resource the control server runs alongside its routes. It starts after the port binds and stops before the
// engine goes down. Only a mount that owns such a resource implements it; the MCP host is the one today.
package splice.app.control.mount

internal interface BoundResource {
    fun start()

    fun stop()
}
