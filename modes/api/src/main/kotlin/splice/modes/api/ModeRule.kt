// NEW: whether a mode is on, written once (modes/README.md, "Turning a mode on and off"). For a mode and a head: a
// head outside the manifest's non-empty `[heads] only`, or on its `[heads] off`, is never offered it, and splice.toml
// cannot turn it on; otherwise splice.toml's `[modes.<name>] heads.<head>` decides, then its `on`, then the manifest's
// `on`. A caller on the manifest's `[callers] off` is never offered the mode, whatever the head says. The list, the
// door and the switch read this and decide nothing of their own.
package splice.modes.api

import splice.core.mode.ModeSetting

/** Where a mode's value for a head came from, as `GET /api/modes` names it. */
public enum class ModeFrom(public val wire: String) {
    MANIFEST("manifest"),
    SPLICE_TOML("splice.toml"),
    HEAD("head"),
    NEVER("never"),
}

/** A mode's value and where it came from. */
public data class ModeState(val on: Boolean, val from: ModeFrom)

public object ModeRule {
    /** The mode's value for [head], given splice.toml's table for it ([setting], null when it has none). */
    public fun forHead(manifest: ModeManifest, setting: ModeSetting?, head: String): ModeState {
        val offer = manifest.offer
        val outside = offer.only.isNotEmpty() && head !in offer.only
        if (outside || head in offer.off) return ModeState(false, ModeFrom.NEVER)
        setting?.heads?.get(head)?.let { return ModeState(it, ModeFrom.HEAD) }
        return everyHead(manifest, setting)
    }

    /** The mode's every-head value: splice.toml's `on`, else the manifest's. */
    public fun everyHead(manifest: ModeManifest, setting: ModeSetting?): ModeState =
        setting?.on?.let { ModeState(it, ModeFrom.SPLICE_TOML) } ?: ModeState(manifest.offer.on, ModeFrom.MANIFEST)

    /** Whether [caller] is ever offered the mode. */
    public fun offeredTo(manifest: ModeManifest, caller: ModeCaller): Boolean = caller !in manifest.offer.callersOff
}
