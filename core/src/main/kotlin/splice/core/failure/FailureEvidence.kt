// NEW: failure-handling slice 1 (2026-10-26). What a failure left behind for the journal, the trace and the perf
// row. The cause decides what happens; the evidence only records it. Slice 1 carries what today's sites hand on:
// the sentence today's renderers write, the provider's status and the provider's own words for the console's
// Requests list. Later slices add a field when the reader that fills it lands.
package splice.core.failure

/** [message]: the sentence today's renderers write for this failure, or the exception's text. [status]: the
 *  provider's HTTP status, when an answer came back. [said]: the provider's own words as V4-444 judged them for the
 *  perf row, or null when the sentence is splice's; slice 2 moves them onto the leaf as [ProviderWords]. [body]: the
 *  reply body as it came, for the journal and the trace. [retryAfterMs]: the pushback the reply's Retry-After
 *  header asked for, which today's retry budgets read until slice 4's engine reads the cause (slice 3). */
public data class FailureEvidence(
    val message: String,
    val status: Int? = null,
    val said: String? = null,
    val body: String? = null,
    val retryAfterMs: Long? = null,
)
