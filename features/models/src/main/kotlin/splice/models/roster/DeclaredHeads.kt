// PORT-OF: daemon/control/.../ControlPorts.kt (DeclaredHead, DeclaredHeads) — invariants unchanged: the
// declared roster the models route joins against, moved beside that route.
package splice.models.roster

import splice.core.topology.HeadModel

/**
 * What the topology declares about ONE head: the provider key it is registered under, and the model
 * list it declares (`HeadConfig.models`, each an id and an optional slot).
 *
 * [models] IS NULLABLE BECAUSE `HeadConfig.models` IS. A head whose operator declared no tiers is a
 * real state, and it is a different fact from a head the wiring never named — which is an ABSENT KEY
 * in the map [DeclaredHeads] returns, never a null value here. The two are kept apart on purpose;
 * see the role's own KDoc for what conflating them would draw on the page.
 */
public data class DeclaredHead(
    /** The registry's provider key for this head. The models page GROUPS by it — FEATURES.md §6,
     *  decided 2026-09-18 for V4-127 — so it is part of the payload, not a convenience. */
    val provider: String,
    val models: List<HeadModel>?,
)

/**
 * V4-127: what the topology declared about every head — the provider key and the declared model list
 * — for the whole daemon at once.
 *
 * WHY THIS IS A ROLE AND NOT FIELDS ON the control plane's `ManagedHead`, where a per-head static fact would naturally
 * live: that record is already a recorded constructor-width offender at 17 parameters, and carrying
 * these two facts pushed it to 19 across a FOURTH subsystem. The ratchet read the growth as WIDENED
 * and refused — "a recorded offender is DEBT, not permission to keep adding parameters" — and
 * re-baselining it would be the bypass that gate exists to prevent. So the facts arrive here and are
 * assigned on ControlServer after construction, the shape `compaction` took in V4-136.
 *
 * THE ROUTE GENUINELY NEEDS THEM, which is why this is a move rather than a deletion: the payload
 * reports the join in BOTH directions, and only the declared list knows that a slot WAS declared. A
 * resolved model that no tier names carries `slot: null`; a declared slot that resolved to nothing
 * must still be its own row, or the page silently omits the tier the operator is missing.
 *
 * AN ABSENT KEY IS NOT A NULL VALUE. `HeadConfig.models` is itself nullable, so a present key whose
 * [DeclaredHead.models] is null is a real state — this head's operator declared no tiers — while an
 * absent key is a head the wiring did not name. The route answers 5xx for the second and renders the
 * first. A per-head `List<HeadModel>?` lookup could not tell them apart, which is why the role answers
 * a MAP: reporting a wiring gap as an empty declaration would draw every served model with no tier
 * naming it, a confident false negative about the one thing this page exists to display. */
public fun interface DeclaredHeads {
    /** Every head the topology declares, by head key. An absent key means the wiring did not name
     *  that head — the route's named 5xx — never that the head declares nothing. */
    public operator fun invoke(): Map<String, DeclaredHead>
}
