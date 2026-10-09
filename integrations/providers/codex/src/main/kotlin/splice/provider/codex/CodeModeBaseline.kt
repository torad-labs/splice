// NEW: the request history a record was built on, fixed at creation.
package splice.provider.codex

/** What the client's input looked like when the record was created: the whole input and its logical (client-visible)
 *  part, each counted and digested, and the metadata version that wrote them. */
internal data class CodeModeBaseline(
    val inputCount: Int,
    val inputDigest: String,
    val logicalCount: Int,
    val logicalDigest: String,
    val metadataVersion: Int,
)
