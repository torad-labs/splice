// NEW: v0.4.0 — the ONE Host-header check both loopback listeners run (the control plane and every
// head), so neither can drift from the other. Binding 127.0.0.1 stops other MACHINES; it does not
// stop a web page in the operator's own browser. DNS rebinding points attacker.example at 127.0.0.1
// after the page loads, and the browser then treats splice's listeners as that page's own origin:
// it can read the unauthenticated routes (/health, the dashboard HTML) and drive any route a
// client-auth head leaves open. Every such request carries the attacker's name in Host, which is
// the one thing rebinding cannot change, so a listener that serves only loopback names closes it.
package splice.core.auth

// Lower-cased and port-free. `[::1]` is how an IPv6 literal appears in Host (RFC 3986 IP-literal).
private val LOOPBACK_NAMES = setOf("localhost", "127.0.0.1", "[::1]")

// RFC 9110 Host = uri-host [ ":" port ], anchored at BOTH ends: the name is a bracketed IP-literal
// or a colon-free reg-name, and nothing may follow but a port. PARSED, never prefix-matched: a
// prefix test serves `localhost.attacker.example`, a name any rebinding page can register.
private val HOST = Regex("^(\\[[^\\]]*\\]|[^:\\[\\]]*)(?::\\d*)?$")

public object LoopbackHost {

    /** The one refusal text both listeners send, each in its own wire shape. */
    public const val FOREIGN_HOST_REFUSAL: String = "splice serves loopback names only (127.0.0.1, localhost, [::1])"

    /**
     * True when a request's Host header names this machine's loopback, on any port (an SSH tunnel
     * may forward a different local port; rebinding never changes the NAME). An ABSENT Host is
     * admitted: every browser sends one, so its absence marks a local non-browser client — the only
     * callers rebinding cannot produce.
     */
    public fun admits(host: String?): Boolean {
        val value = host?.trim()?.lowercase() ?: return true
        return HOST.matchEntire(value)?.groupValues?.get(1) in LOOPBACK_NAMES
    }
}
