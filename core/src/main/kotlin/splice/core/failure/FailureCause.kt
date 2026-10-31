// NEW: failure-handling slice 1 (2026-10-26). Why a turn failed, as one sealed value, decided where the failure is
// decided. Replaces the 19-value enum and FailurePhase that lived in core/turn/FailureCause.kt, and the
// FailureTraits that rode beside them: a leaf now says what kind of failure it is, so whether it was the provider's
// own report, whether a re-send can change it and the name the perf row stores all follow from the leaf.
//
// ClientWire reads the leaf for the status, the error type and x-should-retry. Slice 1 kept today's wire and
// words: each leaf answered what the site that builds it answered before; slice 4 answers by family from the client
// reaction record instead, which ClientWireRecordTest pins, and
// [FailureCause.persisted] is the name that site wrote, which PersistedNamesTest pins. Provider words reach a leaf in
// slice 2, with their filter, so every [FailureCause.Provider.said] is null until then.
package splice.core.failure

import java.time.Instant

/** Why a turn failed: the provider's answer ([Provider]), a broken path with no answer ([Path]), or splice's own
 *  ending or refusal ([Splice]). Every leaf names the perf row's cause; [ClientWire] decides what the client reads. */
public sealed class FailureCause {
    /** The cause name perf rows store and the usage console reads (TurnsTest's Cut off and Given up). */
    public abstract val persisted: PersistedCause

    /** The provider itself reported this failure: a body or an event it sent. False for what splice concluded on its
     *  own (a stall, a tear, a local guard). Drives the G20 health split between the provider and this machine. */
    public open val providerReported: Boolean get() = false

    /** No retry can change this verdict: identical bytes earn it again. Before content, ClientWire keeps the real
     *  type of a permanent api_error instead of advertising it as an overload (V4-81). */
    public open val permanent: Boolean get() = false

    /** A verdict splice's own state decided and the same request cannot change. Its client message opens with the
     *  ending's sentence, and before content it keeps its real type like a [permanent] one. */
    public open val deterministic: Boolean get() = false

    /** A provider-stated reset the session may resume at, including one too distant for an in-request hold. */
    public open val resumeAt: Instant? get() = null

    /** The provider answered, and the answer was a failure. [said] waits for slice 2's ProviderWordsFilter. */
    public sealed class Provider : FailureCause() {
        public abstract val said: ProviderWords?
        override val providerReported: Boolean get() = true

        /** A rate limit: a 429, or a body or event that names one. */
        public data class Limited(
            val lift: Lift,
            val window: LimitWindow?,
            override val said: ProviderWords? = null,
        ) : Provider() {
            override val persisted: PersistedCause get() = PersistedCause.VENDOR_RATE_LIMITED
            override val permanent: Boolean get() = true
            override val resumeAt: Instant? get() = (lift as? Lift.At)?.at
        }

        /** The provider is over capacity: a 502, or a code that spells an overload. */
        public data class Overloaded(
            val lift: Lift,
            val loading: Boolean,
            override val said: ProviderWords? = null,
        ) : Provider() {
            override val persisted: PersistedCause get() = PersistedCause.UPSTREAM_STATUS_5XX
            override val resumeAt: Instant? get() = (lift as? Lift.At)?.at
        }

        /** A 5xx that is no overload, or [ServerFault.FOREIGN_KEY]: a 401 naming a masked key the login never sent,
         *  the provider failing its own check (V4-242). */
        public data class ServerError(val fault: ServerFault, override val said: ProviderWords? = null) : Provider() {
            override val persisted: PersistedCause
                get() = if (fault == ServerFault.FOREIGN_KEY) {
                    PersistedCause.UPSTREAM_REPORTED
                } else {
                    PersistedCause.UPSTREAM_STATUS_5XX
                }
        }

        /** [subscription]: the provider wants a subscription renewed rather than credit added. */
        public data class NeedsPayment(
            val lift: Lift,
            val subscription: Boolean,
            override val said: ProviderWords? = null,
        ) : Provider() {
            override val persisted: PersistedCause get() = PersistedCause.VENDOR_QUOTA_EXHAUSTED
            override val permanent: Boolean get() = true
            override val resumeAt: Instant? get() = (lift as? Lift.At)?.at
        }

