// NEW: LAN share, phase 1 (docs/specs/lan-share.md; Marcos, 2026-10-22). `[share]` in splice.toml: this
// machine serves its heads to the person's other machines on a private network, through ONE listener
// the daemon opens only when `enabled`. Turns are relayed to the head itself, so every credential, its
// refresh, its account pool and its cooldowns stay on this machine. Absent table = off, today's bytes.
package splice.core.topology

import kotlinx.serialization.Serializable

@Serializable
public data class ShareConfig(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_SHARE_PORT,
    /** The heads offered, by topology key. Absent: every head that holds its own credential. */
    val heads: List<String>? = null,
    /** Names a client may reach this machine by besides its own host name (a Tailscale name, say). */
    val hostnames: List<String> = emptyList(),
) {
    /** The heads this machine offers. A client-auth head forwards the caller's own Claude login and holds
     *  nothing to share, so it is never offered, named or not. */
    public fun sharedHeads(topology: Topology): List<String> =
        (heads ?: topology.heads.keys.toList())
            .filter { key -> key in topology.heads && !forwardsCaller(topology, key) }

    /** What boot refuses about this table, all at once like every other finding. */
    internal fun findings(topology: Topology): List<TopologyFinding> {
        if (!enabled) return emptyList()
        val range = if (port in validPortRange) {
            emptyList()
        } else {
            listOf(TopologyFinding(SHARE_PORT_OWNER, "port $port is outside $validPortRange; use a port in that range"))
        }
        val named = heads.orEmpty().mapNotNull { key ->
            when {
                key !in topology.heads ->
                    TopologyFinding("share.heads", "'$key' is not a head in [heads]; name one that is")
                forwardsCaller(topology, key) -> TopologyFinding(
                    "share.heads",
                    "'$key' forwards the caller's own Claude login, so there is nothing to share; remove it",
                )
                else -> null
            }
        }
        return range + named
    }

    private fun forwardsCaller(topology: Topology, key: String): Boolean {
        val provider = topology.heads[key]?.provider?.let { topology.providers[it] } ?: return false
        return provider.auth.kind == AuthKind.Client.wire
    }
}

/** The share listener's port when `[share]` names none: between the control plane (3096) and the first port
 *  `splice add` gives a head (3098 and up). */
public const val DEFAULT_SHARE_PORT: Int = 3097

/** How the share listener appears in a port collision: the key path an operator fixes. */
internal const val SHARE_PORT_OWNER: String = "share.port"
