// NEW: the two annotations a check may carry beside its finding, grouped so [DoctorCheck] stays inside
// detekt's constructor width.
package splice.diagnostics.doctor

/** What a check says beside its finding. [pendingRestart]: the declared restart-required value differs
 *  from the running daemon's value. [details]: supporting counts shown separately from the finding. */
internal data class DoctorCheckNotes(
    val pendingRestart: Boolean = false,
    val details: String? = null,
)
