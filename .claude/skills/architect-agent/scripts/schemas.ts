/**
 * Architect Agent — Schemas
 *
 * Every type in the pipeline is defined here as a Valibot schema.
 * Tools import schemas, never define their own types.
 * The membrane holds the contract.
 *
 * Integration: valibotSchema() wraps these for AI SDK tool() and Output.
 */

import * as v from "valibot";

// ─── Concern Types ───────────────────────────────────────────────────────────
// Irreducible architectural basis. The LLM classifies into this set.
// It does not invent concern types. Stability comes from a fixed basis.

export const ConcernType = v.picklist([
  "domain", // Pure business logic, no I/O
  "command", // State-mutating operation
  "query", // Read operation
  "event", // Side effect triggered by state change
  "adapter_in", // External → internal boundary
  "adapter_out", // Internal → external boundary
  "transformer", // Data shape conversion, pure
  "validator", // Constraint enforcement, returns pass/fail
  "policy", // Decision logic selecting between paths
]);
export type ConcernType = v.InferOutput<typeof ConcernType>;

export const StateRequirement = v.picklist(["stateless", "session", "persistent"]);
export type StateRequirement = v.InferOutput<typeof StateRequirement>;

// ─── Shared Type Definition ──────────────────────────────────────────────────
// Data types that flow between concerns. These become the Valibot schemas
// in the scaffolded tool definitions.

export const TypeFieldSchema = v.object({
  name: v.pipe(v.string(), v.description("Field name")),
  type: v.pipe(v.string(), v.description("TypeScript type expression")),
  optional: v.optional(v.boolean(), false),
  description: v.pipe(v.string(), v.description("What this field represents")),
});

export const SharedTypeSchema = v.object({
  name: v.pipe(v.string(), v.description("Type name, PascalCase")),
  description: v.pipe(v.string(), v.description("What this type represents")),
  fields: v.array(TypeFieldSchema),
});
export type SharedType = v.InferOutput<typeof SharedTypeSchema>;

// ─── Concern ─────────────────────────────────────────────────────────────────
// A single irreducible architectural responsibility extracted from a feature.

export const ConcernSchema = v.object({
  id: v.pipe(v.string(), v.description("Unique concern ID, e.g. 'auth-validate'")),
  name: v.pipe(v.string(), v.description("Human-readable concern name")),
  type: ConcernType,
  description: v.pipe(v.string(), v.description("What this concern does — one sentence")),
  reads: v.pipe(v.array(v.string()), v.description("Type names this concern consumes")),
  writes: v.pipe(v.array(v.string()), v.description("Type names this concern produces")),
  state: StateRequirement,
  invariants: v.pipe(
    v.array(v.string()),
    v.description("Business rules this concern must enforce")
  ),
  externalSystems: v.pipe(
    v.optional(v.array(v.string()), []),
    v.description("External APIs, DBs, or services this concern touches")
  ),
  atomicity: v.pipe(
    v.optional(
      v.object({
        group: v.pipe(
          v.string(),
          v.description("Atomic group ID — concerns sharing a group MUST be co-located")
        ),
        signal: v.pipe(
          v.string(),
          v.description("Source text from spec/rejection that signals atomicity")
        ),
        type: v.picklist(["transaction", "invariant", "ordering"]),
      })
    ),
    v.description(
      "Structured atomicity flag. Set when spec/rejection says 'atomic', " +
        "'all-or-nothing', 'must not separate', 'transactional', or implies " +
        "ordering dependency. Feeds hyperedge detection."
    )
  ),
});
export type Concern = v.InferOutput<typeof ConcernSchema>;

// ─── DECOMPOSE Output ────────────────────────────────────────────────────────

export const DecomposeOutputSchema = v.object({
  featureSummary: v.pipe(v.string(), v.description("One-sentence feature description")),
  concerns: v.pipe(
    v.array(ConcernSchema),
    v.minLength(2),
    v.description("Irreducible concerns extracted from the feature spec")
  ),
  sharedTypes: v.pipe(
    v.array(SharedTypeSchema),
    v.description("Data types that flow between concerns")
  ),
});
export type DecomposeOutput = v.InferOutput<typeof DecomposeOutputSchema>;

// ─── COUPLE Output ───────────────────────────────────────────────────────────

