/**
 * Architect Agent — Pipeline Tools
 *
 * Five blocks, each a tool() object:
 *   DECOMPOSE → COUPLE → PARTITION → SCAFFOLD → VALIDATE
 *
 * SDK invariants enforced:
 *   I-1: Tools on a ToolLoopAgent (not standalone functions)
 *   I-2: tool() with inputSchema via valibotSchema()
 *   I-3: generateText + Output for structured generation (no generateObject)
 *   I-4: LanguageModel instances passed in, not hardcoded
 *   I-6: Output.object() for all structured output
 *   I-8: Tools are stateless — inputs in, outputs out
 */

import { valibotSchema } from "@ai-sdk/valibot";
import { generateText, type LanguageModel, Output, tool } from "ai";
import { jsonrepair } from "jsonrepair";
import * as v from "valibot";

import {
  buildCouplingMatrix,
  DEFAULT_WEIGHTS,
  type DomainCouplingMap,
  detectHyperedges,
} from "../math/coupling.js";
import { partitionConcerns } from "../math/partition.js";
import {
  type Concern,
  ConcernSchema,
  type CoupleOutput,
  CouplingPairSchema,
  CouplingWeightsSchema,
  CrystallizeInputSchema,
  DecomposeOutputSchema,
  HyperEdgeSchema,
  ReviewInputSchema,
  SharedTypeSchema,
} from "../schemas.js";
import { detectMissingCustomOutputs, validateCustomOutputs } from "../validation/custom-output.js";

// ─── JSON extraction helper ───────────────────────────────────────────────────
// Strips <think> blocks and code fences, finds the first JSON object/array,
// and repairs truncated JSON (handles Together AI's ~2048 token output cap).

function extractJson(text: string): unknown | null {
  const stripped = text.replace(/<think>[\s\S]*?<\/think>/g, "").trim();
  const noFences = stripped
    .replace(/^```(?:json)?\n?/m, "")
    .replace(/```\s*$/m, "")
    .trim();
  // Find the first { or [ — everything from there is candidate JSON
  const firstBrace = noFences.indexOf("{");
  const firstBracket = noFences.indexOf("[");
  const start =
    firstBrace === -1
      ? firstBracket
      : firstBracket === -1
        ? firstBrace
        : Math.min(firstBrace, firstBracket);
  if (start === -1) return null;
  const candidate = noFences.slice(start);
  try {
    return JSON.parse(candidate);
  } catch {
    // Try jsonrepair for truncated JSON (common with token-limited providers)
    try {
      return JSON.parse(jsonrepair(candidate));
    } catch {
      return null;
    }
  }
}

// ─── Model + Prompt Injection ────────────────────────────────────────────────
// Models AND system prompts are injected at agent construction.
// System prompts come from agents/*.md files — editable prompt engineering
// without touching TypeScript. The tool factory binds both at setup time.

export interface ToolModels {
  decompose: LanguageModel; // Sonnet: classification + extraction
  couple: LanguageModel; // Haiku: pairwise semantic scoring
  scaffold: LanguageModel; // Opus: structural analysis + code architecture
  validate: LanguageModel; // Sonnet: coherence checking
}

export interface ToolSystemPrompts {
  decomposer: string; // agents/decomposer.md content
  coupler: string; // agents/coupler.md content
  scaffolder: string; // agents/scaffolder.md content
  validator: string; // agents/validator.md content
  extractor: string; // agents/extractor.md content (review mode)
}

// ─── DECOMPOSE ───────────────────────────────────────────────────────────────
// LLM block. Takes a feature spec, extracts irreducible concerns.
// The LLM classifies into the fixed concern type basis — it does not
// invent new types. This is what keeps decomposition stable.

export function createDecomposeTool(model: LanguageModel, systemPrompt: string) {
  return tool({
    description:
      "Decompose a crystallize output (spec + rejection documents) into irreducible " +
      "architectural concerns. Each concern is classified into a fixed type basis: " +
      "domain, command, query, event, adapter_in, adapter_out, transformer, validator, " +
      "policy. Extracts shared data types and maps rejection boundaries to invariants.",
    inputSchema: valibotSchema(CrystallizeInputSchema),
    execute: async (input) => {
      const { output } = await generateText({
        model,
        output: Output.object({ schema: valibotSchema(DecomposeOutputSchema) }),
        system: systemPrompt,
        prompt: buildDecomposePrompt(input),
      });

      if (!output) {
        throw new Error("DECOMPOSE: model returned no structured output");
      }

      return output;
    },
  });
}

