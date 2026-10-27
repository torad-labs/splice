// NEW: failure-handling slice 3 (2026-10-26). Replaces VendorRefusals: what one provider says differently from its API
// type's standard, declared in that provider's own module with the evidence in its KDoc (final-design.md section 12).
package splice.upstream.failure

import splice.core.failure.FailureCause
import splice.core.turn.Usage
import splice.core.usage.PlanLimit

/** A provider's documented deviation from its API type's standard reading. Each method returns null, or false, for
 *  every reply that lacks the deviation's documented shape, and the standard reader reads it as it reads any other. */
public interface ProviderDeviation {
    public fun http(reply: FailedReply): FailureCause.Provider? = null

    public fun event(event: FailedEvent): FailureCause.Provider? = null

    /** Whether [reply] is a dead credential spelled with a status other than 401, which always is one: the login is
     *  renewed once, as a 401's is (xAI answers an expired token with 403 `unauthenticated:bad-credentials`). */
    public fun deadCredential(reply: FailedReply): Boolean = false

    /** The plan window [reply] names in the provider's own body (codex's usage_limit_reached), as
     *  [ReplyReader.planLimit] keeps it until slice 5. */
    public fun planLimit(reply: FailedReply): PlanLimit? = null

    /** What a round [model] completed, billed as [usage], says that its standard reads as a finished round: a reasoning
     *  the provider cut short at a bound it is documented to cut at (failure-handling slice 4, codex's 518n-2). Null
     *  for every round the provider finished whole. */
    public fun completed(model: String, usage: Usage): FailureCause.Provider? = null
}
