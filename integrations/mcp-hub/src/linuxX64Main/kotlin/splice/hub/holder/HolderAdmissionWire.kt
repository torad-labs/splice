package splice.hub.holder

/** A one-shot negative admission is distinct from a ledger-backed response and cannot consume an armed identity. */
object HolderAdmissionWire {
    fun reject(text: String): String = "$REJECTION_PREFIX$text"

    fun rejected(line: String): String? = line.takeIf { it.startsWith(REJECTION_PREFIX) }
        ?.removePrefix(REJECTION_PREFIX)?.takeIf(HolderProtocol::rejected)
}

private const val REJECTION_PREFIX = "@splice-holder/2 reject "
