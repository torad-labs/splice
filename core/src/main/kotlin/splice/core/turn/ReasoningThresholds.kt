// PORT-OF: server/src/reasoning/mirror.mjs named thresholds @ pre-public-port-baseline — invariants (L2/AGENTS.md):
// mirror >= 20 chars, promote >= 40. CX-09 (2026-08-11) retired HONESTY_MIN_CHARS: the
// empty-turn gate no longer compares a character count, it asks whether anything reached the
// client (emittedThinking) and whether the mirror will cover the rest — so the honesty floor is
// the MIRROR floor, and a separate constant that merely happened to equal it was a trap.
// Constants live in :core so the dialect
// (promote decisions) and the gateway (mirror) share ONE definition; mirrorInto itself is
// L2-walled to the gateway's Mirror.kt.
package splice.core.turn

public const val MIRROR_MIN_CHARS: Int = 20
public const val PROMOTE_MIN_CHARS: Int = 40
