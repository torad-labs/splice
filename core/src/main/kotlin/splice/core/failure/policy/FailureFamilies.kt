package splice.core.failure.policy

import splice.core.failure.ClientWire
import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.failure.LoginOwner
import splice.core.failure.Outlast
import splice.core.failure.ProtocolFault
import splice.core.failure.StoppedBy

/** Classifies whether the held cause can heal on retry; ClientWire alone chooses its HTTP/error representation. */
internal object FailureFamilies {
    fun of(cause: FailureCause): ClientWire.Family = when (cause) {
        is FailureCause.Provider -> provider(cause)
        is FailureCause.Path -> ClientWire.Family.R
        is FailureCause.Splice -> splice(cause)
    }

    private fun provider(cause: FailureCause.Provider): ClientWire.Family = when (cause) {
        is FailureCause.Provider.TooLong -> ClientWire.Family.T
        is FailureCause.Provider.AuthRejected ->
            if (cause.owner == LoginOwner.CLIENT) ClientWire.Family.CLIENT_LOGIN else ClientWire.Family.HELD_LOGIN
        is FailureCause.Provider.Forbidden, is FailureCause.Provider.Rejected, is FailureCause.Provider.ModelMissing,
        is FailureCause.Provider.Refused,
        -> ClientWire.Family.P
        is FailureCause.Provider.Unrecognized -> if (cause.clientError) ClientWire.Family.P else ClientWire.Family.R
        is FailureCause.Provider.Limited, is FailureCause.Provider.Overloaded, is FailureCause.Provider.ServerError,
        is FailureCause.Provider.NeedsPayment, is FailureCause.Provider.Empty, is FailureCause.Provider.ToolTear,
        is FailureCause.Provider.ReasoningCut,
        -> ClientWire.Family.R
    }

    private fun splice(cause: FailureCause.Splice): ClientWire.Family = when (cause) {
        is FailureCause.Splice.NoLogin -> ClientWire.Family.HELD_LOGIN
        is FailureCause.Splice.Declined -> declined(cause.why)
        is FailureCause.Splice.HoldOutlasted -> outlasted(cause)
        is FailureCause.Splice.Stopped -> again(cause.by != StoppedBy.CONSOLE)
        is FailureCause.Splice.Protocol -> again(cause.fault == ProtocolFault.CODE_MODE_START)
        // A failure inside splice that the same request earns again is P, not R: an unparseable base_url throws
        // identically on every send (V4-81), and R's promise is that a re-send can heal it. Section 11 lists
        // Internal in R alone; the goal's "permanent kinds are never re-sent" decides the permanent one.
        is FailureCause.Splice.Internal -> again(!cause.permanent)
        is FailureCause.Splice.AtCapacity, is FailureCause.Splice.TurnBudgetSpent,
        FailureCause.Splice.CompactionUnfinished,
        -> ClientWire.Family.R
    }

    /** A hold that ended takes its family from the cause it held (section 11): a held limit ends H, a held overload R,
     *  a held spent account P. A hold the session's wall cannot hold is handed to the client, L. */
    private fun outlasted(cause: FailureCause.Splice.HoldOutlasted): ClientWire.Family = when {
        cause.why == Outlast.WALL -> ClientWire.Family.L
        cause.held is FailureCause.Provider.Limited -> ClientWire.Family.H
        cause.held is FailureCause.Provider.NeedsPayment -> ClientWire.Family.P
        else -> ClientWire.Family.R
    }

    /** R where a re-send can heal this ending ([heals]), P where the same request earns it again. */
    private fun again(heals: Boolean): ClientWire.Family = if (heals) ClientWire.Family.R else ClientWire.Family.P

    private fun declined(why: Decline): ClientWire.Family = when (why) {
        Decline.HeapBusy -> ClientWire.Family.R
        // The caller's own credential at splice's door is a login cause: its 401 is what makes the session read its
        // key helper again, so a rotated key strands nobody (ClientWire.KEPT, record http-401).
        Decline.ReadTimeout, Decline.BadLocalKey, Decline.NotLaunched, is Decline.SpliceKeyAsLogin ->
            ClientWire.Family.KEPT
        is Decline.Preflight -> ClientWire.Family.T
        is Decline.TooLarge, is Decline.Unfit, is Decline.Budget, is Decline.BadBody, is Decline.UnknownModel,
        Decline.ForeignHost, Decline.LivenessProbe, Decline.NoSharedLogin, is Decline.Untranslatable,
        -> ClientWire.Family.P
    }
}
