// The steps a round runner under test takes: RetryEngine's, on a ledger of the test's own, as a turn's runners take
// them on the turn's one ledger (failure-handling slice 4). The waits default to none, so a test that does not look at
// a wait never sleeps; one that does passes its own.
package splice.head.round

import splice.core.util.WallClock
import splice.upstream.TurnRetry
import splice.upstream.TurnWaits
import splice.upstream.retry.RetryLedger

internal fun testRoundSteps(waits: TurnWaits = TurnWaits { _, _, _ -> }): RoundSteps =
    RoundSteps(TurnRetry(RetryLedger(), waits), WallClock { 0L })
