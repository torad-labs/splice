// NEW: LAYOUT-01 — control-owned adapters from ManagedHead and the control audit into the narrow launch
// contract, so the launch feature serves /launch, the Claude head's wrap and the resume hook without
// importing ManagedHead or any other control-plane record.
package splice.app.control

import splice.app.control.api.ControlAudit
import splice.app.control.api.HeadResolver
import splice.launch.LaunchAudit
import splice.launch.LaunchHead
import splice.launch.LaunchHeads

internal object LaunchHeadAdapter {
    fun adapt(head: ManagedHead): LaunchHead = LaunchHead(
        head = head.head,
        auth = head.auth,
        spec = head.launchSpec,
        catalog = head.catalog,
        keyPresence = head.keyPresence,
    )

    /** Every lookup reads [heads] and [resolver] per call; `targets` is the resolver's own launchable-
     *  head precedence, so /launch resolves a name exactly as the rest of the control plane does. */
    fun heads(heads: Map<String, ManagedHead>, resolver: HeadResolver): LaunchHeads = object : LaunchHeads {
        override fun all(): List<LaunchHead> = heads.values.map(::adapt)

        override fun byKey(key: String): LaunchHead? = heads[key]?.let(::adapt)

        override fun targets(name: String): List<LaunchHead> = resolver.launchTargets(name).map(::adapt)
    }

    fun audit(audit: ControlAudit): LaunchAudit = object : LaunchAudit {
        override fun launched(key: String, argv: List<String>) = audit.launch(key, argv)

        override fun warned(message: String) = audit.warning(message)
    }
}
