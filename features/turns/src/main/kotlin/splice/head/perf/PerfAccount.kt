package splice.head.perf

/** The login that carried a turn and whether its prompt cache started cold. [label] null means no account was
 *  proved for the turn, and then the row carries neither fact. */
public data class PerfAccount(
    val label: String? = null,
    val cacheCold: Boolean = false,
)