/**
 * Build the DECOMPOSE prompt from crystallize documents.
 * Structures spec + rejection as labeled sections so the model can
 * distinguish between constructive requirements and rejection boundaries.
 */
function buildDecomposePrompt(input: {
  mode: string;
  title: string;
  slug?: string | undefined;
  spec: string;
  rejection: string;
  existingTypes?: Array<{
    name: string;
    description: string;
    fields: Array<{ name: string; type: string; description: string; optional?: boolean }>;
  }>;
  additionalConstraints?: string[];
}): string {
  const parts = [
    `# ${input.title}`,
    `Mode: ${input.mode}`,
    input.slug ? `Slug: ${input.slug}` : null,
    "",
    "## SPEC DOCUMENT",
    "This is the constructive specification — what to build, how it flows, what it depends on.",
    "",
    input.spec,
    "",
    "## REJECTION DOCUMENT",
    "These are the boundaries — what is NOT permitted, anti-patterns, drift risks.",
    "Map these to invariants on the concerns you extract.",
    "",
    input.rejection,
  ].filter(Boolean);

  if (input.existingTypes && input.existingTypes.length > 0) {
    parts.push(
      "",
      "## EXISTING TYPES",
      "These types already exist in the codebase. Reference them in reads/writes where applicable.",
      "",
      JSON.stringify(input.existingTypes, null, 2)
    );
  }

  if (input.additionalConstraints && input.additionalConstraints.length > 0) {
    parts.push(
      "",
      "## ADDITIONAL CONSTRAINTS",
      ...input.additionalConstraints.map((c) => `- ${c}`)
    );
  }

  return parts.join("\n");
}

// System prompt loaded from agents/decomposer.md — see agent.ts loadAgentFiles()

// ─── COUPLE ──────────────────────────────────────────────────────────────────
// Hybrid block. Deterministic coupling (data, state, boundary) computed in
// code. Domain coupling (semantic proximity of invariants) evaluated by LLM.

export function createCoupleTool(model: LanguageModel, systemPrompt: string) {
  return tool({
    description:
      "Compute the Hamiltonian coupling matrix for a set of architectural concerns. " +
      "Data, state, and boundary coupling are computed deterministically. " +
      "Domain coupling (semantic proximity of business rules) is evaluated by LLM.",
    inputSchema: valibotSchema(
      v.object({
        concerns: v.array(ConcernSchema),
        sharedTypes: v.array(SharedTypeSchema),
        weights: v.optional(CouplingWeightsSchema),
      })
    ),
    execute: async ({ concerns, sharedTypes, weights }) => {
      const w = weights ?? DEFAULT_WEIGHTS;

      // Generate all concern pairs for LLM domain coupling evaluation
      const pairs: Array<{ i: string; j: string }> = [];
      for (let x = 0; x < concerns.length; x++) {
        for (let y = x + 1; y < concerns.length; y++) {
          pairs.push({ i: concerns[x].id, j: concerns[y].id });
        }
      }

      // LLM evaluates domain coupling for all pairs
      const domainCouplings = await evaluateDomainCoupling(model, concerns, pairs, systemPrompt);

      // Build full matrix (deterministic + LLM components)
      const matrix = buildCouplingMatrix(concerns, domainCouplings, w);

      // Detect hyperedges — multi-way constraints (deterministic, no LLM)
      const hyperedges = detectHyperedges(concerns);

      const result: CoupleOutput = {
        matrix,
        hyperedges,
        weights: w,
        concerns,
        sharedTypes,
      };

      return result;
    },
  });
}

/**
 * LLM evaluates semantic proximity of business invariants between concern pairs.
 * Returns a map of pairKey → [0, 1] domain coupling scores.
 */
