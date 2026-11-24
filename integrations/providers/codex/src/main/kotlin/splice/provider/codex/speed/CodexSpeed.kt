// NEW: gpt-speed (docs/specs/gpt-speed.md, decision 4) — every request of a session at Fast or Ultrafast asks the
// ChatGPT backend for its tier the way codex-rs does on a ChatGPT login (rust-v0.169.0):
//   - the body carries the tier's id as `service_tier` (core/src/client.rs, build_request), and no `service_tier` at
//     all at standard speed;
//   - the routing hint names it: `x-codex-routing-hint: model=<slug>;tier=<id>`, or `model=<slug>` without one
//     (client.rs, build_routing_hint_header). This is the hint's one author: CodexRoutingHeaders writes the session
//     and thread headers and leaves the hint here, so no request carries one that this overwrites;
//   - a tier the turn's model does not list in its catalog entry is not sent (protocol/src/openai_models.rs,
//     ModelInfo::service_tier_for_request).
//
// The tier is the SESSION's, read as each request is built, so a subagent's request, a compaction and a turn not
// streamed carry it alike. It never varies by turn type: like reasoning effort it is part of what the backend caches the
// prompt under, and a compaction asked at another tier would read the whole transcript cold.
//
// The session's own turns (not a subagent's) also record the model they run on, which the speed commands read as the
// session's model.
package splice.provider.codex.speed

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.speed.SERVICE_TIER
import splice.core.speed.SessionSpeeds
import splice.core.speed.SpeedCatalog
import splice.upstream.BuiltTurn

private const val HEADER_ROUTING_HINT = "x-codex-routing-hint"

/** One head's reading of each session's speed onto its requests. The default asks for no tier, ever. */
public class CodexSpeed(
    private val speeds: SessionSpeeds = SessionSpeeds(),
    private val catalog: SpeedCatalog? = null,
    private val head: String = "",
) {
    /** [turn] as session [sessionId] asks for it: its tier in the body, or none, and the routing hint every request
     *  carries, naming the tier when there is one. [agentId] is the subagent the turn is for, null for the session's
     *  own turn. */
    public fun applied(turn: BuiltTurn, sessionId: String?, agentId: String?): BuiltTurn {
        val model = turn.meta.route.upstreamModel
        if (agentId == null) speeds.ran(sessionId, model, provider = head.takeIf(String::isNotBlank))
        val tier = catalog?.tierFor(head, model, speeds.speedOf(sessionId))
        val body = if (tier == null) {
            JsonObject(turn.requestBody - SERVICE_TIER)
        } else {
            JsonObject(turn.requestBody + (SERVICE_TIER to JsonPrimitive(tier.id)))
        }
        val hint = if (tier == null) "model=$model" else "model=$model;tier=${tier.id}"
        return turn.copy(requestBody = body, extraHeaders = turn.extraHeaders + (HEADER_ROUTING_HINT to hint))
    }
}