        /** The provider refuses this account the request: a model its plan leaves out, or, with [block], the account
         *  itself until its owner acts, which moves the turn to another login and holds the one that met it. */
        public data class Forbidden(
            override val said: ProviderWords? = null,
            val block: AccountBlock? = null,
        ) : Provider() {
            override val persisted: PersistedCause get() = PersistedCause.UPSTREAM_STATUS_4XX
            override val permanent: Boolean get() = true
        }

        public data class AuthRejected(val owner: LoginOwner, override val said: ProviderWords? = null) : Provider() {
            override val persisted: PersistedCause get() = PersistedCause.AUTH_MISSING
            override val permanent: Boolean get() = true
        }

        /** The request outgrew the model's window. [whileAnswering]: it filled during the answer
         *  (stop_reason model_context_window_exceeded). */
        public data class TooLong(
            val limitTokens: Long?,
            val whileAnswering: Boolean,
            override val said: ProviderWords? = null,
        ) : Provider() {
            override val persisted: PersistedCause get() = PersistedCause.REQUEST_TOO_LARGE
            override val permanent: Boolean get() = true
        }

        public data class Rejected(val staleReasoning: Boolean, override val said: ProviderWords? = null) : Provider() {
            override val persisted: PersistedCause get() = PersistedCause.UPSTREAM_STATUS_4XX
            override val permanent: Boolean get() = true
        }

        public data class ModelMissing(val model: String, override val said: ProviderWords? = null) : Provider() {
            override val persisted: PersistedCause get() = PersistedCause.UPSTREAM_STATUS_4XX
            override val permanent: Boolean get() = true
        }

        /** The model or the provider's policy answered no: a refusal, a policy code or a content filter. */
        public data class Refused(val by: RefusedBy, override val said: ProviderWords? = null) : Provider() {
            override val persisted: PersistedCause
                get() = if (by == RefusedBy.MODEL) PersistedCause.MODEL_REFUSED else PersistedCause.CONTENT_FILTERED
            override val permanent: Boolean get() = true
        }

        /** The model finished with nothing the client can read. [reasoningOnly]: it reasoned and said nothing. */
        public data class Empty(val reasoningOnly: Boolean, override val said: ProviderWords? = null) : Provider() {
            override val persisted: PersistedCause get() = PersistedCause.UPSTREAM_REPORTED
        }

        /** The tool call's arguments are corrupt, or [runaway]: splice's own size guard tripped on what the provider
         *  sent, which is splice's verdict and not the provider's report. [permanent] keeps the one site that marks
         *  its guard as a verdict a re-send repeats (the passthrough runaway). */
        public data class ToolTear(
            val runaway: Boolean,
            override val permanent: Boolean = false,
            override val said: ProviderWords? = null,
        ) : Provider() {
            override val persisted: PersistedCause get() = PersistedCause.TOOL_TEAR
            override val providerReported: Boolean get() = !runaway
        }

        /** A round that finished with its reasoning cut short at [reasoningTokens] (codex's 518n-2 truncation,
         *  CodexReplies). It never ends a turn: the engine continues the round, and at its budget the round's answer
         *  stands (final-design.md section 8). Its persisted name is the provider-reported one, since perf rows store
         *  a name for every leaf; no ending ever carries it. */
        public data class ReasoningCut(
            val reasoningTokens: Long,
            override val said: ProviderWords? = null,
        ) : Provider() {
            override val persisted: PersistedCause get() = PersistedCause.UPSTREAM_REPORTED
        }

        /** A failure no table names. [clientError]: the provider's verdict on the request, which a re-send repeats. */
        public data class Unrecognized(
            val clientError: Boolean,
            override val said: ProviderWords? = null,
        ) : Provider() {
            override val persisted: PersistedCause get() = PersistedCause.UPSTREAM_REPORTED
            override val permanent: Boolean get() = clientError
        }
    }

    /** Nobody answered: the path broke. This keeps the G20 split, since no provider report is involved. */
    public sealed class Path : FailureCause() {
        /** The request never got an answer. [local]: the address is this machine's own runtime. */
        public data class Unreachable(val why: Unreach, val local: Boolean) : Path() {
            override val persisted: PersistedCause
                get() =
                    if (why == Unreach.REFUSED) PersistedCause.CONNECT_REFUSED else PersistedCause.UPSTREAM_CONN_RESET
        }

