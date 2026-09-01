// PORT-OF: splice/gateway/head/HeadServer.kt (handleCountTokens, TOKEN_ESTIMATE_BYTES) @ 1caedd6 —
// invariants unchanged: the NAMED CHANGE from the Node port — count_tokens is a purely LOCAL
// estimate that never becomes a quota-burning turn, takes NO turn-gate slot, and stays bounded by
// the fast-fail materialization lease plus maxRequestBytes. Split out (HD-24), deliberately NOT
// merged into HeadAdmission: it is not a turn, and that is the point.
package splice.gateway.head

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.spi.Provider

private const val TOKEN_ESTIMATE_BYTES = 3

internal class CountTokens(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val clientAuth: ClientAuth,
    private val admission: AdmissionGate,
    private val bodyReader: RequestBodyReader,
    private val bodyParse: AnthropicBodyParse,
    private val responses: AdmissionResponses,
) {
    suspend fun handleCountTokens(call: ApplicationCall) {
        if (!clientAuth.authorize(call)) return
        // NO turn-gate slot here: count_tokens is a purely LOCAL estimate (no upstream stream),
        // and queueing it on maxInflight let a saturated head stall or 529 Claude Code's
        // pre-flight sizing for minutes (review 2026-07-22). Memory stays bounded by the
        // materialization gate (fastFail: contention 529s instead of queueing, so a count_tokens
        // flood cannot camp the shared permits) plus the maxRequestBytes cap.
        val body = admission.materializeOrRespond(call, fastFail = true) {
            bodyReader.receiveBodyBounded(call, deps.maxRequestBytes)
        } ?: return
        val parsed = bodyParse.parseOrNull(body.text)
        if (parsed == null) {
            responses.respondInvalidRequest(call, "invalid request body")
        } else {
            // Conservative and Unicode-safe: UTF-8 bytes / 3 overestimates ordinary English while
            // avoiding the old UTF-16 chars / 4 undercount for CJK and emoji. The complete JSON body
            // intentionally contributes structural/tool overhead.
            val estimate =
                ((body.bytes + TOKEN_ESTIMATE_BYTES - 1) / TOKEN_ESTIMATE_BYTES)
                    .coerceAtLeast(1)
                    .toLong()
            deps.log("[${provider.key}] count_tokens estimate=$estimate (local; no upstream turn)\n")
            call.respondText(
                buildJsonObject { put("input_tokens", estimate) }.toString(),
                ContentType.Application.Json,
            )
        }
    }
}
