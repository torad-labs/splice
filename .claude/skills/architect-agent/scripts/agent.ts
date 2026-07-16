/**
 * Architect Agent — Main Entry
 *
 * ToolLoopAgent that runs the five-block pipeline:
 *   DECOMPOSE → COUPLE → PARTITION → SCAFFOLD → VALIDATE
 *
 * The agent uses prepareStep to:
 *   1. Route models per block (cognitive shape matching)
 *   2. Gate tools per step (only the current pipeline block is visible)
 *   3. Force tool choice (deterministic pipeline, not LLM discretion)
 *
 * If VALIDATE fails, the pipeline re-runs with violations as feedback.
 * Maximum 2 retry cycles before surfacing the best-effort result.
 *
 * SDK invariants:
 *   I-1: Agent IS a ToolLoopAgent
 *   I-5: Config aggregates on agent constructor + prepareStep
 *   I-7: Loop control via stopWhen (custom StopCondition)
 *   I-10: abortSignal propagated to all generate() calls
 */

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { valibotSchema } from "@ai-sdk/valibot";
import { stepCountIs, ToolLoopAgent } from "ai";
import { createArchitectTelemetry, withArchitectMiddleware } from "./middleware/telemetry.js";
import { CrystallizeInputSchema } from "./schemas.js";
import {
  createCoupleTool,
  createDecomposeTool,
  createExtractTool,
  createPartitionTool,
  createScaffoldTool,
  createValidateTool,
  type ToolModels,
  type ToolSystemPrompts,
} from "./tools/pipeline.js";

const __dirname = dirname(fileURLToPath(import.meta.url));

// ─── Agent File Loading ──────────────────────────────────────────────────────
// Each pipeline block loads its system prompt from agents/*.md.
// The .md files ARE the prompt engineering layer — editable without touching TS.

function loadAgentFiles(agentsDir?: string): ToolSystemPrompts {
  const dir = agentsDir ?? join(__dirname, "..", "agents");
  return {
    decomposer: readFileSync(join(dir, "decomposer.md"), "utf-8"),
    coupler: readFileSync(join(dir, "coupler.md"), "utf-8"),
    scaffolder: readFileSync(join(dir, "scaffolder.md"), "utf-8"),
    validator: readFileSync(join(dir, "validator.md"), "utf-8"),
    extractor: readFileSync(join(dir, "extractor.md"), "utf-8"),
  };
}

// ─── Pipeline Stage Definitions ──────────────────────────────────────────────

interface PipelineStage {
  toolName: string;
  modelKey: keyof ToolModels;
}

const PIPELINE_STAGES: PipelineStage[] = [
  { toolName: "decompose", modelKey: "decompose" },
  { toolName: "couple", modelKey: "couple" },
  { toolName: "partition", modelKey: "decompose" }, // deterministic, model unused
  { toolName: "scaffold", modelKey: "scaffold" },
  { toolName: "validate", modelKey: "validate" },
];

// Review mode: EXTRACT replaces DECOMPOSE as the first step
const REVIEW_PIPELINE_STAGES: PipelineStage[] = [
  { toolName: "extract", modelKey: "decompose" },
  { toolName: "couple", modelKey: "couple" },
  { toolName: "partition", modelKey: "decompose" },
  { toolName: "scaffold", modelKey: "scaffold" },
  { toolName: "validate", modelKey: "validate" },
];

// Max pipeline cycles (initial + retries)
const MAX_CYCLES = 2;
const STEPS_PER_CYCLE = PIPELINE_STAGES.length;
const MAX_STEPS = MAX_CYCLES * STEPS_PER_CYCLE;

// ─── Agent Factory ───────────────────────────────────────────────────────────

export interface ArchitectAgentConfig {
  models: ToolModels;
  devTools?: boolean;
  /** Path to agents/ directory. Defaults to ../agents relative to this file. */
  agentsDir?: string;
  onBlockComplete?: (metrics: {
    blockName: string;
    durationMs: number;
    inputTokens: number;
    outputTokens: number;
    costEstimateUsd: number;
    coherenceScore?: number;
  }) => void | Promise<void>;
}

