// NEW: how a head asks its backend to hold the answer to a caller's output schema (2026-10-27). The shapes live here;
// which one a backend takes, and the subset its strict mode documents, is a vendor fact each provider module states.
package splice.dialect.responses

/** How a head asks its backend to hold the answer to a caller's json_schema text format. */
public sealed class ResponsesOutputSchemaPolicy {
    /** Strict over the schema as written: the Responses contract, for a profile that states nothing else. */
    public data object StrictAsGiven : ResponsesOutputSchemaPolicy()

    /** The backend holds every answer to the schema without strict, so strict is false and the schema goes as
     *  written, and a schema outside its strict subset is answered instead of refused. */
    public data object ConstrainedWithoutStrict : ResponsesOutputSchemaPolicy()

    /** The backend's strict mode holds [subset]. The schema is rewritten into it and sent strict; where no rewrite
     *  keeps an answer to it valid against the caller's schema, the schema goes as written with strict false. */
    public data class StrictWithin(val subset: StrictSchemaSubset) : ResponsesOutputSchemaPolicy()
}

/** What a vendor's strict structured output accepts in a schema, as its documentation states it. */
public data class StrictSchemaSubset(
    /** Every object must set additionalProperties false; an object the caller left open is closed. */
    val closedObjects: Boolean,
    /** Every property must be listed in required; one the caller left optional is made nullable, and a null the
     *  backend answers for it reaches the client as the field left out. */
    val everyKeyRequired: Boolean,
    /** The keywords strict accepts. Annotations outside it are dropped; any other keyword sends the schema as
     *  written. */
    val keywords: Set<String>,
    /** The string formats strict enforces, or null for any. */
    val formats: Set<String>?,
    /** Whether a reference may reach the schema it sits in. */
    val recursion: Boolean,
    /** Whether the root must be an object schema, not a union. */
    val objectRoot: Boolean,
    val limits: StrictSchemaLimits = StrictSchemaLimits(),
)

/** The sizes a vendor's strict mode refuses past, or null where it states none. */
public data class StrictSchemaLimits(
    /** Levels of object nesting, the root object being the first. */
    val nestingLevels: Int? = null,
    /** Object properties across the whole schema. */
    val properties: Int? = null,
    /** Enum values across the whole schema. */
    val enumValues: Int? = null,
    /** Characters of property names, definition names, and enum and const string values, together. */
    val stringChars: Int? = null,
)
