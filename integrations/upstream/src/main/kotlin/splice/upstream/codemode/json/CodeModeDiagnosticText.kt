package splice.upstream.codemode.json

/** The source Rust compiler's Debug string spelling for model-visible protocol diagnostics. */
public object CodeModeDiagnosticText {
    public fun quoted(value: String): String = CodeModeRustDebug.string(value)
}
