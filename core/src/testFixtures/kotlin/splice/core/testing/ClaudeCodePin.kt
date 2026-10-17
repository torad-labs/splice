// NEW: 2026-10-08 — the Claude Code release splice pins, for the tests of modules that must agree with it.
// The pin is :core's own (ClientVersionTracker's default), so it is `internal` there; a sibling module's
// test that asserts "this table was read from the pinned release" reads it through here, which is the
// owner's fixture and not a second copy of the number.
package splice.core.testing

import splice.core.TESTED_CLAUDE_CODE

public const val CLAUDE_CODE_PIN: String = TESTED_CLAUDE_CODE
