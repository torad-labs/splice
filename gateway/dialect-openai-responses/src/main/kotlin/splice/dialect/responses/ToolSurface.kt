// NEW: the deferred tool surface for responses-lite (gpt-5.6 family). splice forwards Claude Code's
// FULL tool list — measured 64-87 function tools per request, median upstream body 399KB, p90 1.1MB —
// while OpenAI's own client serves these models a collapsed surface (models.json gives gpt-5.6-sol
// supports_search_tool:true, and core/src/client.rs:838-921 wraps ToolSpec::ToolSearch into the same
// additional_tools developer item splice already emits). This file owns the PARTITION and the wire
// tool objects; the answering side lives in ResponsesToolSearch.kt, and the shape-400 recovery lives
// in ToolSurfaceRecovery.kt (split 2026-07-24: this file hit the SAME TooManyFunctions ceiling
// ResponsesLite.kt was originally split from ResponsesRequestBuilder to avoid).
// Invariants:
//   - PURE and ORDER-STABLE: the partition is a function of (body.tools, policy) ONLY — no
//     transcript signal participates, so an identical client request produces identical bytes and
//     the additional_tools payload (position 0 of the lite input array, ResponsesLite.kt liteInput)
//     never moves mid-conversation. This used to be false: a prior cut promoted any tool NAMED BY a
//     ToolUseBlock to always-eager ("the R2 wall", via warmToolNames) as the stateless replacement
//     for codex's session-held loaded-tool set. Removed 2026-07-25: the client's tool list is
//     otherwise stable across a conversation, so that promotion flipped the tools[] set — and
//     therefore additional_tools' bytes — the FIRST time the model actually used a previously
//     deferred tool, busting the ENTIRE cached prefix (measured: ~94K re-billed tokens against a
//     few-K-to-15K/request deferral saving; one bust erases dozens of requests of savings and
//     recurs per distinct newly-used deferred tool). codex-rs never does this: it does not promote
//     a searched tool into the tools array, ever — see the permanent regression test at codex-rs
//     core/tests/suite/search_tool.rs:782-814 ("follow-up request should rely on
//     tool_search_output history, not tool injection" / "...not namespace injection"). The model
//     is meant to learn a deferred tool's schema FROM HISTORY (tool_search_output), never from a
//     moving tools array. [warmToolNames] is KEPT — it now feeds ResponsesRequestBuilder.kt's
//     declaration-replay instead: a deferred tool a ToolUseBlock already named gets its full
//     schema re-declared IN HISTORY, immediately before that function_call, which closes the same
//     "replayed history references an undeclared tool" failure mode without ever touching
//     additional_tools;
//   - OFF (quirks.toolSurface == null), non-lite, compact, latch-closed, or below the minDeferred
//     floor => (all eager, none deferred), byte-identical to today (ResponsesContractTest pins it);
//   - deferred tools are ABSENT from the request entirely — defer_loading never rides the request
//     side, only the tool_search_output (codex core/tests/suite/search_tool.rs:723-741).
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.wire.AnthropicRequest
import splice.core.wire.ToolDefinition
import splice.core.wire.ToolUseBlock
import java.util.concurrent.atomic.AtomicBoolean

/** Per-provider deferred-tool-surface policy — governs whether/how a request's `tools` gets
 *  PARTITIONed into an eager set (declared normally in `additional_tools`) and a deferred set
 *  (answered on demand via `tool_search`; see this file's header). Overlaid from the operator-
 *  facing TOML `[providers.*.quirks.tool_surface]` table via [withToolSurfaceToml] into
 *  [ResponsesQuirks.toolSurface]. An ABSENT policy (`toolSurface == null`) means the feature is
 *  OFF — every request is byte-identical to pre-feature status quo (all tools eager, no
 *  tool_search object); there is no "configured but inert" state between off and active. */
