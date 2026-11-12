// NEW: failure-handling slice 1 (2026-10-26). Where the turn stood when its failure arrived, which is what decides
// how the client is told: as an HTTP status before anything was sent, or in band once the stream is open.
package splice.core.failure

/** How far the turn's answer to the client had got. */
public enum class Moment {
    /** Before the turn exists: the admission plane answers with an HTTP status. */
    ADMISSION,

    /** The turn runs but its HTTP status is still splice's to choose. */
    BEFORE_COMMIT,

    /** The stream is open and no content has reached the client. */
    COMMITTED,

    /** Content has reached the client. */
    MID_CONTENT,

    /** A stream=false answer: one HTTP status and one body. */
    NON_STREAM,
}

/** Where the turn stood. [streamBegan]: the provider's stream had started. */
public data class Position(val moment: Moment, val streamBegan: Boolean)
