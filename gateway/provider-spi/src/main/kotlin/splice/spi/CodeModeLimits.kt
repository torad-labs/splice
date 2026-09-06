// NEW: shared UTF-8 ceilings keep bridge admission and worker frames in agreement.
package splice.spi

/** Hard byte ceilings for code-mode text and its serialized worker protocol. */
public object CodeModeLimits {
    public const val MAX_TEXT_BYTES: Int = 65_536
    public const val MAX_FRAME_BYTES: Int = 1_048_576

    /** Whether [value] fits the worker's UTF-8 text limit, independently of character limits. */
    public fun fitsText(value: String): Boolean = value.encodeToByteArray().size <= MAX_TEXT_BYTES
}
