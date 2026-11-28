package splice.core.client

/** Explicit opt-out parser shared by launch inputs (`SPLICE_NO_HOLD`) and per-request headers (`x-splice-no-hold`). */
public object NoHoldFlag {
    public fun isOn(raw: String?): Boolean = when (raw?.trim()?.lowercase()) {
        "1", "true", "yes" -> true
        else -> false
    }
}
