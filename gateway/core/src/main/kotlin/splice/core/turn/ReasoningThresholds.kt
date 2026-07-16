// PORT-OF: server/src/reasoning/mirror.mjs named thresholds @ 4ca99f7 — invariants (L2/AGENTS.md):
// mirror >= 20 chars, promote >= 40, honesty < 20. Constants live in :core so the dialect
// (promote decisions) and the gateway (mirror) share ONE definition; mirrorInto itself is
// L2-walled to the gateway's Mirror.kt.
package splice.core.turn

public const val MIRROR_MIN_CHARS: Int = 20
public const val PROMOTE_MIN_CHARS: Int = 40
public const val HONESTY_MIN_CHARS: Int = 20
