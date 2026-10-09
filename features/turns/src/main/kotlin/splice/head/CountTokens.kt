// PORT-OF: splice/gateway/head/HeadServer.kt (handleCountTokens, TOKEN_ESTIMATE_BYTES) @ 1caedd6 —
// invariants unchanged: the NAMED CHANGE from the Node port — count_tokens is a purely LOCAL
// estimate that never becomes a quota-burning turn, takes NO turn-gate slot, and stays bounded by
// the fast-fail materialization lease plus maxRequestBytes. Split out (HD-24), deliberately NOT
// merged into HeadAdmission: it is not a turn, and that is the point.
package splice.head

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.PromptTokenEstimate
import splice.core.util.JsonWire
import splice.head.admission.AdmissionGate
import splice.head.admission.AdmissionResponses
import splice.head.admission.Materializing
import splice.head.turn.Materialized
import splice.upstream.Provider

internal class CountTokens(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val admission: AdmissionGate,
    private val bodyReader: RequestBodyReader,
    private val bodyParse: AnthropicBodyParse,
    private val responses: AdmissionResponses,
) {
    suspend fun handleCountTokens(call: ApplicationCall) {
        // NO turn-gate slot here: count_tokens is a purely LOCAL estimate (no upstream stream),
        // and queueing it on maxInflight let a saturated head stall or 529 Claude Code's
        // pre-flight sizing for minutes (review 2026-07-22). Memory stays bounded by the
        // materialization gate (fastFail: contention 529s instead of queueing, so a count_tokens
        // flood cannot camp the shared heap budget) plus the maxRequestBytes cap.
        val prepared = admission.materializeBodyOrRespond(call, Materializing(fastFail = true)) {
            when (val read = bodyReader.receiveBodyBounded(call, deps.policy.maxRequestBytes)) {
                // The 413 is the gate's to write, so the cap travels back as the case it is.
                is BodyRead.TooLarge -> Materialized.TooLarge(read.limit)
                is BodyRead.Received -> Materialized.Done(
                    bodyParse.parse(read.body.text).map { PromptTokenEstimate.fromBytes(read.body.bytes.toLong()) },
                )
            }
        } ?: return
        // Both success and invalid-body replies publish only after materialization returned its loan.
        val estimate = prepared.getOrNull()
        if (estimate == null) {
            responses.respondInvalidRequest(call, "invalid request body")
            return
        }
        // Conservative and Unicode-safe: UTF-8 bytes / 3 includes structural/tool overhead.
        deps.log("[${provider.key}] count_tokens estimate=$estimate (local; no upstream turn)\n")
        call.respondText(
            JsonWire.string(buildJsonObject { put("input_tokens", estimate) }),
            ContentType.Application.Json,
        )
    }
}