        /** The answer began and broke off. [toolOpen]: a tool call was open when it broke
         *  (PartialRound.toolTearOpen). */
        public data class Torn(val why: Tear, val toolOpen: Boolean) : Path() {
            override val persisted: PersistedCause
                get() = if (why == Tear.RESET) PersistedCause.UPSTREAM_CONN_RESET else PersistedCause.UPSTREAM_TRUNCATED
        }

        /** The provider went silent past a watchdog tier. */
        public data class Stalled(val tier: Stall) : Path() {
            override val persisted: PersistedCause get() = PersistedCause.UPSTREAM_STALLED
        }
    }

    /** splice ended or declined the turn itself. */
    public sealed class Splice : FailureCause() {
        /** No login to send. [loginCommand] fixes it, where the head has one. */
        public data class NoLogin(val loginCommand: String?) : Splice() {
            override val persisted: PersistedCause get() = PersistedCause.AUTH_MISSING
        }

        /** The head's admission gate and queue were full. */
        public data class AtCapacity(val limit: Int) : Splice() {
            override val persisted: PersistedCause get() = PersistedCause.ADMISSION_FULL
        }

        /** splice refused the request before any provider saw it. */
        public data class Declined(val why: Decline) : Splice() {
            override val persisted: PersistedCause
                get() = if (why is Decline.TooLarge) PersistedCause.REQUEST_TOO_LARGE else PersistedCause.DECLINED
        }

        /** The turn spent its whole budget without progress. [atWall]: a live path was held past that budget to the
         *  session's own time limit, and ended 30 s before it (failure-handling slice 5). */
        public data class TurnBudgetSpent(
            val budgetMs: Long,
            val beforeFirstAttempt: Boolean,
            val atWall: Boolean = false,
        ) : Splice() {
            override val persisted: PersistedCause get() = PersistedCause.UPSTREAM_STALLED
        }

        /** A held turn outlasted its bound. [held]: the cause it held on, which its family comes from: whether the
         *  provider reported it and whether a re-send can change it are [held]'s. */
        public data class HoldOutlasted(val why: Outlast, val held: FailureCause, val nextReset: Instant?) : Splice() {
            override val resumeAt: Instant? get() = nextReset ?: held.resumeAt

            /** A hold that never began, its reset too far or past the session's wall,
             *  is the held cause's own ending. */
            override val persisted: PersistedCause
                get() = if (why == Outlast.TOO_FAR || why == Outlast.WALL) {
                    held.persisted
                } else {
                    PersistedCause.HOLD_OUTLASTED
                }
            override val providerReported: Boolean get() = held.providerReported
            override val permanent: Boolean get() = held.permanent
            override val deterministic: Boolean get() = held.deterministic
        }

        /** A compaction retry followed a compaction that did not finish (LocalResponses). */
        public data object CompactionUnfinished : Splice() {
            override val persisted: PersistedCause get() = PersistedCause.COMPACTION_UNFINISHED
        }

        /** The turn was stopped: by a restart, by the console (also at admission, for a stopped turn's re-send), or
         *  by a head that is stopping. */
        public data class Stopped(val by: StoppedBy) : Splice() {
            override val persisted: PersistedCause get() = PersistedCause.STOPPED
        }

        /** splice's own protocol state cannot go on: the code-mode machine, or a call the dialect cannot express. */
        public data class Protocol(val fault: ProtocolFault) : Splice() {
            override val persisted: PersistedCause
                get() = if (fault == ProtocolFault.DIALECT_UNSUPPORTED) {
                    PersistedCause.DIALECT_UNSUPPORTED
                } else {
                    PersistedCause.CODE_MODE_PROTOCOL
                }
            override val deterministic: Boolean get() = fault == ProtocolFault.CODE_MODE_STATE
        }

        /** A failure splice cannot attribute. [escaped]: an exception escaped the turn's own handling, which today
         *  answers api_error rather than an overload. [permanent]: that exception is one a re-send repeats (a base URL
         *  that does not parse). The exception's text goes to the evidence only. */
        public data class Internal(val escaped: Boolean = false, override val permanent: Boolean = false) : Splice() {
            override val persisted: PersistedCause get() = PersistedCause.INTERNAL
        }
    }
}
