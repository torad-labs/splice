// NEW: V4-102 (arch-audit, 2026-09-17) — the Anthropic error envelope, built in ONE place.
//
// Four surfaces wrote this shape by hand — AdmissionResponses (as a String), CollectingTerminal
// (with an optional usage), SseEmitter (wrapped in an SSE frame) and provider-spi's
// RateLimitCooldown (our own fail-fast body) — and nothing held them together. They agreed by
// convention, so a change to one was invisible to the others, which is the class the
// kt-error-envelope-single-source wall exists to catch.
//
// WHY IT LIVES IN CORE, WHICH IS THE WHOLE DESIGN CONSTRAINT: provider-spi cannot import :gateway,
// so a builder in the gateway's wire package is unreachable from the one site that most needs
// it — our own synthesized failure body, the one the operator saw as braces in his transcript.
// Core is the lowest module all four can reach.
//
// THE FACTORY IS NAMED `of`, AND THAT NAME IS LOAD-BEARING. law_pre_content_wire_type.py finds call
// sites matching a function called errorEnvelope( — a wall written for a different purpose, which
// would red every call site of a builder named that way. `ErrorEnvelope.of(...)` says the same thing
// without tripping it. Renaming it to errorEnvelope would look tidier and break the build.
//
// THE BYTES ARE A CONTRACT. This shape is what upstream hands back and what our own fail-fast body
// claims to be, so the classifier can read either identically — see UpstreamFailureClassifier, which
// lifts error.message. Changing the rendering here changes a wire contract, not a formatting choice.
package splice.core.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The error envelope every surface answers with: {"type":"error","error":{"type","message"}}. */
public object ErrorEnvelope {

    /** The envelope as a JSON object. [usage] rides INSIDE as a sibling of `error`, because the
     *  non-stream path bills the turn even when it fails honestly (RG2-001) and the wire response
     *  must not be the one place that accounting goes missing. Omitted entirely when absent, so a
     *  caller with no usage renders byte-identically to the callers that never had one. */
    public fun of(type: String, message: String, usage: JsonObject? = null): JsonObject = buildJsonObject {
        put(FIELD_TYPE, FIELD_ERROR)
        putJsonObject(FIELD_ERROR) {
            put(FIELD_TYPE, type)
            put(FIELD_MESSAGE, message)
        }
        usage?.let { put(FIELD_USAGE, it) }
    }
}

private const val FIELD_TYPE = "type"
private const val FIELD_ERROR = "error"
private const val FIELD_MESSAGE = "message"
private const val FIELD_USAGE = "usage"
