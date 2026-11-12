// NEW: failure-handling slice 2 (2026-10-26). The voice a test's terminal speaks in: a terminal composes its error
// line from the turn voice it is built with, so every test that builds one gives it this voice, on a fixed zone so a
// line reads the same on every machine.
package splice.head

import splice.core.failure.HeadVoice
import splice.core.failure.TurnVoice
import splice.core.failure.VoiceFacts
import java.time.ZoneId

/** A head with no login command and no provider to name, on Chicago time. */
internal val testHeadVoice: HeadVoice = HeadVoice("claude-test", null, VoiceFacts(zone = ZoneId.of("America/Chicago")))

/** The voice of a turn about no particular request: not a compaction, not a first exchange. */
internal val testVoice: TurnVoice get() = testHeadVoice.anyTurn
