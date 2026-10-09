// NEW: a small parameter object so ManagedHeadFactory.assembleHead and HeadServerFactory can share
// a head's three file-backed stores without pushing either factory over detekt's LongParameterList
// ceiling. A data class, so detekt's LongParameterList exempts it (campaign claude-head decomposition).
package splice.app.head

import splice.core.model.ClientWindows
import splice.head.usage.QuotaTracker
import splice.head.usage.UsageStore
import splice.head.wire.TraceStore
import splice.upstream.retry.ProviderHoldStore

internal data class HeadStores(
    val usageStore: UsageStore,
    val telemetry: HeadTelemetryStores,
    val quota: QuotaTracker,
    val accounts: HeadAccountStores = HeadAccountStores(),
    /** Per-session client windows (ClientWindows): written by the control plane's statusline
     *  route, read by the head's usage payload — one instance so both see the same sessions. */
    val clientWindows: ClientWindows = ClientWindows(),
    /** V4-174: the head's opt-in full trace; null is off. No default (the V4-105 law on the gateway
     *  twin): the one construction site decides from the head's own config, never by omission. */
    val trace: TraceStore?,
    /** V4-412: the head's provider hold on disk, so status and usage still read out of quota after a
     *  restart. Null keeps it in memory only. */
    val providerHold: ProviderHoldStore? = null,
)
