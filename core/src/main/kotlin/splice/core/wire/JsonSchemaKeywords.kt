// NEW: the JSON Schema keywords and type names that more than one dialect reads from a caller's schema, declared once
// in :core (2026-10-27). The Kimi sanitizer and the Responses strict rewrite walk the same schemas, so they spell the
// same keywords from one declaration.
package splice.core.wire

public object JsonSchemaKeywords {
    public const val REF: String = "\$ref"
    public const val DEFS: String = "\$defs"
    public const val DEFINITIONS: String = "definitions"
    public const val PROPERTIES: String = "properties"
    public const val ITEMS: String = "items"
    public const val ANY_OF: String = "anyOf"
    public const val ENUM: String = "enum"

    /** The `type` values of an object schema and an array schema. */
    public const val OBJECT: String = "object"
    public const val ARRAY: String = "array"
}
