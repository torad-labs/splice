package splice.app.diagnostics

import splice.app.control.ControlListen
import splice.app.control.ControlReadings
import splice.app.control.ControlServer
import splice.app.control.FailedHeads
import splice.app.control.HeadSources
import splice.app.control.ManagedHead
import splice.app.control.RuntimeNotAnswering
import splice.app.control.TopologyDigest
import splice.app.control.TurnPathStalled
import splice.app.control.UsageWarning
import splice.app.control.UsageWarningSource
import splice.app.control.api.ControlAudit
import splice.app.control.api.ControlHealthReport
import splice.app.control.api.HeadReadiness
import splice.app.control.api.HeadSignals
import splice.app.control.mount.ControlGuard
import splice.configuration.topology.TopologyStale
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.failure.HeadVoice
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.version.ClientVersionTracker
import splice.diagnostics.logs.HeadLogSource
import splice.head.admission.AdmissionErrorBody
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.http.ingress.HeapIngress
import splice.upstream.memory.JvmHeap
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView

/** Isolates a model-free diagnostic's live bridge while keeping the production control listener and its guard. */
internal object CodeModeProbeControlServer {
    fun build(config: ConfigService, management: MgmtKey, head: String): ControlServer {
        val heads = mapOf(head to managed(head))
        val signals = HeadSignals(heads, RuntimeNotAnswering { emptyMap() })
        val health = ControlHealthReport(
            HeadReadiness(heads, FailedHeads { 0 }, heads.size, TurnPathStalled { emptyList() }),
            signals,
            TopologyDigest { "" },
            TopologyStale { false },
            ClientVersionTracker(),
        )
        return ControlServer(
            ControlListen(0),
            heads,
            config,
            guard(config, management),
            {},
            ControlReadings(health, signals),
        )
    }

    private fun guard(config: ConfigService, management: MgmtKey): ControlGuard = ControlGuard(
        management,
        ControlAudit {},
        {},
        HeapIngress(
            JvmHeap.budget,
            { config.getConfig().maxRequestBytes },
            AdmissionErrorBody(HeadVoice("splice", loginCommand = null)),
            readIdleMs = { config.getConfig().ingressReadIdleMs },
        ),
    )

    private fun managed(headKey: String): ManagedHead = ManagedHead(
        object : Head {
            override val key: String = headKey
            override val label: String = headKey
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, port, "isolated-code-mode-probe")
        },
        object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe(): AuthDescription = AuthDescription(false, "isolated-code-mode-probe")
        },
        HeadSources(
            HeadUsageSource { UsageView(0, 0, RateLimitView(null, null, null)) },
            object : HeadCompactSource {
                override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
            },
            object : HeadLogSource {
                override fun tail(lines: Int): String = ""
                override fun path(): String = ""
            },
        ),
        UsageWarningSource { UsageWarning(0, 0) },
    )
}