export const CouplingPairSchema = v.object({
  i: v.pipe(v.string(), v.description("Concern ID")),
  j: v.pipe(v.string(), v.description("Concern ID")),
  dataCoupling: v.pipe(v.number(), v.minValue(0), v.maxValue(1)),
  stateCoupling: v.pipe(v.number(), v.minValue(0), v.maxValue(1)),
  domainCoupling: v.pipe(v.number(), v.minValue(0), v.maxValue(1)),
  boundaryCost: v.pipe(v.number(), v.minValue(0), v.maxValue(1)),
  totalH: v.pipe(v.number(), v.description("Weighted Hamiltonian score")),
});
export type CouplingPair = v.InferOutput<typeof CouplingPairSchema>;

// ─── Hyperedges ──────────────────────────────────────────────────────────────
// Multi-way constraints that bind 3+ concerns simultaneously.
// The Hamiltonian handles pairwise. Hyperedges handle multi-way.
// Partition algorithm treats hyperedges as hard constraints — never cut.

export const HyperEdgeType = v.picklist([
  "shared_transaction", // Concerns sharing an atomic DB transaction
  "shared_invariant", // Business rule referencing 3+ concerns
  "shared_system", // 3+ concerns touching the same external system
  "shared_type_cluster", // 3+ concerns all reading/writing the same type
]);

export const HyperEdgeSchema = v.object({
  id: v.pipe(v.string(), v.description("Hyperedge identifier")),
  type: HyperEdgeType,
  concernIds: v.pipe(
    v.array(v.string()),
    v.minLength(2),
    v.description(
      "Concern IDs bound by this multi-way constraint (2+ for explicit atomicity, 3+ for heuristic)"
    )
  ),
  weight: v.pipe(
    v.number(),
    v.minValue(0),
    v.maxValue(1),
    v.description("Strength of the multi-way binding. 1.0 = never split.")
  ),
  reason: v.pipe(v.string(), v.description("Why these concerns are multi-way bound")),
});
export type HyperEdge = v.InferOutput<typeof HyperEdgeSchema>;

export const CouplingWeightsSchema = v.object({
  data: v.number(),
  state: v.number(),
  domain: v.number(),
  boundary: v.number(),
});
export type CouplingWeights = v.InferOutput<typeof CouplingWeightsSchema>;

export const CoupleOutputSchema = v.object({
  matrix: v.array(CouplingPairSchema),
  hyperedges: v.pipe(
    v.array(HyperEdgeSchema),
    v.description("Multi-way constraints that bind 3+ concerns. Partition must not cut these.")
  ),
  weights: CouplingWeightsSchema,
  concerns: v.pipe(v.array(ConcernSchema), v.description("Pass-through from decompose")),
  sharedTypes: v.pipe(v.array(SharedTypeSchema), v.description("Pass-through from decompose")),
});
export type CoupleOutput = v.InferOutput<typeof CoupleOutputSchema>;

// ─── PARTITION Output ────────────────────────────────────────────────────────
// Recursive agent tree. Valibot handles recursion via v.lazy().

export interface AgentPartition {
  name: string;
  role: string;
  concerns: string[];
  internalCohesion: number;
  subAgents: AgentPartition[];
}

export const AgentPartitionSchema: v.GenericSchema<AgentPartition> = v.lazy(() =>
  v.object({
    name: v.string(),
    role: v.pipe(v.string(), v.description("What this agent/subagent is responsible for")),
    concerns: v.pipe(v.array(v.string()), v.description("Concern IDs assigned to this partition")),
    internalCohesion: v.pipe(v.number(), v.description("Q score within this partition")),
    subAgents: v.array(AgentPartitionSchema),
  })
);

export const PartitionOutputSchema = v.object({
  agents: v.array(AgentPartitionSchema),
  modularity: v.pipe(v.number(), v.description("Global Q score of the partition")),
  concerns: v.pipe(v.array(ConcernSchema), v.description("Pass-through")),
  sharedTypes: v.pipe(v.array(SharedTypeSchema), v.description("Pass-through")),
});
export type PartitionOutput = v.InferOutput<typeof PartitionOutputSchema>;

// ─── SCAFFOLD Output ─────────────────────────────────────────────────────────

export const OutputStrategy = v.picklist([
  "Output.object",
  "Output.array",
  "Output.choice",
  "Output.text",
  "Output.json",
  "custom",
  "none",
]);

export const ModelTier = v.picklist(["haiku", "sonnet", "opus"]);

