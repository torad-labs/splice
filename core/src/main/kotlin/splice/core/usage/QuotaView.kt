// PORT-OF: daemon/control/src/main/kotlin/splice/control/ManagedHead.kt — the plan-window projection the
// control surfaces render. Shared vocabulary: the usage payloads and the statusline draw it, and the
// account pool answers its selected account's windows in it, so it lives below both capabilities.
package splice.core.usage

public data class QuotaWindowView(val usedPct: Int, val resetsAt: Long?)

public data class QuotaView(val fiveHour: QuotaWindowView?, val sevenDay: QuotaWindowView?, val plan: String?)