/**
 * Create the architect agent.
 *
 * Usage:
 * ```ts
 * import { anthropic } from "@ai-sdk/anthropic";
 *
 * const agent = createArchitectAgent({
 *   models: {
 *     decompose: anthropic("claude-sonnet-4-5-20250929"),
 *     couple:    anthropic("claude-haiku-4-5-20251001"),
 *     scaffold:  anthropic("claude-opus-4-6"),
 *     validate:  anthropic("claude-sonnet-4-5-20250929"),
 *   },
 * });
 *
 * const result = await agent.generate({
 *   prompt: JSON.stringify({
 *     title: "User Authentication System",
 *     description: "JWT-based auth with OAuth2 social login...",
 *     constraints: ["PostgreSQL", "< 200ms latency"],
 *   }),
 * });
 * ```
 */
export function createArchitectAgent(config: ArchitectAgentConfig) {
  const { models, devTools = true, agentsDir, onBlockComplete } = config;

  // Load system prompts from agents/*.md files
  const prompts = loadAgentFiles(agentsDir);

  // Wrap all models with architect middleware
  const wrappedModels: ToolModels = {
    decompose: withArchitectMiddleware(models.decompose, { devTools }),
    couple: withArchitectMiddleware(models.couple, { devTools }),
    scaffold: withArchitectMiddleware(models.scaffold, {
      devTools,
      maxOutputTokens: 8192, // Together AI max output tokens for Llama 3.3
    }),
    validate: withArchitectMiddleware(models.validate, { devTools }),
  };

  // Create tools with injected models AND system prompts from agents/*.md
  const tools = {
    decompose: createDecomposeTool(wrappedModels.decompose, prompts.decomposer),
    couple: createCoupleTool(wrappedModels.couple, prompts.coupler),
    partition: createPartitionTool(),
    scaffold: createScaffoldTool(wrappedModels.scaffold, prompts.scaffolder),
    validate: createValidateTool(wrappedModels.validate, prompts.validator),
    extract: createExtractTool(wrappedModels.decompose, prompts.extractor),
  };

  // Telemetry integration
  const { integration: telemetry, getMetrics } = createArchitectTelemetry({
    onBlockComplete,
  });

  // The agent
  const agent = new ToolLoopAgent({
    id: "architect-agent",
    model: wrappedModels.decompose, // Default model, overridden per step
    instructions: AGENT_INSTRUCTIONS,
    tools,
    stopWhen: stepCountIs(MAX_STEPS),

    // Typed runtime options: the three crystallize documents
    callOptionsSchema: valibotSchema(CrystallizeInputSchema),

    // Per-call setup: inject crystallize context into instructions
    prepareCall: async ({ options, ...settings }) => {
      // biome-ignore lint/suspicious/noExplicitAny: SDK callOptionsSchema produces a union type not expressible statically
      const opts = options as any;
      const isReview = !opts.spec;
      const crystallizeContext = [
        `# ${isReview ? "Review" : "Crystallize"} Input: ${options.title}`,
        isReview ? "Mode: review" : `Mode: ${options.mode}`,
        !isReview && opts.slug ? `Slug: ${opts.slug}` : null,
        "",
        isReview
          ? "Use the EXTRACT tool first. This is review mode — read the source files and identify existing concerns, SDK violations, and architectural issues."
          : "The DECOMPOSE tool will receive the full spec and rejection documents.\nPass the crystallize input directly to the decompose tool.",
        "",
        isReview ? null : `Spec document length: ${opts.spec.length} chars`,
        isReview ? null : `Rejection document length: ${opts.rejection.length} chars`,
        isReview && opts.sourceFiles ? `Source files: ${opts.sourceFiles.length} files` : null,
        opts.additionalConstraints && opts.additionalConstraints.length > 0
          ? `Additional constraints: ${opts.additionalConstraints.join("; ")}`
          : null,
      ]
        .filter(Boolean)
        .join("\n");

      return {
        ...settings,
        instructions: `${AGENT_INSTRUCTIONS}\n\n---\n\n${crystallizeContext}`,
        experimental_context: { isReview },
      };
    },

    // Per-step configuration: model routing + tool gating
    // biome-ignore lint/suspicious/noExplicitAny: SDK prepareStep signature requires dynamic typing for experimental_context
    prepareStep: (async ({ stepNumber, experimental_context }: any) => {
      const ctx = experimental_context as { isReview?: boolean } | undefined;
      const pipeline = ctx?.isReview ? REVIEW_PIPELINE_STAGES : PIPELINE_STAGES;
      const cycleStep = stepNumber % STEPS_PER_CYCLE;
      const stage = pipeline[cycleStep];

      if (!stage) return {};

      return {
        model: wrappedModels[stage.modelKey],
        activeTools: [stage.toolName],
        toolChoice: { type: "tool" as const, toolName: stage.toolName },
        experimental_context: ctx,
      };
      // biome-ignore lint/suspicious/noExplicitAny: SDK prepareStep return type requires cast
    }) as any,

    // Lifecycle callbacks for observability
    onStepFinish: async (event) => {
      // biome-ignore lint/suspicious/noExplicitAny: SDK event type lacks experimental_context in public typings
      const ctx = (event as any).experimental_context as { isReview?: boolean } | undefined;
      const pipeline = ctx?.isReview ? REVIEW_PIPELINE_STAGES : PIPELINE_STAGES;
      const cycleStep = event.stepNumber % STEPS_PER_CYCLE;
      const stage = pipeline[cycleStep];
      console.log(
        `[architect] Step ${event.stepNumber}: ${stage?.toolName ?? "unknown"} ` +
          `(${event.usage?.inputTokens ?? 0} in / ${event.usage?.outputTokens ?? 0} out)`
      );
    },

    experimental_telemetry: {
      isEnabled: true,
      functionId: "architect-pipeline",
      metadata: { version: "1.0.0" },
      integrations: [telemetry],
    },
  });

  return { agent, getMetrics };
}