async function evaluateDomainCoupling(
  model: LanguageModel,
  concerns: Concern[],
  pairs: Array<{ i: string; j: string }>,
  systemPrompt: string
): Promise<DomainCouplingMap> {
  const _PairScoreSchema = v.object({
    scores: v.array(
      v.object({
        pair: v.string(),
        score: v.pipe(v.number(), v.minValue(0), v.maxValue(1)),
        reason: v.string(),
      })
    ),
  });

  const concernMap = new Map(concerns.map((c) => [c.id, c]));

  const pairDescriptions = pairs.map((p) => {
    const a = concernMap.get(p.i);
    const b = concernMap.get(p.j);
    if (!a || !b) throw new Error(`Concern pair lookup failed: ${p.i}, ${p.j}`);
    return {
      pair: `${p.i}:${p.j}`,
      a_invariants: a.invariants,
      b_invariants: b.invariants,
      a_description: a.description,
      b_description: b.description,
    };
  });

  const { text } = await generateText({
    model,
    system:
      systemPrompt +
      "\n\nIMPORTANT: Return ONLY raw JSON with key 'scores'. No markdown, no code fences.",
    prompt: JSON.stringify(pairDescriptions),
  });

  const parsed = extractJson(text);
  if (!parsed) return {};

  const result: DomainCouplingMap = {};
  // biome-ignore lint/suspicious/noExplicitAny: LLM JSON output has no static type guarantee
  for (const s of (parsed as any).scores ?? []) {
    if (typeof s.pair === "string" && typeof s.score === "number") {
      result[s.pair] = Math.max(0, Math.min(1, s.score));
    }
  }
  return result;
}

// ─── PARTITION ───────────────────────────────────────────────────────────────
// Deterministic block. No LLM. Runs Louvain modularity maximization
// on the coupling matrix to produce the agent hierarchy.

export function createPartitionTool() {
  return tool({
    description:
      "Partition concerns into an agent hierarchy using Louvain modularity maximization. " +
      "Respects hyperedges as hard constraints — multi-way bound concerns are never split. " +
      "Deterministic — given the same coupling matrix + hyperedges, produces the same partition. " +
      "Recursive: if a partition's internal structure improves by sub-partitioning, " +
      "it produces sub-agents automatically.",
    inputSchema: valibotSchema(
      v.object({
        concerns: v.array(ConcernSchema),
        sharedTypes: v.array(SharedTypeSchema),
        matrix: v.array(CouplingPairSchema),
        hyperedges: v.optional(v.array(HyperEdgeSchema), []),
      })
    ),
    execute: async ({ concerns, sharedTypes, matrix, hyperedges }) => {
      const { agents, modularity } = partitionConcerns(concerns, matrix, hyperedges ?? []);

      return {
        agents,
        modularity,
        concerns,
        sharedTypes,
      };
    },
  });
}

// ─── SCAFFOLD ────────────────────────────────────────────────────────────────
// LLM block (Opus). Takes the partition + concerns and generates:
//   - tool() definitions for each concern
//   - ToolLoopAgent definitions for each agent partition
//   - Middleware requirements
//   - Telemetry lifecycle hooks
// This is the structural analysis block — highest cognitive load.

export function createScaffoldTool(model: LanguageModel, systemPrompt: string) {
  return tool({
    description:
      "Generate the complete AI SDK v6 scaffold from a partition. " +
      "Produces tool definitions (with inputSchema, outputStrategy, toModelOutput), " +
      "agent definitions (with ToolLoopAgent config, prepareStep logic, subagent wiring), " +
      "middleware requirements, and telemetry lifecycle hooks.",
    inputSchema: valibotSchema(
      v.object({
        // Use v.unknown() for agents to avoid recursive $ref in JSON Schema
        // (some providers reject tool schemas with $ref/$defs).
        agents: v.array(v.unknown()),
        concerns: v.array(ConcernSchema),
        sharedTypes: v.array(SharedTypeSchema),
        modularity: v.number(),
      })
    ),
    execute: async ({ agents, concerns, sharedTypes, modularity }) => {
      // Use plain text for provider-agnostic compatibility (avoids response_format rejection).
      // The system prompt instructs the model to return raw JSON.
      const { text } = await generateText({
        model,
        system: `${systemPrompt}\n\nIMPORTANT: Return ONLY raw JSON. No markdown, no code fences.`,
        prompt: JSON.stringify({ agents, concerns, sharedTypes, modularity }),
      });

      const parsed =
        extractJson(text) ??
        (() => {
          console.warn(`[SCAFFOLD] Non-JSON response (${text.length} chars), using fallback`);
          return {
            tools: [],
            agents: [],
            middleware: [],
            telemetryHooks: [],
            biomeConfig: {
              maxLinesPerFile: 300,
              maxLinesPerFunction: 50,
              maxCognitiveComplexity: 15,
            },
          };
        })();
      // biome-ignore lint/suspicious/noExplicitAny: LLM JSON output is dynamically typed, schema validated downstream
      return parsed as any;
    },
  });
}

// System prompt loaded from agents/scaffolder.md — see agent.ts loadAgentFiles()

