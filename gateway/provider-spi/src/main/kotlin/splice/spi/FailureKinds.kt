// NEW: the classified-failure DTO and the transport it came from. Split from
// UpstreamFailureClassifier.kt so the regex object is not billed for the
// result types (concentration, 2026-08-19). Same-package FQCNs are unchanged.
package splice.spi

import splice.core.turn.ErrorType

public data class ClassifiedFailure(val type: ErrorType, val message: String)

public enum class FailureSource { HTTP, SSE }
