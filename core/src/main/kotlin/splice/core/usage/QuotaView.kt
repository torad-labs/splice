// PORT-OF: daemon/control/src/main/kotlin/splice/control/ManagedHead.kt — the plan-window projection the
// control surfaces render. Shared vocabulary: the usage payloads and the statusline draw it, and the
// account pool answers its selected account's windows in it, so it lives below both capabilities.
package splice.core.usage

/** One plan window as a surface renders it. [resetsAt] and [observedAt] are both epoch SECONDS;
 *  [observedAt] is when the numbers were read (the provider's headers or its usage endpoint), null
 *  when the source names no observation — a window's age is how a reader tells a live bar from one
 *  the head last saw days ago. */
public data class QuotaWindowView(val usedPct: Int, val resetsAt: Long?, val observedAt: Long? = null)

public data class QuotaView(val fiveHour: QuotaWindowView?, val sevenDay: QuotaWindowView?, val plan: String?)
