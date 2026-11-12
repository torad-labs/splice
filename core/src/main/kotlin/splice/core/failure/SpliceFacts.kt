// NEW: failure-handling slice 1 (2026-10-26). The facts a splice leaf of FailureCause carries.
package splice.core.failure

public enum class StoppedBy { RESTART, CONSOLE, HEAD_STOPPING }

/** [CODE_MODE_START]: the code-mode machine cannot go on now, which a later turn may change: it could not start or
 *  reach its runtime, could not save its state, or still waits on the client's tool results.
 *  [CODE_MODE_STATE]: its recorded state cannot go on, which no re-send changes. */
public enum class ProtocolFault { CODE_MODE_START, CODE_MODE_STATE, DIALECT_UNSUPPORTED }

/** Why a hold ended without the turn going on (failure-handling slice 5): [MISSED_RESETS], its cause did not lift at
 *  three times it stated; [MAX_HOLD], it held seven days; [TOO_FAR], the stated reset lies past seven days, so no hold
 *  begins; [WALL], the session cannot wait for it, so Claude Code is told the reset and waits itself. */
public enum class Outlast { MISSED_RESETS, MAX_HOLD, TOO_FAR, WALL }