public data class ToolDeferralPolicy(
    val deferPrefixes: List<String> = listOf(MCP_PREFIX),
    /** Names FORCED deferred regardless of prefix — the Agent/Task availability brake. */
    val defer: Set<String> = emptySet(),
    /** Names FORCED eager; wins over every defer rule. */
    val eager: Set<String> = emptySet(),
    /** Below this many deferrable tools the split buys nothing and costs a round. */
    val minDeferred: Int = DEFAULT_MIN_DEFERRED,
    /** Max tools returned by one `tool_search` answer (clamped 1..this —
     *  [ResponsesToolSearchController.clampedLimit]). Trades answer size against round count: a
     *  HIGHER limit answers a broad query in fewer rounds at the cost of a bigger
     *  tool_search_output payload every round; a LOWER limit keeps each round's answer small but
     *  pushes more queries toward [searchRounds]'s exhaustive fallback. */
    val searchLimit: Int = DEFAULT_SEARCH_LIMIT,
    /** Permitted `tool_search` rounds before the FINAL round answers with the ENTIRE deferred set
     *  regardless of query — the loop-can't-wedge law (ResponsesToolSearch.kt header): capability
     *  at the cap is exactly today's full surface. Trades round budget against when narrowing
     *  gives up: MORE rounds lets ranked, limit-sized answers keep narrowing longer before the
     *  exhaustive fallback; FEWER rounds reaches the larger, unranked exhaustive answer sooner. */
    val searchRounds: Int = DEFAULT_SEARCH_ROUNDS,
)

/** The per-request split. [eager] PRESERVES body.tools order — a reordering breaks the
 *  prompt-cache prefix when Claude Code retries the identical request. */
internal data class ToolPartition(val eager: List<ToolDefinition>, val deferred: List<ToolDefinition>) {
    val deferring: Boolean get() = deferred.isNotEmpty()
}

/** The capability latch — the ENTIRE persistent state of this design: one bit per provider
 *  instance, moving in exactly ONE direction (toward status quo). Closed by a backend rejection
 *  of the tool-surface shape; a daemon restart re-opens it, so a transient backend change costs
 *  one re-probe per lifetime, never a permanent silent downgrade.
 *
 *  Honesty gap, accepted (review 2026-07-25, options 1+2 over 3): the turn that TRIGGERS the
 *  close is itself served on a degraded surface, not full status quo. [ResponsesProvider.
 *  amendBodyOnFailure]'s signature is `(status, responseText, bodyJson)` — the already-serialized
 *  wire body, never the [ToolPartition] or [ToolDeferralPolicy] that produced it — so the amend
 *  path can strip the invented tool_search shape but cannot reconstruct and re-attach the
 *  deferred tools' full schemas (they never rode the request by design; this file's header). That
 *  ONE recovery turn therefore completes eager-only: one turn below full status quo. Every LATER
 *  turn on this provider instance reads [open] == false and builds the full eager set from the
 *  start via [partitionTools]'s `!opts.toolSurfaceOpen -> allEager` branch — true status quo.
 *  Cost: one degraded-but-successful turn per provider instance per daemon lifetime. Restoring
 *  the full surface on the amend path itself (option 3) was rejected: it would touch the same
 *  shared amend seam RC-4's stale-reasoning recovery uses. [close] returns whether THIS call
 *  performed the actual transition so the caller can fire its one observable signal exactly once
 *  per instance, never once per amend attempt. */
internal class ToolSurfaceLatch {
    private val openFlag = AtomicBoolean(true)
    val open: Boolean get() = openFlag.get()

    /** CAS, not a plain set: only the open->closed transition returns true, so a caller racing
     *  two amend attempts (or any future re-entry) fires its visibility signal exactly once. */
    fun close(): Boolean = openFlag.compareAndSet(true, false)
}

/** Overlay the head's TOML `[providers.*.quirks.tool_surface]` table — a DIRECT set, not the
 *  null-preserves-base merge [withReasoningCacheToml] uses: toolSurface's null means literally
 *  OFF (the field's own KDoc), and no provider's defaultQuirks() ever presets a non-null base to
 *  inherit from, so a direct set is both simpler and exactly as correct. Chained (not folded into
 *  [withToml]) because that function already sits at detekt's complexity ceiling. */
public fun ResponsesQuirks.withToolSurfaceToml(policy: ToolDeferralPolicy?): ResponsesQuirks =
    copy(toolSurface = policy)

/** The partition gate: SEQUENTIAL early returns via a `when` ladder, never a compound boolean —
 *  the decision has five clauses and ComplexCondition fails at 3 operands. */
internal fun ResponsesQuirks.partitionTools(body: AnthropicRequest, opts: BuildOptions): ToolPartition {
    val allEager = ToolPartition(body.tools, emptyList())
    val policy = toolSurface
    return when {
        policy == null -> allEager
        !opts.toolSurfaceOpen -> allEager // latch closed
        !isLite(opts) -> allEager // compact is subsumed: isLite is already !compact && …
        else -> partitionWithPolicy(body, policy, allEager)
    }
}