// ─── VALIDATE ────────────────────────────────────────────────────────────────
// Hamiltonian coherence check. Verifies the scaffold against the coupling matrix.
// If it fails, the pipeline loops back to DECOMPOSE with violations as feedback.

export function createValidateTool(model: LanguageModel, systemPrompt: string) {
  return tool({
    description:
      "Validate the architectural scaffold against the Hamiltonian coupling matrix " +
      "and hyperedge constraints. Checks for coupling leaks, hyperedge cuts, " +
      "orphan concerns, missing types, and invariant gaps. " +
      "Returns a coherence score and pass/fail with specific violations.",
    inputSchema: valibotSchema(
      // Use v.unknown() for complex schemas that generate $ref/$defs
      // (some providers reject tool schemas with recursive $ref).
      v.object({
        scaffold: v.unknown(),
        partition: v.unknown(),
        crystallizeInput: v.unknown(),
        hyperedges: v.optional(v.array(v.unknown()), []),
      })
    ),
    execute: async ({ scaffold, partition, crystallizeInput, hyperedges }) => {
      // Deterministic checks first
      const violations: Array<{
        type: string;
        severity: string;
        message: string;
        concernIds: string[];
        suggestion: string;
      }> = [];

      // Check: every concern has a corresponding tool
      // biome-ignore lint/suspicious/noExplicitAny: scaffold/partition are v.unknown() — dynamically typed LLM output
      const scaffoldAny = scaffold as any;
      // biome-ignore lint/suspicious/noExplicitAny: partition is v.unknown() — dynamically typed LLM output
      const partitionAny = partition as any;
      const scaffoldTools: Array<{ concernIds: string[]; name?: string }> =
        scaffoldAny?.tools ?? [];
      const partitionConcerns: Array<{ id: string; reads: string[]; writes: string[] }> =
        partitionAny?.concerns ?? [];
      const partitionSharedTypes: Array<{ name: string }> = partitionAny?.sharedTypes ?? [];
      // biome-ignore lint/suspicious/noExplicitAny: agents array shape varies by pipeline stage
      const partitionAgents: any[] = partitionAny?.agents ?? [];
      const partitionModularity: number = partitionAny?.modularity ?? 0;

      const toolConcernIds = new Set(scaffoldTools.flatMap((t) => t.concernIds ?? []));
      const allConcernIds = partitionConcerns.map((c) => c.id);

      for (const id of allConcernIds) {
        if (!toolConcernIds.has(id)) {
          violations.push({
            type: "missing_tool",
            severity: "critical",
            message: `Concern "${id}" has no corresponding tool in the scaffold`,
            concernIds: [id],
            suggestion: `Add a tool definition for concern "${id}"`,
          });
        }
      }

      // Check: every sharedType is referenced
      const allTypeNames = new Set(partitionSharedTypes.map((t) => t.name));
      const referencedTypes = new Set(
        partitionConcerns.flatMap((c) => [...(c.reads ?? []), ...(c.writes ?? [])])
      );

      for (const typeName of allTypeNames) {
        if (!referencedTypes.has(typeName)) {
          violations.push({
            type: "missing_type",
            severity: "high",
            message: `Shared type "${typeName}" defined but never referenced`,
            concernIds: [],
            suggestion: `Remove orphan type "${typeName}" or add a concern that uses it`,
          });
        }
      }

      // Check: concerns reading a type have a writer for that type
      for (const concern of partitionConcerns) {
        for (const readType of concern.reads ?? []) {
          const hasWriter = partitionConcerns.some(
            (c) => c.id !== concern.id && (c.writes ?? []).includes(readType)
          );
          if (!hasWriter && !allTypeNames.has(readType)) {
            violations.push({
              type: "missing_type",
              severity: "critical",
              message: `Concern "${concern.id}" reads type "${readType}" but no concern writes it`,
              concernIds: [concern.id],
              suggestion: `Add a concern that produces "${readType}" or check the type name`,
            });
          }
        }
      }

      // Check: hyperedge integrity — no multi-way constraint split across agents
      if (hyperedges && hyperedges.length > 0) {
        const concernToAgent = new Map<string, string>();
        // biome-ignore lint/suspicious/noExplicitAny: recursive agent tree structure from v.unknown() input
        const collectAgentMap = (agents: any[]) => {
          for (const agent of agents) {
            for (const cId of agent.concerns ?? []) {
              concernToAgent.set(cId, agent.name);
            }
            if (agent.subAgents) collectAgentMap(agent.subAgents);
          }
        };
        collectAgentMap(partitionAgents);

        // biome-ignore lint/suspicious/noExplicitAny: hyperedges is v.unknown()[] — dynamically typed pipeline input
        for (const he of hyperedges as any[]) {
          const agentSet = new Set<string>();
          for (const cId of he.concernIds ?? []) {
            const agentName = concernToAgent.get(cId);
            if (agentName) agentSet.add(agentName);
          }
          if (agentSet.size > 1) {
            violations.push({
              type: "hyperedge_cut",
              severity: "critical",
              message: `Hyperedge "${he.id}" (${he.type}) spans ${agentSet.size} agents: ${[...agentSet].join(", ")}. Concerns: ${(he.concernIds ?? []).join(", ")}`,
              concernIds: he.concernIds ?? [],
              suggestion: `Merge these agents or adjust Hamiltonian weights to keep hyperedge members together. Reason: ${he.reason}`,
            });
          }
        }
      }

      // Check: custom Output validity — customOutputCode must implement interface
      const safeScaffold = {
        tools: scaffoldTools,
        // biome-ignore lint/suspicious/noExplicitAny: scaffold is v.unknown() — dynamically typed pipeline output
        agents: (scaffold as any)?.agents ?? [],
        // biome-ignore lint/suspicious/noExplicitAny: scaffold is v.unknown() — dynamically typed pipeline output
        middlewareNeeded: (scaffold as any)?.middlewareNeeded ?? [],
        // biome-ignore lint/suspicious/noExplicitAny: scaffold is v.unknown() — dynamically typed pipeline output
        telemetryHooks: (scaffold as any)?.telemetryHooks ?? [],
        // biome-ignore lint/suspicious/noExplicitAny: safeScaffold passed to validateCustomOutputs which expects loose typing
      } as any;
      const customResults = validateCustomOutputs(safeScaffold);
      for (const cr of customResults) {
        if (!cr.valid) {
          for (const err of cr.errors) {
            violations.push({
              type: "invalid_custom_output",
              severity: "critical",
              message: `Tool "${cr.toolName}" customOutputCode: ${err}`,
              concernIds: scaffoldTools
                .filter((t) => t.name === cr.toolName)
                .flatMap((t) => t.concernIds ?? []),
              suggestion:
                "Fix the Output interface implementation — needs type, responseFormat, parseOutput with success/value return",
            });
          }
        }
      }

      // Check: tools that SHOULD use custom Output but don't
      const missingCustom = detectMissingCustomOutputs(safeScaffold);
      for (const mc of missingCustom) {
        violations.push({
          type: "missing_custom_output",
          severity: "warning",
          message: `Tool "${mc.toolName}": ${mc.reason}`,
          concernIds: scaffoldTools
            .filter((t) => t.name === mc.toolName)
            .flatMap((t) => t.concernIds ?? []),
          suggestion: "Consider custom Output with domain-specific parser instead of Output.object",
        });
      }

      // LLM evaluates higher-order coherence
      const { text: llmText } = await generateText({
        model,
        system:
          systemPrompt +
          "\n\nIMPORTANT: Return ONLY raw JSON with keys 'couplingLeaks', 'invariantGaps', 'coherenceScore'. No markdown, no code fences.",
        prompt: JSON.stringify({
          scaffold,
          partition,
          crystallizeInput,
        }),
      });
      const llmValidation = extractJson(llmText) as {
        couplingLeaks?: Array<{
          agentA: string;
          agentB: string;
          leakedConcerns: string[];
          severity: number;
          reason: string;
        }>;
        invariantGaps?: Array<{ invariant: string; concernId: string; issue: string }>;
        coherenceScore?: number;
      } | null;

      // Merge LLM findings
      if (llmValidation) {
        for (const leak of llmValidation.couplingLeaks ?? []) {
          if (leak.severity > 0.5) {
            violations.push({
              type: "coupling_leak",
              severity: leak.severity > 0.7 ? "critical" : "high",
              message: `High coupling between agents "${leak.agentA}" and "${leak.agentB}": ${leak.reason}`,
              concernIds: leak.leakedConcerns,
              suggestion: `Consider merging these concerns into the same agent`,
            });
          }
        }

        for (const gap of llmValidation.invariantGaps ?? []) {
          violations.push({
            type: "invariant_gap",
            severity: "high",
            message: `Invariant "${gap.invariant}" on concern "${gap.concernId}": ${gap.issue}`,
            concernIds: [gap.concernId],
            suggestion: `Ensure the tool for "${gap.concernId}" enforces this invariant`,
          });
        }
      }

      const coherenceScore = llmValidation?.coherenceScore ?? 0;
      const hasCritical = violations.some((v) => v.severity === "critical");
      const pass = coherenceScore >= 0.7 && !hasCritical;

      return {
        coherenceScore,
        violations,
        pass,
        summary: pass
          ? `Architecture is coherent (Q=${partitionModularity.toFixed(3)}, H=${coherenceScore.toFixed(3)}). ${violations.length} non-blocking findings.`
          : `Architecture has ${violations.filter((v) => v.severity === "critical").length} critical violations. Coherence: ${coherenceScore.toFixed(3)}. Restructure needed.`,
      };
    },
  });
}