// ─── Agent Instructions ──────────────────────────────────────────────────────

const AGENT_INSTRUCTIONS = `You are an architectural decomposition agent. You receive crystallize pipeline output (spec + rejection documents) and transform it into a validated, production-ready AI SDK v6 agent scaffold.

INPUT:
You receive crystallize output via prepareCall options:
- spec: The constructive document (spec.md or prd.html) — requirements, architecture, data flow, build sequence
- rejection: The boundary document (rejection.md or rejection.html) — anti-patterns, drift risks, constraints

OUTPUT:
A typed scaffold that a code agent can implement directly — tool definitions, agent configs, middleware, telemetry.

PIPELINE:
1. DECOMPOSE: Extract irreducible concerns from the spec + rejection docs. Requirements become concerns. Rejection boundaries become invariants. Flag atomicity signals.
2. COUPLE: Compute Hamiltonian coupling matrix (data, state, domain, boundary) + detect hyperedges (multi-way constraints).
3. PARTITION: Louvain modularity maximization → agent hierarchy. Pass hyperedges from COUPLE — they are hard constraints.
4. SCAFFOLD: Generate tool(), ToolLoopAgent, middleware, and telemetry definitions.
5. VALIDATE: Check coherence against both the Hamiltonian AND the crystallize documents. Every spec requirement must have a tool. Every rejection boundary must have an invariant.

RULES:
- Call tools in exact pipeline order.
- DECOMPOSE receives the full crystallize input (spec + rejection + existingTypes + constraints).
- COUPLE receives the decompose output.
- PARTITION receives the couple output. IMPORTANT: pass the hyperedges array from COUPLE output to PARTITION.
- SCAFFOLD receives the partition output.
- VALIDATE receives scaffold + partition + the original crystallize input + hyperedges from COUPLE. Forward hyperedges explicitly — without them, the hyperedge_cut check is skipped.
- If VALIDATE returns pass=false, re-run from DECOMPOSE with violations as feedback.
- Maximum 2 full cycles.

CRITICAL — CRYSTALLIZE DOCUMENTS ARE THE SOURCE OF TRUTH:
The spec document already contains requirements, dependencies, and build sequence.
The rejection document already contains anti-patterns and boundaries.
DECOMPOSE refines these into typed concerns — it does NOT start from scratch.
If the spec says R1 depends on R2, the coupling matrix should reflect that.
If the rejection says "do not X", at least one concern must have an invariant preventing X.

REVIEW MODE:
If the input contains sourceFiles instead of spec+rejection, this is a REVIEW of existing code.
Use EXTRACT instead of DECOMPOSE. EXTRACT reads the TypeScript source files and reverse-decomposes
them into concerns — the same output format as DECOMPOSE.
Pipeline in review mode: EXTRACT → COUPLE → PARTITION → SCAFFOLD → VALIDATE.
The diff between SCAFFOLD output (what the code SHOULD look like) and the EXTRACT output
(what the code ACTUALLY looks like) IS the remediation plan.
EXTRACT also produces sdkViolations — these are immediate fixes independent of the architecture.`;

// ─── Convenience: Run from Crystallize Output ────────────────────────────────

