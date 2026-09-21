// NEW: V4-122 — the ONE width an upstream error is quoted at, in core so every module that renders
// a failure can reach it.
//
// WHY IT MOVED HERE: three files declared `ERR_SNIPPET` — WsLogKeys.kt (160) in the responses
// dialect, UpstreamClient.kt (160) in provider-spi, and TurnTelemetry.kt (200) in the gateway — and
// the checker's NAMED_SCARS list held the name as a scar precisely because two widths for one
// meaning is a defect, not a preference: the ws, turn-telemetry and upstream surfaces are read
// TOGETHER when a turn fails, so the same upstream message would appear at two lengths in one
// investigation. Kotlin has no re-export for a const, so one declaration means one import — which is
// why this is a file rather than a pointer comment.
//
// WIDTH CHOSEN: 200, the gateway's value, and NOT because it is larger. TurnTelemetry's ERR_SNIPPET
// is what FailureText.kt and TurnKnownEnd.kt use to build text the CLIENT can see, and those bytes
// are pinned by the oracle replay; unifying on 160 would have moved a wire contract to tidy a
// constant. The two log-only surfaces widen by 40 characters, which costs nothing and moves no pin.
package splice.core.util

/** How much of an upstream error is quoted into a log line or a rendered failure message. */
public const val ERR_SNIPPET: Int = 200