private fun partitionWithPolicy(
    body: AnthropicRequest,
    policy: ToolDeferralPolicy,
    allEager: ToolPartition,
): ToolPartition {
    val chosenName = body.toolChoice?.name
    val deferred = body.tools.filter { eligibleForDefer(it, policy, chosenName) }
    val deferredNames = deferred.mapTo(HashSet(deferred.size)) { it.name }
    val eager = body.tools.filter { it.name !in deferredNames }
    return when {
        deferred.size < policy.minDeferred -> allEager
        eager.isEmpty() -> allEager // degenerate-config guard
        else -> ToolPartition(eager, deferred)
    }
}

private fun eligibleForDefer(
    t: ToolDefinition,
    policy: ToolDeferralPolicy,
    chosenName: String?,
): Boolean = when {
    !deferrable(t, policy) -> false
    t.name in policy.eager -> false
    else -> t.name != chosenName
}

private fun deferrable(t: ToolDefinition, policy: ToolDeferralPolicy): Boolean =
    t.name in policy.defer || policy.deferPrefixes.any { t.name.startsWith(it) }

/** Any tool named by a ToolUseBlock anywhere in this transcript — a pure scan of the request
 *  Claude Code already resends every turn, so it is restart-proof and needs no cache, no TTL, no
 *  keys. Used to no longer promote these tools to eager (removed 2026-07-25, see this file's
 *  header): the intersection of this set with [ToolPartition.deferred] is now the input to
 *  ResponsesRequestBuilder.kt's declaration-replay — every deferred tool a ToolUseBlock already
 *  named gets its full schema re-declared in history, immediately before that tool's
 *  function_call, instead of forcing the tool eager (which moved additional_tools and busted the
 *  cached prefix). */
internal fun warmToolNames(body: AnthropicRequest): Set<String> =
    body.messages.asSequence()
        .flatMap { it.content.asSequence() }
        .filterIsInstance<ToolUseBlock>()
        .mapTo(HashSet()) { it.name }

/** [forceStrictFalse] (codex-rs parity: hard-sets `strict:false` on every function tool,
 *  responses_api.rs:29-32) and [emitStrict] (pass through a tool's own strict==true) are
 *  DISTINCT quirks, not one flag with two meanings (review 2026-07-24 round 2/3): GrokProvider's
 *  pre-existing `emitStrict = true` predates this feature and was never consequential (Claude
 *  Code's ToolDefinition.strict is always null), so folding the codex-only forced-false behavior
 *  into `emitStrict` silently changed grok's live wire bytes too — a head this feature must not
 *  touch. forceStrictFalse defaults false and only CodexProvider sets it. */
internal fun functionToolObject(t: ToolDefinition, emitStrict: Boolean, forceStrictFalse: Boolean): JsonObject =
    buildJsonObject {
        put(FIELD_TYPE, TYPE_FUNCTION)
        put(FIELD_NAME, t.name)
        put(FIELD_DESCRIPTION, t.description ?: "")
        put(FIELD_PARAMETERS, t.inputSchema ?: emptyObjectSchema())
        when {
            // forceStrictFalse is a HARD SET (codex-rs parity, responses_api.rs:29-32): every
            // function tool gets strict:false regardless of the tool's OWN value — passing
            // t.strict through here (review 2026-07-25) would send strict:true for a tool that
            // arrives with strict==true, breaking the exact parity this quirk exists for.
            forceStrictFalse -> put(FIELD_STRICT, false)
            emitStrict && t.strict == true -> put(FIELD_STRICT, true)
            else -> Unit
        }
    }

/** A deferred tool as it rides inside a tool_search_output.tools[] answer — carries the same
 *  fields as [functionToolObject] plus `defer_loading:true` (tool_search.rs:36-40). Authored
 *  standalone rather than composed from [functionToolObject]: JSON key order is semantically
 *  irrelevant to the API, but composing would put defer_loading last instead of adjacent to the
 *  other declared-shape fields, and this way the two builders stay independently readable. */
internal fun deferredToolObject(t: ToolDefinition, emitStrict: Boolean, forceStrictFalse: Boolean): JsonObject =
    buildJsonObject {
        put(FIELD_TYPE, TYPE_FUNCTION)
        put(FIELD_NAME, t.name)
        put(FIELD_DESCRIPTION, t.description ?: "")
        put(FIELD_DEFER_LOADING, true)
        put(FIELD_PARAMETERS, t.inputSchema ?: emptyObjectSchema())
        when {
            // Same hard-set law as functionToolObject's identical branch just above (review
            // 2026-07-25) — a deferred tool answered through tool_search gets forced strict:false
            // too, never a pass-through of its own strict==true.
            forceStrictFalse -> put(FIELD_STRICT, false)
            emitStrict && t.strict == true -> put(FIELD_STRICT, true)
            else -> Unit
        }
    }