export interface CrystallizeInput {
  mode: "feature" | "product";
  title: string;
  slug?: string;
  /** spec.md or prd.html content */
  spec: string;
  /** rejection.md or rejection.html content */
  rejection: string;
  existingTypes?: Array<{
    name: string;
    description: string;
    fields: Array<{ name: string; type: string; description: string; optional?: boolean }>;
  }>;
  additionalConstraints?: string[];
}

export async function architectFromCrystallize(
  config: ArchitectAgentConfig,
  input: CrystallizeInput,
  options?: { abortSignal?: AbortSignal }
) {
  const { agent, getMetrics } = createArchitectAgent(config);

  const result = await agent.generate({
    // biome-ignore lint/suspicious/noExplicitAny: CrystallizeInput is a superset of callOptionsSchema union
    options: input as any,
    prompt: JSON.stringify(input),
    abortSignal: options?.abortSignal,
  });

  return {
    text: result.text,
    steps: result.steps,
    metrics: getMetrics(),
    usage: result.usage,
  };
}
// ─── Convenience: Review Existing Agent Code ─────────────────────────────────

export interface ReviewInput {
  title: string;
  description?: string;
  sourceFiles: Array<{ path: string; content: string }>;
  additionalConstraints?: string[];
}

export async function reviewExistingAgent(
  config: ArchitectAgentConfig,
  input: ReviewInput,
  options?: { abortSignal?: AbortSignal }
) {
  const { agent, getMetrics } = createArchitectAgent(config);

  // Review mode: the agent instructions tell the LLM to use EXTRACT instead of DECOMPOSE
  const result = await agent.generate({
    // biome-ignore lint/suspicious/noExplicitAny: ReviewInput coerced to callOptionsSchema union for review mode
    options: input as any, // Type coercion — the agent handles both shapes
    prompt: JSON.stringify({
      ...input,
      _mode: "review", // Signal to the agent that this is review mode
    }),
    abortSignal: options?.abortSignal,
  });

  return {
    text: result.text,
    steps: result.steps,
    metrics: getMetrics(),
    usage: result.usage,
  };
}

// ─── Direct Pipeline Runner (bypasses ToolLoopAgent) ─────────────────────────
// Used when the outer LLM orchestrator is unreliable (e.g. DeepSeek V3 on
// Together AI puts tool calls in text field for large inputs).
// Each pipeline block is called directly — no LLM routing needed since
// the review pipeline is always: EXTRACT → COUPLE → PARTITION → SCAFFOLD → VALIDATE.

