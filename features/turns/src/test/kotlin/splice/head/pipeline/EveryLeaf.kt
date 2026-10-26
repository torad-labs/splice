// NEW: failure-handling slice 1 (2026-10-26). One failure of every kind, for the pipeline tests that walk each kind
// across the presentation seam. It replaces their walk over the old cause enum's entries.
package splice.head.pipeline

import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LoginOwner
import splice.core.failure.Outlast
import splice.core.failure.ProtocolFault
import splice.core.failure.RefusedBy
import splice.core.failure.ServerFault
import splice.core.failure.Stall
import splice.core.failure.StoppedBy
import splice.core.failure.Tear
import splice.core.failure.Unreach
import java.time.Instant

/** One of every leaf, deterministic ones included. A leaf added later belongs here too. */
internal val everyLeaf: List<FailureCause> = listOf(
    FailureCause.Provider.Limited(Lift.Unstated, window = null),
    FailureCause.Provider.Overloaded(Lift.Unstated, loading = false),
    FailureCause.Provider.ServerError(ServerFault.INTERNAL),
    FailureCause.Provider.NeedsPayment(Lift.Unstated, subscription = false),
    FailureCause.Provider.Forbidden(),
    FailureCause.Provider.AuthRejected(LoginOwner.SPLICE),
    FailureCause.Provider.TooLong(limitTokens = null, whileAnswering = false),
    FailureCause.Provider.Rejected(staleReasoning = false),
    FailureCause.Provider.ModelMissing("gone-model"),
    FailureCause.Provider.Refused(RefusedBy.MODEL),
    FailureCause.Provider.Empty(reasoningOnly = false),
    FailureCause.Provider.ToolTear(runaway = false),
    FailureCause.Provider.Unrecognized(clientError = false),
    FailureCause.Path.Unreachable(Unreach.RESET_BEFORE_REPLY, local = false),
    FailureCause.Path.Torn(Tear.NO_TERMINAL, toolOpen = false),
    FailureCause.Path.Stalled(Stall.IDLE),
    FailureCause.Splice.NoLogin(loginCommand = null),
    FailureCause.Splice.AtCapacity(limit = 4),
    FailureCause.Splice.Declined(Decline.Budget(limit = "\$20.00", resetsAt = Instant.parse("2026-10-27T05:00:00Z"))),
    FailureCause.Splice.TurnBudgetSpent(budgetMs = 900_000L, beforeFirstAttempt = false),
    FailureCause.Splice.HoldOutlasted(Outlast.MAX_HOLD, FailureCause.Provider.Limited(Lift.Unstated, null), null),
    FailureCause.Splice.CompactionUnfinished,
    FailureCause.Splice.Stopped(StoppedBy.CONSOLE),
    FailureCause.Splice.Protocol(ProtocolFault.CODE_MODE_STATE),
    FailureCause.Splice.Protocol(ProtocolFault.CODE_MODE_START),
    FailureCause.Splice.Internal(),
)