export const ToolDefinitionSchema = v.object({
  name: v.string(),
  description: v.pipe(
    v.string(),
    v.description("Tool description for the LLM — when and how to use it")
  ),
  concernIds: v.array(v.string()),
  inputFields: v.array(
    v.object({
      name: v.string(),
      type: v.pipe(v.string(), v.description("TypeScript type expression")),
      description: v.string(),
      optional: v.optional(v.boolean(), false),
    })
  ),
  outputFields: v.array(
    v.object({
      name: v.string(),
      type: v.pipe(v.string(), v.description("TypeScript type expression")),
      description: v.string(),
    })
  ),
  outputStrategy: v.pipe(
    OutputStrategy,
    v.description("Which Output type for structured generation inside this tool's execute handler")
  ),

  // ─── Code-producing fields ─────────────────────────────────────────────────
  // Literal, copy-pasteable TypeScript. Not descriptions — actual code.

  inputSchemaCode: v.pipe(
    v.optional(v.string()),
    v.description(
      "Literal Valibot: v.object({ token: v.pipe(v.string(), v.description('JWT bearer token')) })"
    )
  ),
  outputSchemaCode: v.pipe(
    v.optional(v.string()),
    v.description("Literal Valibot for the Output wrapper schema")
  ),
  executeSignature: v.pipe(
    v.optional(v.string()),
    v.description(
      "Typed function signature: async ({ field1, field2 }, { abortSignal }) => { /* TODO */ }"
    )
  ),

  // ─── Custom Output (when built-in variants don't fit) ──────────────────────

  customOutputCode: v.pipe(
    v.optional(v.string()),
    v.description(
      "Full custom Output implementation when Output.object/array/choice/text are insufficient. " +
        "Implements { type, responseFormat, parseOutput }. Used for: streaming partial validation, " +
        "multi-format responses, domain-specific parsing, fallback chains, composite outputs."
    )
  ),
  customOutputReason: v.pipe(
    v.optional(v.string()),
    v.description("Why a custom Output is needed instead of a built-in variant")
  ),

  toModelOutput: v.pipe(
    v.optional(v.string()),
    v.description("If tool returns large data, describe the compression for the model")
  ),
  toModelOutputCode: v.pipe(
    v.optional(v.string()),
    v.description(
      "Literal function: (result) => ({ summary: result.key, count: result.items.length })"
    )
  ),
  state: StateRequirement,
  strict: v.pipe(
    v.optional(v.boolean(), false),
    v.description("Enable strict tool calling when provider supports it")
  ),
  inputExamples: v.pipe(
    v.optional(v.array(v.record(v.string(), v.unknown()))),
    v.description("Example inputs to guide the model (Anthropic-native, middleware for others)")
  ),
});
export type ToolDefinition = v.InferOutput<typeof ToolDefinitionSchema>;

export interface AgentDefinition {
  name: string;
  role: string;
  modelTier: "haiku" | "sonnet" | "opus";
  tools: string[];
  instructions: string;
  stopCondition: string;
  subAgents: AgentDefinition[];
  // Description fields
  prepareStepLogic?: string;
  prepareCallSchema?: string;
  // Code-producing fields
  prepareStepCode?: string;
  prepareCallCode?: string;
  callOptionsSchemaCode?: string;
  telemetryIntegrationCode?: string;
}

export const AgentDefinitionSchema: v.GenericSchema<AgentDefinition> = v.lazy(() =>
  v.object({
    name: v.string(),
    role: v.pipe(v.string(), v.description("Single-sentence agent responsibility")),
    modelTier: v.pipe(
      ModelTier,
      v.description(
        "Cognitive shape: haiku=pattern-match, sonnet=pathfind, opus=structural-analysis"
      )
    ),
    tools: v.pipe(v.array(v.string()), v.description("Tool names assigned to this agent")),
    instructions: v.pipe(
      v.string(),
      v.description("System prompt — pure function of role + tools, no hardcoded context")
    ),
    stopCondition: v.pipe(
      v.string(),
      v.description("SDK stop expression: 'stepCountIs(5)', 'hasToolCall(\"done\")', or composite")
    ),
    subAgents: v.array(AgentDefinitionSchema),

    // ─── Description fields ─────────────────────────────────────────────
    prepareStepLogic: v.pipe(
      v.optional(v.string()),
      v.description(
        "Describe prepareStep behavior: model routing, tool gating, context compression"
      )
    ),
    prepareCallSchema: v.pipe(
      v.optional(v.string()),
      v.description("Describe callOptionsSchema fields if this agent accepts runtime options")
    ),

    // ─── Code-producing fields ──────────────────────────────────────────
    prepareStepCode: v.pipe(
      v.optional(v.string()),
      v.description(
        "Literal prepareStep function body: async ({ stepNumber }) => ({ activeTools: [...] })"
      )
    ),
    prepareCallCode: v.pipe(
      v.optional(v.string()),
      v.description(
        "Literal prepareCall function body: async ({ options }) => ({ instructions: build(...) })"
      )
    ),
    callOptionsSchemaCode: v.pipe(
      v.optional(v.string()),
      v.description("Literal Valibot for callOptionsSchema: v.object({ userId: v.string(), ... })")
    ),
    telemetryIntegrationCode: v.pipe(
      v.optional(v.string()),
      v.description("Full TelemetryIntegration class using bindTelemetryIntegration from SDK")
    ),
  })
);

