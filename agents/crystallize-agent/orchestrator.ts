/**
 * Crystallize orchestrator — main entry point.
 *
 * ToolLoopAgent created per runCrystallize call with tools baked in.
 * navigate → convergence → output enforced via prepareStep.
 * OutputToolContext injected into experimental_context before output step.
 * experimental_onToolCallFinish reads structured outputs via `output` field.
 *
 * Telemetry: caller registers a TelemetryIntegration globally via
 * registerTelemetryIntegration() from 'ai'. No model wrapping here.
 *
 * Domain and pipeline types live in ./types.ts.
 */

import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { type LanguageModel, stepCountIs, ToolLoopAgent } from "ai";
import { estimateCostUsd, type ProviderPricing, resolvePricing } from "./lib/cost.js";
import type { OnToolCallFinishEvent } from "./middleware/telemetry-types.js";
import { type ConvergenceOutput, createConvergenceTool } from "./tools/convergence/index.js";

export type { ProviderPricing } from "./lib/cost.js";
export { ORCHESTRATOR_FUNCTION_ID } from "./types.js";
export type {
  AdjustmentSignal,
  CrystAntibody,
  CrystallizeConfig,
  CrystallizeParams,
  CrystallizeResult,
  CrystConstraint,
  CrystDimension,
  CrystGene,
  CrystIdentity,
  CrystMechanism,
  CrystMode,
  CrystOutput,
  CrystPersona,
  CrystQualityVector,
  CrystSeed,
  CrystStep,
  CrystVertex,
  PipelineContext,
  StoreBlockParams,
} from "./types.js";

import { iterBlockName } from "./tools/convergence/shared.js";
import {
  createNavigateTool,
  loadNavigateSystem,
  type NavigateOutput,
} from "./tools/navigate/index.js";
import {
  createOutputTool,
  loadOutputSystem,
  type OutputOutput,
  type OutputToolContext,
} from "./tools/output/index.js";
import {
  ORCHESTRATOR_FUNCTION_ID,
  type CrystAntibody,
  type CrystallizeConfig,
  type CrystallizeResult,
  type CrystDimension,
  type CrystQualityVector,
  type CrystStep,
  type PipelineContext,
} from "./types.js";

// ─── Factory ──────────────────────────────────────────────────

export function createPipelineContext(config: CrystallizeConfig): PipelineContext {
  return {
    lineageId: config.lineageId,
    anchor: config.anchor,
    params: config.params,
    jobId: config.jobId,
    wakeUpContext: config.wakeUpContext,
    outputDir: config.outputDir,
    model: config.model,
    models: config.models,
    pricing: config.pricing ?? null,
    pricingByModel: config.pricingByModel ?? {},
    outputs: {},
    thinking: {},
    convergenceIn: 0,
    convergenceOut: 0,

    iterations: 0,
    converged: false,
    elboTrajectory: [],
    vertices: [],
    encoding: null,
    qualityVector: null,
    elbo: null,
    crystAntibodies: config.initialAntibodies ? [...config.initialAntibodies] : [],
    adjustmentSignals: [],
    telemetry: config.telemetry,
  };
}

// ─── Context builder (for output step) ───────────────────────

function formatQualitySection(qv: CrystQualityVector | null): string {
  if (!qv) return "";
  const lines = ["\n\n---\n\n## Structured: Quality Vector\n", "### Primary\n"];
  lines.push(
    `ELBO: ${qv.elbo.toFixed(3)} | holistic: ${qv.holistic.toFixed(2)} | accuracy: ${qv.accuracy.toFixed(2)} | complexity: ${qv.complexity.toFixed(2)}`,
  );
  if (qv.rejection_severity !== undefined)
    lines.push(
      `Rejection: severity=${qv.rejection_severity.toFixed(2)}, count=${qv.rejection_count ?? 0}`,
    );
  if (qv.weakest?.length) lines.push(`Weakest: ${qv.weakest.join(", ")}`);
  lines.push("", "| Dimension | Score |", "|---|---|");
  for (const [dim, score] of Object.entries(qv.dimensions)) {
    lines.push(`| ${dim} | ${(score).toFixed(2)} |`);
  }
  return lines.join("\n");
}

const CONVERGENCE_BLOCKS = [
  "crystallize.encode",
  "crystallize.decode",
  "crystallize.measure",
  "crystallize.converge",
] as const;