private fun emptyObjectSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put(FIELD_PROPERTIES, buildJsonObject { })
}

/** Shape verbatim from codex core/src/tools/handlers/tool_search_spec.rs:63-76 (the
 *  ToolSearchSourceListing::Omit branch — splice has no MCP-server description metadata to
 *  list), MINUS the "with BM25" clause codex's copy carries: splice's ranking is a deterministic
 *  field-weighted substring score, not BM25 (spec §2.3; ToolSearchIndex's own header). Dropping
 *  that clause is deliberate, not a missed port. execution:"client" is what tells the backend WE
 *  answer the search, never Claude Code. [limit] is the operator's configured policy.searchLimit
 *  (threaded from the caller) so the advertised default matches what [ResponsesToolSearchController]
 *  actually clamps to (review 2026-07-24: this used to hardcode DEFAULT_SEARCH_LIMIT). */
internal fun toolSearchToolObject(limit: Int): JsonObject = buildJsonObject {
    put(FIELD_TYPE, TYPE_TOOL_SEARCH)
    put(FIELD_EXECUTION, EXECUTION_CLIENT)
    put(FIELD_DESCRIPTION, TOOL_SEARCH_DESCRIPTION)
    put(
        FIELD_PARAMETERS,
        buildJsonObject {
            put("type", "object")
            put(
                FIELD_PROPERTIES,
                buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("type", "string")
                            put(FIELD_DESCRIPTION, "Search query for deferred tools.")
                        },
                    )
                    put(
                        "limit",
                        buildJsonObject {
                            put("type", "number")
                            put(FIELD_DESCRIPTION, "Maximum number of tools to return. Defaults to $limit.")
                        },
                    )
                },
            )
            put("required", buildJsonArray { add(JsonPrimitive("query")) })
            put("additionalProperties", false)
        },
    )
}

/** The additional_tools array for this request: every eager tool, then (only when deferring) the
 *  tool_search tool. The deferred tools themselves never ride here (search_tool.rs:723-741). */
internal fun toolsSection(
    partition: ToolPartition,
    emitStrict: Boolean,
    forceStrictFalse: Boolean,
    searchLimit: Int,
): JsonArray = buildJsonArray {
    partition.eager.forEach { add(functionToolObject(it, emitStrict, forceStrictFalse)) }
    if (partition.deferring) add(toolSearchToolObject(searchLimit))
}

// Wire field/type literals shared by the request-side (this file) and the answer-side
// (ResponsesToolSearch.kt) — each private-per-file since StringLiteralDuplication scopes per file.
private const val MCP_PREFIX = "mcp__"
internal const val DEFAULT_MIN_DEFERRED = 8
internal const val DEFAULT_SEARCH_LIMIT = 8
internal const val DEFAULT_SEARCH_ROUNDS = 3

private const val FIELD_TYPE = "type"
private const val FIELD_NAME = "name"
private const val FIELD_DESCRIPTION = "description"
private const val FIELD_PARAMETERS = "parameters"
private const val FIELD_PROPERTIES = "properties"
private const val FIELD_STRICT = "strict"
private const val FIELD_DEFER_LOADING = "defer_loading"
private const val FIELD_EXECUTION = "execution"
private const val TYPE_FUNCTION = "function"
private const val TYPE_TOOL_SEARCH = "tool_search"
private const val EXECUTION_CLIENT = "client"

private const val TOOL_SEARCH_DESC_HEAD = "# Tool discovery\n\n" +
    "Searches over deferred tool metadata and exposes matching tools for the next model call.\n\n"
private const val TOOL_SEARCH_DESC_TAIL = "Some of the tools may not have been provided to you upfront, " +
    "and you should use this tool (`tool_search`) to search for the required tools. " +
    "For MCP tool discovery, always use `tool_search` instead of `list_mcp_resources` " +
    "or `list_mcp_resource_templates`."
private const val TOOL_SEARCH_DESCRIPTION = TOOL_SEARCH_DESC_HEAD + TOOL_SEARCH_DESC_TAIL