export const ScaffoldOutputSchema = v.object({
  tools: v.array(ToolDefinitionSchema),
  agents: v.array(AgentDefinitionSchema),
  middlewareNeeded: v.pipe(
    v.array(
      v.object({
        name: v.string(),
        purpose: v.string(),
        type: v.picklist([
          "provider_normalization",
          "rag_injection",
          "guardrail",
          "logging",
          "caching",
          "rate_limiting",
          "custom",
        ]),
      })
    ),
    v.description("Middleware requirements identified from the architecture")
  ),
  telemetryHooks: v.pipe(
    v.array(
      v.object({
        event: v.picklist([
          "onStart",
          "onStepStart",
          "onToolCallStart",
          "onToolCallFinish",
          "onStepFinish",
          "onFinish",
        ]),
        purpose: v.string(),
      })
    ),
    v.description("Lifecycle callbacks needed for observability")
  ),
  biomeConfig: v.pipe(
    v.optional(
      v.object({
        maxCognitiveComplexity: v.pipe(
          v.number(),
          v.description("noExcessiveCognitiveComplexity threshold (default 15)")
        ),
        maxLinesPerFunction: v.pipe(
          v.number(),
          v.description("noExcessiveLinesPerFunction threshold (default 50)")
        ),
        maxLinesPerFile: v.pipe(
          v.number(),
          v.description("noExcessiveLinesPerFile threshold (default 300)")
        ),
        additionalRules: v.optional(v.array(v.string()), []),
      }),
      {
        maxCognitiveComplexity: 15,
        maxLinesPerFunction: 50,
        maxLinesPerFile: 300,
        additionalRules: [],
      }
    ),
    v.description(
      "Biome linter config for the implementing code agent. " +
        "These are quality gates — without them, agents produce monolithic, " +
        "unreadable functions. The scaffold includes this so the implementing " +
        "agent generates code that passes lint."
    )
  ),
});
export type ScaffoldOutput = v.InferOutput<typeof ScaffoldOutputSchema>;

// ─── VALIDATE Output ─────────────────────────────────────────────────────────

export const ViolationSchema = v.object({
  type: v.picklist([
    "coupling_leak", // High coupling across agent boundary
    "orphan_concern", // Concern not assigned to any agent
    "missing_type", // Type referenced but not defined in sharedTypes
    "circular_dep", // Circular dependency between agents
    "state_mismatch", // Stateful concern in stateless agent
    "unbounded_agent", // Agent with too many concerns (low cohesion)
    "missing_tool", // Concern has no corresponding tool
    "invariant_gap", // Business invariant not covered by any tool
    "requirement_gap", // Spec requirement not mapped to any concern
    "rejection_breach", // Rejection boundary not enforced by any invariant
    "hyperedge_cut", // Multi-way constraint (3+ concerns) split across agents
    "invalid_custom_output", // customOutputCode doesn't implement Output interface
    "missing_custom_output", // Tool should use custom Output but doesn't
  ]),
  severity: v.picklist(["critical", "high", "warning", "low"]),
  message: v.string(),
  concernIds: v.array(v.string()),
  suggestion: v.string(),
});

export const ValidateOutputSchema = v.object({
  coherenceScore: v.pipe(
    v.number(),
    v.minValue(0),
    v.maxValue(1),
    v.description("Global Hamiltonian coherence — 1.0 = perfect partition")
  ),
  violations: v.array(ViolationSchema),
  pass: v.boolean(),
  summary: v.string(),
});
export type ValidateOutput = v.InferOutput<typeof ValidateOutputSchema>;