// ─── EXTRACT ─────────────────────────────────────────────────────────────────
// Review mode. Reads existing agent code and reverse-decomposes into concerns.
// Produces the same output shape as DECOMPOSE — so the rest of the pipeline
// (COUPLE → PARTITION → SCAFFOLD → VALIDATE) works identically.
// The diff between SCAFFOLD output and actual code IS the remediation plan.

export function createExtractTool(model: LanguageModel, systemPrompt: string) {
  return tool({
    description:
      "Reverse-decompose existing agent code into architectural concerns. " +
      "Reads TypeScript source files, identifies tools, agents, data flow, " +
      "SDK violations, and missing concern types. Produces the same output " +
      "as DECOMPOSE so the rest of the pipeline can run on extracted concerns.",
    inputSchema: valibotSchema(ReviewInputSchema),
    execute: async (input) => {
      // Concatenate all source files with clear delimiters
      const sourceContext = input.sourceFiles
        .map((f) => `\n${"=".repeat(60)}\nFILE: ${f.path}\n${"=".repeat(60)}\n\n${f.content}`)
        .join("\n");

      const prompt = [
        `# Review: ${input.title}`,
        input.description ? `\nDeveloper says: "${input.description}"\n` : "",
        input.additionalConstraints && input.additionalConstraints.length > 0
          ? `\nConstraints:\n${input.additionalConstraints.map((c) => `- ${c}`).join("\n")}\n`
          : "",
        "\n## Source Files\n",
        sourceContext,
      ].join("");

      // Use plain text for provider-agnostic compatibility (avoids response_format rejection).
      // Append JSON template to force fill-in mode — prevents prose analysis output.
      const jsonTemplate = `
YOUR ENTIRE RESPONSE MUST BE VALID JSON. No explanation, no preamble, no markdown.
Fill in this template based on the source files above:

{
  "featureSummary": "<what this agent actually does>",
  "concerns": [
    {
      "id": "<tool-name or concern-id>",
      "name": "<concern name>",
      "type": "<domain|command|query|event|adapter_in|adapter_out|transformer|validator|policy>",
      "description": "EXTRACTED from <source>: <what it does>",
      "reads": ["<input type names>"],
      "writes": ["<output type names>"],
      "state": "stateless",
      "invariants": ["<constraints>"],
      "externalSystems": [],
      "atomicity": null
    }
  ],
  "sharedTypes": [
    {
      "name": "<TypeName>",
      "description": "EXTRACTED from <source>",
      "fields": [{ "name": "<field>", "type": "<type>", "description": "<desc>" }]
    }
  ],
  "sdkViolations": [
    {
      "file": "<filename>",
      "line": "~<linenum>",
      "violation": "I-<N>",
      "description": "<what is wrong>",
      "fix": "<how to fix>"
    }
  ],
  "missingConcernTypes": ["<absent types from the 9-type basis>"],
  "biomeViolations": {
    "filesOver300": [],
    "functionsOver50": [],
    "totalDataInCodeLines": 0,
    "totalDuplicatedLines": 0
  },
  "duplicatedCode": []
}

OUTPUT ONLY THE JSON OBJECT. NOTHING ELSE.`;

      const { text } = await generateText({
        model,
        system: systemPrompt,
        prompt: prompt + jsonTemplate,
      });

      const parsed =
        extractJson(text) ??
        (() => {
          console.warn(`[EXTRACT] Non-JSON response (${text.length} chars), using fallback`);
          return { concerns: [], sharedTypes: [], sdkViolations: [] };
        })();
      // biome-ignore lint/suspicious/noExplicitAny: LLM JSON output is dynamically typed, schema validated downstream
      return parsed as any;
    },
  });
}
