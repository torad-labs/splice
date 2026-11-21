package splice.core.head

/** The lifetime of a turn route, whether it has a listener or uses the gateway's. */
public interface TurnRouteLifetime {
    /** Stop admitting new turns and let active turns settle before their listener is closed. */
    public suspend fun drain() {}

    /** Stop admitting turns, settle their replies, and close the route. */
    public suspend fun stop()

    /** Close admission and hand over the books; return the already-started replies this daemon will finish. */
    public suspend fun retire(): Int {
        stop()
        return 0
    }

    /** Give the retained replies up to [withinMs] to end, then close the route. */
    public suspend fun finish(withinMs: Long) {
        stop()
    }
}