export async function reviewExistingAgentDirect(
  config: ArchitectAgentConfig,
  input: ReviewInput,
  options?: { abortSignal?: AbortSignal }
) {
  const { models, devTools = true, agentsDir, onBlockComplete } = config;
  const prompts = loadAgentFiles(agentsDir);

  const wrappedModels = {
    decompose: withArchitectMiddleware(models.decompose, { devTools }),
    couple: withArchitectMiddleware(models.couple, { devTools }),
    scaffold: withArchitectMiddleware(models.scaffold, { devTools, maxOutputTokens: 8192 }),
    validate: withArchitectMiddleware(models.validate, { devTools }),
  };

  const tools = {
    extract: createExtractTool(wrappedModels.decompose, prompts.extractor),
    couple: createCoupleTool(wrappedModels.couple, prompts.coupler),
    partition: createPartitionTool(),
    scaffold: createScaffoldTool(wrappedModels.scaffold, prompts.scaffolder),
    validate: createValidateTool(wrappedModels.validate, prompts.validator),
  };

  const t0 = Date.now();
  const abortSignal = options?.abortSignal;

  // Step 1: EXTRACT
  console.log("[architect-direct] Running EXTRACT...");
  const t1 = Date.now();
  const extracted = await tools.extract.execute?.(
    { ...input, additionalConstraints: input.additionalConstraints ?? [] },
    // biome-ignore lint/suspicious/noExplicitAny: tool.execute context type not publicly exported
    { abortSignal } as any
  );
  const extractMs = Date.now() - t1;
  console.log(`[architect-direct] EXTRACT done (${extractMs}ms)`);
  onBlockComplete?.({
    blockName: "extract",
    durationMs: extractMs,
    inputTokens: 0,
    outputTokens: 0,
    costEstimateUsd: 0,
  });

  // Step 2: COUPLE
  console.log("[architect-direct] Running COUPLE...");
  const t2 = Date.now();
  const coupled = await tools.couple.execute?.(
    {
      // biome-ignore lint/suspicious/noExplicitAny: tool execute returns untyped result
      concerns: (extracted as any).concerns ?? [],
      // biome-ignore lint/suspicious/noExplicitAny: tool execute returns untyped result
      sharedTypes: (extracted as any).sharedTypes ?? [],
    },
    // biome-ignore lint/suspicious/noExplicitAny: tool.execute context type not publicly exported
    { abortSignal } as any
  );
  const coupleMs = Date.now() - t2;
  console.log(`[architect-direct] COUPLE done (${coupleMs}ms)`);
  onBlockComplete?.({
    blockName: "couple",
    durationMs: coupleMs,
    inputTokens: 0,
    outputTokens: 0,
    costEstimateUsd: 0,
  });

  // Step 3: PARTITION
  console.log("[architect-direct] Running PARTITION...");
  const t3 = Date.now();
  const partition = await tools.partition.execute?.(
    {
      // biome-ignore lint/suspicious/noExplicitAny: tool execute returns untyped result
      concerns: (coupled as any).concerns ?? [],
      // biome-ignore lint/suspicious/noExplicitAny: tool execute returns untyped result
      sharedTypes: (coupled as any).sharedTypes ?? [],
      // biome-ignore lint/suspicious/noExplicitAny: tool execute returns untyped result
      matrix: (coupled as any).matrix ?? [],
      // biome-ignore lint/suspicious/noExplicitAny: tool execute returns untyped result
      hyperedges: (coupled as any).hyperedges ?? [],
    },
    // biome-ignore lint/suspicious/noExplicitAny: tool.execute context type not publicly exported
    { abortSignal } as any
  );
  const partitionMs = Date.now() - t3;
  console.log(`[architect-direct] PARTITION done (${partitionMs}ms)`);
  onBlockComplete?.({
    blockName: "partition",
    durationMs: partitionMs,
    inputTokens: 0,
    outputTokens: 0,
    costEstimateUsd: 0,
  });

  // Step 4: SCAFFOLD
  console.log("[architect-direct] Running SCAFFOLD...");
  const t4 = Date.now();
  const scaffold = await tools.scaffold.execute?.(
    {
      // biome-ignore lint/suspicious/noExplicitAny: tool execute returns untyped result
      agents: (partition as any).agents ?? [],
      // biome-ignore lint/suspicious/noExplicitAny: tool execute returns untyped result
      concerns: (partition as any).concerns ?? [],
      // biome-ignore lint/suspicious/noExplicitAny: tool execute returns untyped result
      sharedTypes: (partition as any).sharedTypes ?? [],
      // biome-ignore lint/suspicious/noExplicitAny: tool execute returns untyped result
      modularity: (partition as any).modularity ?? 0,
    },
    // biome-ignore lint/suspicious/noExplicitAny: tool.execute context type not publicly exported
    { abortSignal } as any
  );
  const scaffoldMs = Date.now() - t4;
  console.log(`[architect-direct] SCAFFOLD done (${scaffoldMs}ms)`);
  onBlockComplete?.({
    blockName: "scaffold",
    durationMs: scaffoldMs,
    inputTokens: 0,
    outputTokens: 0,
    costEstimateUsd: 0,
  });

  // Step 5: VALIDATE
  console.log("[architect-direct] Running VALIDATE...");
  const t5 = Date.now();
  const validate = await tools.validate.execute?.(
    {
      scaffold,
      partition,
      crystallizeInput: input,
      // biome-ignore lint/suspicious/noExplicitAny: tool execute returns untyped result
      hyperedges: (coupled as any).hyperedges ?? [],
    },
    // biome-ignore lint/suspicious/noExplicitAny: tool.execute context type not publicly exported
    { abortSignal } as any
  );
  const validateMs = Date.now() - t5;
  // biome-ignore lint/suspicious/noExplicitAny: tool execute returns untyped result
  const coherenceScore = (validate as any).coherenceScore;
  console.log(`[architect-direct] VALIDATE done (${validateMs}ms)`);
  onBlockComplete?.({
    blockName: "validate",
    durationMs: validateMs,
    inputTokens: 0,
    outputTokens: 0,
    costEstimateUsd: 0,
    coherenceScore,
  });

  const totalMs = Date.now() - t0;
  console.log(`[architect-direct] Pipeline complete in ${totalMs}ms`);

  return {
    extract: extracted,
    couple: coupled,
    partition,
    scaffold,
    validate,
  };
}