function buildConvergenceBlockLines(ctx: PipelineContext, iterations: number): string[] {
  const iters = iterations > 1 ? [iterations - 1, iterations] : [iterations];
  const parts: string[] = [];
  for (const iter of iters) {
    for (const base of CONVERGENCE_BLOCKS) {
      const key = iterBlockName(base, iter);
      const out = ctx.outputs[key];
      if (out && out !== "SKIP (compare mode)") {
        parts.push(`### ${key}${iter === iterations - 1 ? ` (iteration ${iter})` : ""}\n\n${out}`);
      }
    }
  }
  return parts;
}

function buildPipelineContext(ctx: PipelineContext): string {
  const parts: string[] = [];
  const iterations = ctx.iterations;

  const navOut = ctx.outputs["crystallize.navigate"];
  if (navOut) parts.push(`### crystallize.navigate\n\n${navOut}`);

  if (iterations > 1 && ctx.elboTrajectory.length > 0) {
    const trajectory = ctx.elboTrajectory
      .filter((e) => e.iteration < iterations)
      .map((e) => `Iteration ${e.iteration}: ELBO ${e.primary?.toFixed(2) ?? "N/A"}`)
      .join("\n");
    if (trajectory) parts.push(`### ELBO Trajectory (earlier iterations)\n\n${trajectory}`);
  }

  parts.push(...buildConvergenceBlockLines(ctx, iterations));

  const qualitySection = formatQualitySection(ctx.qualityVector);
  if (qualitySection) parts.push(qualitySection);

  return parts.join("\n\n---\n\n");
}

// ─── Cost helper ──────────────────────────────────────────────

function blockCost(
  ctx: PipelineContext,
  model: LanguageModel,
  usage: { inputTokens: number; outputTokens: number; cacheReadTokens: number },
): number {
  const modelId = (model as { modelId?: string }).modelId;
  return estimateCostUsd(
    usage.inputTokens,
    usage.outputTokens,
    usage.cacheReadTokens,
    resolvePricing(ctx.pricingByModel, ctx.pricing, modelId),
  );
}

// ─── runCrystallize ───────────────────────────────────────────

const ITER_SUFFIX_RE = /-i\d+$/;

function mergeAntibodiesInto(target: CrystAntibody[], incoming: CrystAntibody[]): void {
  for (const ab of incoming) {
    const idx = target.findIndex((a) => a.pattern === ab.pattern);
    if (idx === -1) target.push(ab);
    else if (ab.strength > (target[idx]?.strength ?? -Infinity)) target[idx] = ab;
  }
}

function loadOrchestratorInstructions(): string {
  const _dir = dirname(fileURLToPath(import.meta.url));
  let eliIdentity = "";
  try {
    eliIdentity = readFileSync(join(_dir, "tools/eli-identity.md"), "utf-8");
  } catch {
    // file missing — proceed without identity
  }
  const pipeline =
    "You are the crystallize pipeline orchestrator. Call the three phase tools in order:\n1. navigate — vertex discovery\n2. convergence — encode/decode/measure/converge loop\n3. output — final crystallized result\n\nCall each tool once in order. Do not skip steps.";
  return eliIdentity ? `${eliIdentity}\n\n---\n\n${pipeline}` : pipeline;
}

const ORCHESTRATOR_INSTRUCTIONS = loadOrchestratorInstructions();

