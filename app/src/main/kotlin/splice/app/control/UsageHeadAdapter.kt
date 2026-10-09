// NEW: LAYOUT-01 — control-owned adapters from ManagedHead into the usage feature's projection, so the
// usage routes read their head facts without importing ManagedHead or any other control-plane record.
package splice.app.control

import splice.accounts.claude.ClaudeLoginPlacesSource
import splice.app.control.api.HeadResolver
import splice.core.auth.CLIENT_AUTH_KIND
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.models.roster.DeclaredHeads
import splice.usage.UsageBilling
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeadSinks
import splice.usage.UsageHeadStatusline
import splice.usage.UsageHeadWarn
import splice.usage.UsageHeads

internal object UsageHeadAdapter {
    /** Adapted per call, never captured: a head's label follows a runtime rename (DR-22a). */
    fun heads(
        heads: Map<String, ManagedHead>,
        native: ClaudeLoginPlacesSource = ClaudeLoginPlacesSource { null },
    ): UsageHeads = object : UsageHeads {
        override fun all(): List<UsageHead> = heads.values.map { head ->
            val adapted = adapt(head)
            if (head.authSurface.authKind == CLIENT_AUTH_KIND) {
                adapted.copy(usage = NativeUsageSource(head, native))
            } else {
                adapted
            }
        }

        override fun providerResetForMs(key: String): Long = heads[key]?.head?.providerResetForMs() ?: 0L
    }

    /** The shared by-name lookup (key first, then every wrapper-command match). */
    fun lookup(
        resolver: HeadResolver,
        declared: DeclaredHeads = DeclaredHeads { emptyMap() },
    ): UsageHeadLookup = object : UsageHeadLookup {
        override fun byName(name: String): List<UsageHead> = resolver.headByName(name).map(::adapt)

        override fun billing(key: String): UsageBilling? {
            val head = resolver.headByName(key).firstOrNull { it.head.key == key } ?: return null
            val auth = AuthKindRegistry.from(head.authSurface.authKind)
            return when {
                declared()[key]?.family == "local" -> UsageBilling.LOCAL_RUNTIME
                auth?.isOAuth == true || auth == AuthKind.Client -> UsageBilling.SUBSCRIPTION
                else -> UsageBilling.API_RATE
            }
        }
    }

    private fun adapt(head: ManagedHead): UsageHead = UsageHead(
        key = head.head.key,
        label = head.head.label,
        usage = head.sources.usage,
        warn = UsageHeadWarn(
            warnPct = head.usageWarning.warnPct,
            warnTokens5h = head.usageWarning.warnTokens5h,
        ),
        sinks = UsageHeadSinks(
            perf = head.sources.perf,
            perfRows = head.sources.perfRows?.let {
                if (head.authSurface.authKind == CLIENT_AUTH_KIND) NativeAccountRows(it) else it
            },
            economics = head.sources.economics,
            accountPool = head.authSurface.accountPool,
        ),
        statusline = UsageHeadStatusline(
            catalog = head.statusline.catalog,
            clientWindows = head.statusline.clientWindows,
        ),
        anthropicUpstream = head.authSurface.authKind == CLIENT_AUTH_KIND,
    )
}
