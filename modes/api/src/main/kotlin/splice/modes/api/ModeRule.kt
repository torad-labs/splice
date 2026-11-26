// NEW: whether a mode is on, written once (modes/README.md, "Turning a mode on and off"). For a mode, a head and a
// caller: a head outside the manifest's non-empty `[heads] only`, or on its `[heads] off`, and a caller on its
// `[callers] off`, are never offered it, and splice.toml cannot turn it on; otherwise splice.toml's
// `[modes.<name>] heads.<head>` decides, then its `on`, then the manifest: its `on`, or, for a caller its `on_for`
// names, on. The list, the door and the switch read this and decide nothing of their own.
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
    /** The mode's value for [head], given splice.toml's table for it ([setting], null when it has none), for a
     *  session whose requests come from [caller]; null asks for a caller the manifest's `on_for` does not name. */
    public fun forHead(
        manifest: ModeManifest,
        setting: ModeSetting?,
        head: String,
        caller: ModeCaller? = null,
    ): ModeState {
        val offer = manifest.offer
        val outside = offer.only.isNotEmpty() && head !in offer.only
        val leftOut = head in offer.off || caller in offer.callersOff
        if (outside || leftOut) return ModeState(false, ModeFrom.NEVER)
        setting?.heads?.get(head)?.let { return ModeState(it, ModeFrom.HEAD) }
        return everyHead(manifest, setting, caller)
    }

    /** The mode's every-head value for [caller]: never for a caller on `[callers] off`, else splice.toml's `on`, else
     *  the manifest's `on`, or on for a caller its `on_for` names. */
    public fun everyHead(manifest: ModeManifest, setting: ModeSetting?, caller: ModeCaller? = null): ModeState {
        val offer = manifest.offer
        if (caller in offer.callersOff) return ModeState(false, ModeFrom.NEVER)
        setting?.on?.let { return ModeState(it, ModeFrom.SPLICE_TOML) }
        return ModeState(offer.on || caller in offer.onFor, ModeFrom.MANIFEST)
    }

    /** Whether [caller] is ever offered the mode. */
    public fun offeredTo(manifest: ModeManifest, caller: ModeCaller): Boolean = caller !in manifest.offer.callersOff
}