// ─── Crystallize Input ───────────────────────────────────────────────────────
// The architect agent receives output from the crystallize pipeline.
// Two modes: "feature" (3 markdown files) or "product" (3 HTML files).
// The architect consumes spec + rejection. It replaces prompts/index.

export const CrystallizeMode = v.picklist(["feature", "product"]);

export const CrystallizeInputSchema = v.object({
  mode: v.pipe(
    CrystallizeMode,
    v.description("Which crystallize output mode produced these files")
  ),
  title: v.pipe(v.string(), v.description("Feature/product title")),
  slug: v.pipe(v.optional(v.string()), v.description("URL slug from crystallize")),

  // Feature mode: spec.md + rejection.md (prompts.md is replaced by scaffold)
  // Product mode: prd.html + rejection.html (index.html is replaced by scaffold)
  spec: v.pipe(
    v.string(),
    v.description(
      "Feature mode: spec.md content (architecture, components, data flow, build sequence). " +
        "Product mode: prd.html content (quality profile, build sequence, pattern memory)."
    )
  ),
  rejection: v.pipe(
    v.string(),
    v.description(
      "Feature mode: rejection.md content (anti-patterns, boundaries, drift patterns). " +
        "Product mode: rejection.html content (boundary sections)."
    )
  ),

  // Optional: existing types already in the codebase
  existingTypes: v.pipe(
    v.optional(v.array(SharedTypeSchema), []),
    v.description("Types that already exist in the codebase, passed alongside crystallize output")
  ),

  // Optional: extra constraints not in the crystallize docs
  additionalConstraints: v.pipe(
    v.optional(v.array(v.string()), []),
    v.description(
      "Constraints beyond what crystallize captured: 'max 200ms latency', 'PostgreSQL only', etc."
    )
  ),
});
export type CrystallizeInput = v.InferOutput<typeof CrystallizeInputSchema>;

// ─── Review Mode Input ──────────────────────────────────────────────────────
// For reviewing existing agent code. The EXTRACT block reads source files and
// produces the same DecomposeOutput that DECOMPOSE produces from a spec —
// but extracted from actual implementation, not specification.

export const SourceFileSchema = v.object({
  path: v.pipe(v.string(), v.description("File path relative to project root")),
  content: v.pipe(v.string(), v.description("Full file content")),
});
export type SourceFile = v.InferOutput<typeof SourceFileSchema>;

export const ReviewInputSchema = v.object({
  title: v.pipe(v.string(), v.description("Agent/feature name being reviewed")),
  description: v.pipe(
    v.optional(v.string()),
    v.description("What this agent is supposed to do — from the developer, not from code")
  ),
  sourceFiles: v.pipe(
    v.array(SourceFileSchema),
    v.minLength(1),
    v.description("TypeScript source files of the existing agent implementation")
  ),
  additionalConstraints: v.pipe(
    v.optional(v.array(v.string()), []),
    v.description("Constraints the agent should respect: 'PostgreSQL only', '< 200ms', etc.")
  ),
});
export type ReviewInput = v.InferOutput<typeof ReviewInputSchema>;

export const SdkViolationSchema = v.object({
  file: v.pipe(v.string(), v.description("Source file path")),
  line: v.pipe(v.string(), v.description("Approximate line number or range")),
  violation: v.pipe(
    v.string(),
    v.description("Invariant ID: I-1 through I-10, or 'pattern' for non-invariant issues")
  ),
  description: v.pipe(v.string(), v.description("What's wrong")),
  fix: v.pipe(v.string(), v.description("How to fix it — specific SDK pattern to use")),
});
export type SdkViolation = v.InferOutput<typeof SdkViolationSchema>;

export const ExtractOutputSchema = v.object({
  featureSummary: v.pipe(
    v.string(),
    v.description("What this agent actually does — from reading the code, not the description")
  ),
  concerns: v.pipe(
    v.array(ConcernSchema),
    v.description("Concerns extracted from actual implementation")
  ),
  sharedTypes: v.pipe(
    v.array(SharedTypeSchema),
    v.description("Types extracted from inputSchema and return types")
  ),
  sdkViolations: v.pipe(
    v.array(SdkViolationSchema),
    v.description("SDK v6 invariant violations found in the code")
  ),
  missingConcernTypes: v.pipe(
    v.optional(v.array(v.string()), []),
    v.description(
      "Concern types from the 9-type basis that are absent in the code (e.g. 'validator', 'event')"
    )
  ),
});
export type ExtractOutput = v.InferOutput<typeof ExtractOutputSchema>;