export async function runCrystallize(
  inputText: string,
  config: CrystallizeConfig,
): Promise<CrystallizeResult> {
  const pipelineCtx = createPipelineContext(config);

  // Pre-assemble system prompts (constant for this run).
  const navigateSystem = loadNavigateSystem(pipelineCtx.params);
  const outputSystem = loadOutputSystem(pipelineCtx.params);

  const orchestrator = new ToolLoopAgent({
    model: pipelineCtx.model,
    ...(pipelineCtx.telemetry
      ? {
          experimental_telemetry: {
            ...pipelineCtx.telemetry,
            functionId: ORCHESTRATOR_FUNCTION_ID,
          },
        }
      : {}),

    tools: {
      navigate: createNavigateTool(
        pipelineCtx.models?.navigate ?? pipelineCtx.model,
        navigateSystem,
        pipelineCtx.telemetry
          ? {
              ...pipelineCtx.telemetry,
              functionId: "crystallize.navigate.model",
            }
          : undefined,
      ),
      convergence: createConvergenceTool(
        pipelineCtx.model,
        pipelineCtx.models as Record<string, LanguageModel> | undefined,
        pipelineCtx.params.mode,
        pipelineCtx.params.output,
        pipelineCtx.telemetry,
      ),
      output: createOutputTool(
        pipelineCtx.models?.output ?? pipelineCtx.model,
        outputSystem,
        pipelineCtx.telemetry
          ? {
              ...pipelineCtx.telemetry,
              functionId: "crystallize.output.model",
            }
          : undefined,
      ),
    },

    prepareCall: (baseArgs) => ({
      ...baseArgs,
      model: pipelineCtx.model,
      instructions: ORCHESTRATOR_INSTRUCTIONS,
      // Freeze snapshot: brain integration reads immutable config fields only.
      // Mutable state (outputs, iterations) is accessed via closure in handleX functions.
      experimental_context: Object.freeze({ ...pipelineCtx }) as unknown,
      activeTools: ["navigate"] as const,
    }),

    stopWhen: [
      ({ steps }) => steps.some((s) => s.toolCalls.some((tc) => tc.toolName === "output")),
      stepCountIs(10),
    ],

    prepareStep: ({ steps, messages }) => {
      const calledTools = new Set(steps.flatMap((s) => s.toolCalls.map((tc) => tc.toolName)));

      if (!calledTools.has("navigate")) {
        return {
          activeTools: ["navigate"] as const,
          toolChoice: { type: "tool", toolName: "navigate" } as const,
        };
      }

      if (!calledTools.has("convergence")) {
        const navigateOutput = pipelineCtx.outputs["crystallize.navigate"] ?? "";
        if (!navigateOutput) {
          console.warn(
            "[orchestrator] prepareStep: navigate was called but outputs[crystallize.navigate] is empty — convergence will proceed without navigation context",
          );
        }
        return {
          model: pipelineCtx.model,
          messages: navigateOutput
            ? [
                ...messages,
                {
                  role: "user" as const,
                  content: `Navigate output — pass this verbatim as navigate_summary:\n\n${navigateOutput}`,
                },
              ]
            : messages,
          activeTools: ["convergence"] as const,
          toolChoice: { type: "tool", toolName: "convergence" } as const,
        };
      }

      const outputCtx: OutputToolContext = {
        pipelineContext: buildPipelineContext(pipelineCtx),
        elboSummary:
          pipelineCtx.elbo !== null ? `ELBO: ${pipelineCtx.elbo.toFixed(3)}` : "ELBO: N/A",
        iterations: pipelineCtx.iterations,
        converged: pipelineCtx.converged,
        slug: pipelineCtx.params.slug,
        outputMode: pipelineCtx.params.output,
      };

      return {
        // Use base orchestrator model for dispatch — output model runs inside createOutputTool.
        activeTools: ["output"] as const,
        toolChoice: { type: "tool", toolName: "output" } as const,
        experimental_context: outputCtx as unknown,
      };
    },
  });

  let innerCostUsd = 0;

  const handleNavigate = (r: NavigateOutput): void => {
    pipelineCtx.vertices = r.vertices;
    pipelineCtx.outputs["crystallize.navigate"] = r.text;
    if (r.thinking) pipelineCtx.thinking["crystallize.navigate"] = r.thinking;
    innerCostUsd += blockCost(
      pipelineCtx,
      pipelineCtx.models?.navigate ?? pipelineCtx.model,
      r.usage,
    );
  };

  const handleConvergence = (r: ConvergenceOutput): void => {
    pipelineCtx.convergenceIn += r.usage.inputTokens;
    pipelineCtx.convergenceOut += r.usage.outputTokens;
    pipelineCtx.iterations = r.iterations;
    pipelineCtx.converged = r.converged;
    pipelineCtx.elbo = r.elbo;
    pipelineCtx.elboTrajectory = r.elboTrajectory;
    pipelineCtx.qualityVector = r.qualityVector as CrystQualityVector | null;
    pipelineCtx.encoding = r.encoding as CrystDimension[] | null;
    pipelineCtx.adjustmentSignals = r.adjustmentSignals;
    Object.assign(pipelineCtx.outputs, r.outputs);
    Object.assign(pipelineCtx.thinking, r.thinking);
    mergeAntibodiesInto(pipelineCtx.crystAntibodies, r.antibodies as CrystAntibody[]);
    // Accumulate convergence costs from per-block usages.
    for (const [blockName, bu] of Object.entries(r.usages)) {
      const baseBlockName = blockName.replace(ITER_SUFFIX_RE, "");
      const resolvedModel =
        pipelineCtx.models?.[baseBlockName.replace("crystallize.", "") as CrystStep] ??
        pipelineCtx.model;
      innerCostUsd += blockCost(pipelineCtx, resolvedModel, {
        inputTokens: bu.inputTokens,
        outputTokens: bu.outputTokens,
        cacheReadTokens: bu.cacheReadTokens,
      });
    }
  };

  const handleOutput = (r: OutputOutput): void => {
    pipelineCtx.outputs["crystallize.output"] = r.text;
    if (r.thinking) pipelineCtx.thinking["crystallize.output"] = r.thinking;
    pipelineCtx.outputAntibodies = r.antibodies as CrystAntibody[];
    pipelineCtx.outputFiles = r.files;
    // File I/O lives here — lifecycle handler responsibility, not tool responsibility.
    if (r.files?.length && pipelineCtx.outputDir) {
      mkdirSync(pipelineCtx.outputDir, { recursive: true });
      for (const f of r.files) {
        writeFileSync(join(pipelineCtx.outputDir, f.name), f.content, "utf-8");
      }
    }
    innerCostUsd += blockCost(
      pipelineCtx,
      pipelineCtx.models?.output ?? pipelineCtx.model,
      r.usage,
    );
  };

  let outputError: Error | undefined;

  const handleToolFailure = (toolName: string, error: unknown): void => {
    if (toolName === "output") {
      outputError = error instanceof Error ? error : new Error(String(error));
    } else {
      console.warn(
        `[orchestrator] tool call failed: ${toolName} — ${error instanceof Error ? error.message : String(error)}`,
      );
    }
  };

  const handleToolSuccess = (toolName: string, output: unknown): void => {
    if (toolName === "navigate") handleNavigate(output as NavigateOutput);
    else if (toolName === "convergence") handleConvergence(output as ConvergenceOutput);
    else if (toolName === "output") handleOutput(output as OutputOutput);
  };

  const onToolCallFinish = (event: OnToolCallFinishEvent): void => {
    if (!event.success) {
      handleToolFailure(event.toolCall.toolName, event.error);
      return;
    }
    handleToolSuccess(event.toolCall.toolName, event.output);
  };

  const agentResult = await orchestrator.generate({
    prompt: inputText,
    // exactOptionalPropertyTypes causes a contravariance mismatch between generic
    // OnToolCallFinishEvent<ToolSet> and the concrete tool union. Cast to the exact
    // expected type using the orchestrator's own generate signature as the source of truth.
    experimental_onToolCallFinish: onToolCallFinish as unknown as NonNullable<
      Parameters<typeof orchestrator.generate>[0]["experimental_onToolCallFinish"]
    >,
  });

  if (outputError) throw outputError;

  if (agentResult.finishReason === "length") {
    throw new Error(
      // biome-ignore lint/security/noSecrets: false positive — error message string, not a secret
      "crystallize: context window exceeded (finishReason='length') — pipeline truncated before output step",
    );
  }
  if (agentResult.finishReason === "error") {
    throw new Error("crystallize: model returned finishReason='error'");
  }

  const outputCalled = agentResult.steps.some((s) =>
    s.toolCalls.some((tc) => tc.toolName === "output"),
  );
  if (!outputCalled) {
    throw new Error(
      "crystallize: pipeline did not complete — output step was never reached (step cap or early stop)",
    );
  }

  const totalIn = (agentResult.totalUsage.inputTokens ?? 0) + pipelineCtx.convergenceIn;
  const totalOut = (agentResult.totalUsage.outputTokens ?? 0) + pipelineCtx.convergenceOut;
  // Inner tool costs (navigate, convergence blocks, output) + outer orchestrator dispatch steps.
  const totalCostUsd =
    innerCostUsd +
    agentResult.steps.reduce((sum, step) => {
      const modelId = step.response.modelId as string | undefined;
      const usage = step.usage;
      const cost = estimateCostUsd(
        usage.inputTokens ?? 0,
        usage.outputTokens ?? 0,
        0,
        resolvePricing(pipelineCtx.pricingByModel, pipelineCtx.pricing, modelId),
      );
      return sum + cost;
    }, 0);

  const allAntibodies = [...pipelineCtx.crystAntibodies];
  mergeAntibodiesInto(allAntibodies, pipelineCtx.outputAntibodies ?? []);

  return {
    blockOutputs: pipelineCtx.outputs,
    blockThinking: pipelineCtx.thinking,
    iterations: pipelineCtx.iterations,
    converged: pipelineCtx.converged,
    antibodies: allAntibodies,
    files: pipelineCtx.outputFiles,
    totalInputTokens: totalIn,
    totalOutputTokens: totalOut,
    totalCostUsd,
  };
}
