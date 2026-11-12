package splice.upstream.codemode.json

/** A derived serde visitor consumes one value from its shared reader. */
public fun interface CodeModeJsonVisitor<T> {
    public fun read(reader: CodeModeArgumentJson): T
}

/** Inspect a field name before its colon or value is consumed, as serde duplicate checks do. */
public fun interface CodeModeJsonFieldEncounter {
    public fun encounter(name: String): Unit
}

/** Consume a known or ignored field's value after its colon. */
public fun interface CodeModeJsonField {
    public fun read(name: String): Unit
}

/** Consume the value at one position of a derived tuple visitor. */
public fun interface CodeModeJsonElement {
    public fun read(index: Int): Unit
}
