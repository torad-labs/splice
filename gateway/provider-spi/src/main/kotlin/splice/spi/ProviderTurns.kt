// NEW: the per-turn request and liveness bag the Provider SPI threads.
// Split from Provider.kt so the interface file is not billed for the DTOs
// (concentration, 2026-08-19). Same-package FQCNs are unchanged.
package splice.spi

import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnMeta

/** The upstream request the provider built from an Anthropic body: wire JSON + per-turn meta +
 *  per-turn extra HTTP headers (e.g. grok's x-grok-conv-id — PER TURN, never provider-shared
 *  state: a shared field races concurrent sessions into each other's affinity headers). */
public data class BuiltTurn(
    val requestBody: JsonObject,
    val meta: TurnMeta,
    val extraHeaders: Map<String, String> = emptyMap(),
    /** The answering policy for THIS turn's deferred surface, built by the request builder (the
     *  only place that knows what was deferred). Null = no deferral this turn, or the feature is
     *  off — the gateway's round loop is byte-for-byte unchanged. */
    val toolSearch: ToolSearchController? = null,
)

/** Per-turn liveness signals the gateway hands the translator: the watchdog's typed sentinel and
 *  a REAL client-liveness probe (flipped when a downstream write fails — the head owns it; a
 *  provider must never hardcode it, that makes ClientAbandoned unreachable dead code). */
public data class TurnSignals(
    val watchdogFired: WatchdogProbe,
    val clientGone: ClientGone,
)
