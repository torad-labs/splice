// PORT-OF: daemon/control/.../ControlPorts.kt (ShutdownDaemon) — invariants unchanged: the lifecycle
// request both the shutdown and the draining-restart routes end in, moved beside the restart route.
package splice.lifecycle.restart

/**
 * Asks the daemon to begin an orderly shutdown — what `POST /mgmt/shutdown` actually does.
 *
 * A REQUEST and not the shutdown itself: production completes a signal the main coroutine is
 * waiting on, so the daemon tears itself down on its own thread, in its own order, while this
 * handler is still free to write the response. A default no-op means an embedded control server
 * (tests, the dashboard-only path) simply cannot be told to exit.
 */
public fun interface ShutdownDaemon {
    public operator fun invoke()
}
