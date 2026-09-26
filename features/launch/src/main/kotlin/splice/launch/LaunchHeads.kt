// NEW: LAYOUT-01 — the head facts the launch surfaces consume: the /launch recipe, the Claude head's
// wrap, and the resume hook. The control plane adapts its wider ManagedHead into this projection, so
// the launch feature never depends upward on the control plane.
package splice.launch

import splice.core.auth.AuthProvider
import splice.core.head.Head
import splice.core.model.ModelCatalog

/** One head as the launch surfaces see it. [head] is the live lifecycle handle: a launch reads its
 *  health at request time, and the audit and ambiguity messages name its key and label. */
public data class LaunchHead(
    val head: Head,
    val auth: AuthProvider,
    /** Present when this head can be launched as a Claude Code wrapper (P4-LAUNCH). */
    val spec: LaunchSpec?,
    /** The head's live model catalog: a launch plants the windows splice.toml declares NOW (V4-162). */
    val catalog: ModelCatalog? = null,
    val keyPresence: KeyPresenceProbe = KeyPresenceProbe { true },
)

/** The launch-time key-presence read (DR-81): "does this head hold a working api key RIGHT NOW",
 *  read per /launch and never frozen into [LaunchSpec]. */
public fun interface KeyPresenceProbe {
    public fun keyPresentNow(): Boolean
}

/** The heads the launch routes resolve, read at CALL time. Resolution stays the control plane's, so
 *  every capability resolves a name the same way. */
public interface LaunchHeads {
    /** Every configured head, in topology order: the "configured:" list a failed launch names. */
    public fun all(): List<LaunchHead>

    /** The head with exactly this topology key. The hook scripts and wrap name a head by key, never by
     *  its wrapper label, so this lookup is exact and never ambiguous. */
    public fun byKey(key: String): LaunchHead?

    /** The launchable heads a `/launch/<name>` resolves to: a launchable KEY match wins outright,
     *  otherwise every launchable wrapper-command match. The caller decides what none and several mean. */
    public fun targets(name: String): List<LaunchHead>
}

/** The two `[control]` audit lines a launch writes: the argv it answered, and any warning it carried. */
public interface LaunchAudit {
    public fun launched(key: String, argv: List<String>)

    public fun warned(message: String)
}
