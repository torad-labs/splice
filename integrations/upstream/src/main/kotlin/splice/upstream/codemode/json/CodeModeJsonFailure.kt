package splice.upstream.codemode.json

/** serde_json reports byte columns and the position at which its streaming visitor stopped. */
internal class CodeModeJsonFailure(val reason: String, val offset: Int, val byteOffset: Int? = null) :
    IllegalArgumentException(reason)
