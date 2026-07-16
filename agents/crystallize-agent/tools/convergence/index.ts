/**
 * Convergence tool — Phases 2-5: encode → decode → measure → converge loop.
 *
 * createConvergenceTool(model, models, identity, mode, outputMode) → Tool.
 * The tool runs an internal ToolLoopAgent that calls 4 phase reporting tools in sequence.
 * State is accumulated in a local ConvergenceState object and returned as ConvergenceOutput.
 *
 * Zero imports from parent folders. Drop tools/convergence/ into any project.
 */

import type {
  LanguageModel,
  ModelMessage,
  TelemetrySettings,
  ToolLoopAgentOnToolCallFinishCallback,
} from "ai";
import { ToolLoopAgent, tool } from "ai";
import type { SpanType } from "mlflow-tracing";
import { withSpan } from "mlflow-tracing";
import * as v from "valibot";
import { z } from "zod";
import { valibotSchema } from "../valibot-schema.js";
import {
  createConvergeReportTool,
  type LocalAdjustmentSignal,
  type LocalAntibody,
  loadConvergeSystem,
  MAX_ITERATIONS,
} from "./converge.js";
import { createDecodeReportTool, loadDecodeSystem } from "./decode.js";
import { createEncodeReportTool, type LocalDimension, loadEncodeSystem } from "./encode.js";
import { createMeasureReportTool, type LocalQualityVector, loadMeasureSystem } from "./measure.js";
import { iterBlockName } from "./shared.js";

export { MAX_ITERATIONS };

// ─── Local types ──────────────────────────────────────────────

interface TokenUsage {
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
}

// ─── Step models ──────────────────────────────────────────────

export type StepModels = Partial<
  Record<"encode" | "decode" | "measure" | "converge", LanguageModel>
>;

// ─── ConvergenceState — mutable, shared via experimental_context ──

interface ConvergenceState {
  encoding: LocalDimension[] | null;
  qualityVector: LocalQualityVector | null;
  elbo: number | null;
  elboTrajectory: { iteration: number; primary?: number | undefined }[];
  iterations: number;
  converged: boolean;
  outputs: Record<string, string>;
  thinking: Record<string, string>;
  antibodies: LocalAntibody[];
  adjustmentSignals: LocalAdjustmentSignal[];
  /** Per-block wall-clock duration in ms, populated by experimental_onToolCallFinish. */
  stepDurations: Record<string, number>;
}

// ─── Output types ─────────────────────────────────────────────

export interface ConvergenceOutput {
  encoding: LocalDimension[] | null;
  qualityVector: LocalQualityVector | null;
  elbo: number | null;
  elboTrajectory: { iteration: number; primary?: number | undefined }[];
  iterations: number;
  converged: boolean;
  outputs: Record<string, string>;
  thinking: Record<string, string>;
  antibodies: LocalAntibody[];
  adjustmentSignals: LocalAdjustmentSignal[];
  usage: TokenUsage;
  /** Per-block token usage keyed by block name (e.g. "crystallize.encode"). */
  usages: Record<string, TokenUsage>;
  /** Per-block wall-clock duration in ms. */
  durations: Record<string, number>;
}

// ─── Output schema ────────────────────────────────────────────

const V_TOKEN_USAGE = v.object({
  inputTokens: v.pipe(v.number(), v.integer(), v.minValue(0)),
  outputTokens: v.pipe(v.number(), v.integer(), v.minValue(0)),
  cacheReadTokens: v.pipe(v.number(), v.integer(), v.minValue(0)),
});

export const V_CONVERGENCE_OUTPUT = v.object({
  encoding: v.nullable(
    v.array(
      v.object({
        name: v.pipe(
          v.string(),
          v.minLength(1),
          v.title("Dimension name"),
          v.description("Name of the latent dimension"),
        ),
        mu: v.pipe(v.number(), v.title("Mean"), v.description("Central tendency")),
        sigma: v.pipe(
          v.number(),
          v.minValue(0),
          v.title("Spread"),
          v.description("Uncertainty spread; non-negative"),
        ),
        description: v.optional(v.string()),
      }),
    ),
  ),
  qualityVector: v.nullable(
    v.object({
      elbo: v.pipe(
        v.number(),
        v.minValue(-1),
        v.maxValue(1),
        v.description("Evidence Lower Bound; range [-1, 1]"),
      ),
      holistic: v.pipe(
        v.number(),
        v.minValue(0),
        v.maxValue(1),
        v.description("Overall quality in [0, 1]"),
      ),
      accuracy: v.pipe(
        v.number(),
        v.minValue(0),
        v.maxValue(1),
        v.description("Factual accuracy in [0, 1]"),
      ),
      complexity: v.pipe(
        v.number(),
        v.minValue(0),
        v.maxValue(1),
        v.description("Structural complexity in [0, 1]"),
      ),
      dimensions: v.record(v.string(), v.number()),
      weakest: v.optional(v.array(v.string())),
      rejection_severity: v.optional(v.pipe(v.number(), v.minValue(0), v.maxValue(1))),
      rejection_count: v.optional(v.pipe(v.number(), v.integer(), v.minValue(0))),
    }),
  ),
  elbo: v.nullable(v.number()),
  elboTrajectory: v.array(v.object({ iteration: v.number(), primary: v.optional(v.number()) })),
  iterations: v.number(),
  converged: v.boolean(),
  outputs: v.record(v.string(), v.string()),
  thinking: v.record(v.string(), v.string()),
  antibodies: v.array(
    v.object({
      pattern: v.pipe(
        v.string(),
        v.minLength(1),
        v.description("The bias or training-pull pattern"),
      ),
      type: v.pipe(
        v.picklist(["training_pull", "default_override", "bias_caught"]),
        v.description("Antibody classification"),
      ),
      strength: v.pipe(
        v.number(),
        v.minValue(0),
        v.maxValue(1),
        v.description("Signal strength in [0, 1]"),
      ),
    }),
  ),
  adjustmentSignals: v.array(
    v.object({
      dimension: v.pipe(v.string(), v.minLength(1), v.description("Latent dimension to adjust")),
      direction: v.pipe(
        v.picklist(["widen_sigma", "narrow_sigma", "shift_mu_up", "shift_mu_down"]),
        v.description("Adjustment direction"),
      ),
      magnitude: v.pipe(
        v.number(),
        v.minValue(0),
        v.description("Adjustment magnitude; non-negative"),
      ),
    }),
  ),
  usage: V_TOKEN_USAGE,
  usages: v.record(v.string(), V_TOKEN_USAGE),
  durations: v.record(v.string(), v.number()),
});

// ─── Internal context type ────────────────────────────────────

interface InternalCtx {
  state: ConvergenceState;
  iteration: number;
}

// ─── Feedback formatter ───────────────────────────────────────

function formatFeedback(state: ConvergenceState, iteration: number): string {
  const lines: string[] = [`## Iteration ${iteration} Feedback — Refine Your Encoding`, ""];

  // ELBO trajectory
  const traj = state.elboTrajectory
    .map((t) => `  i${t.iteration}: ${t.primary?.toFixed(3) ?? "?"}`)
    .join("\n");
  lines.push(`**ELBO Trajectory**:\n${traj}`);

  // Flat detection
  if (state.elboTrajectory.length >= 2) {
    const last = state.elboTrajectory[state.elboTrajectory.length - 1]?.primary;
    const prev = state.elboTrajectory[state.elboTrajectory.length - 2]?.primary;
    if (last !== undefined && prev !== undefined && Math.abs(last - prev) < 0.02) {
      lines.push(
        "⚠ ELBO is flat — widen your exploration. Use broader sigma values and consider entirely different dimension framings.",
      );
    }
  }

  // Adjustment signals
  if (state.adjustmentSignals.length > 0) {
    lines.push("", "**Adjustment Signals** (apply to next encoding):");
    for (const s of state.adjustmentSignals) {
      lines.push(`  - "${s.dimension}": ${s.direction} by ${s.magnitude.toFixed(2)}`);
    }
  }

  // Antibodies
  if (state.antibodies.length > 0) {
    lines.push("", "**Antibodies** (bias patterns — avoid these):");
    for (const a of state.antibodies) {
      lines.push(`  - [${a.type}] "${a.pattern}" (strength: ${a.strength.toFixed(2)})`);
    }
  }

  // Previous decoded plan (key context for encode to improve upon)
  const prevDecodeKey = iterBlockName("crystallize.decode", iteration - 1);
  const prevDecode = state.outputs[prevDecodeKey];
  if (prevDecode) {
    lines.push("", "**Previous Decoded Plan**:", prevDecode.slice(0, 2000));
  }

  // Previous encoding dimensions
  if (state.encoding) {
    lines.push("", "**Previous Encoding** (dimensions to refine, not repeat):");
    for (const d of state.encoding) {
      lines.push(`  - ${d.name}: μ=${d.mu.toFixed(2)}, σ=${d.sigma.toFixed(2)}`);
    }
  }

  lines.push(
    "",
    "Produce a NEW encoding that addresses these signals. Do not replicate the previous dimension structure.",
  );
  return lines.join("\n");
}

// ─── execute helpers ──────────────────────────────────────────

const TOOL_TO_BLOCK: Record<string, string> = {
  report_encoding: "crystallize.encode",
  report_decoded_plan: "crystallize.decode",
  report_quality_vector: "crystallize.measure",
  report_convergence: "crystallize.converge",
};

interface BlockUsagesResult {
  usages: Record<string, TokenUsage>;
  durations: Record<string, number>;
}

interface StepUsageSource {
  inputTokens?: number | undefined;
  outputTokens?: number | undefined;
  inputTokenDetails?: { cacheReadTokens?: number | undefined } | undefined;
}

function buildBlockUsages(
  steps: {
    toolCalls: { toolName: string }[];
    usage: StepUsageSource;
  }[],
  state: ConvergenceState,
): BlockUsagesResult {
  const usages: Record<string, TokenUsage> = {};
  let iter = 1;
  for (const step of steps) {
    const toolName = step.toolCalls[0]?.toolName;
    const blockBase = toolName ? (TOOL_TO_BLOCK[toolName] ?? null) : null;
    if (blockBase) {
      const key = iterBlockName(blockBase, iter);
      usages[key] = {
        inputTokens: step.usage.inputTokens ?? 0,
        outputTokens: step.usage.outputTokens ?? 0,
        cacheReadTokens: step.usage.inputTokenDetails?.cacheReadTokens ?? 0,
      };
      if (toolName === "report_convergence") iter++;
    }
  }
  return { usages, durations: state.stepDurations };
}

// ─── prepareStep helpers ──────────────────────────────────────

const REASONING_ON = {
  togetherai: { reasoning: { enabled: true }, reasoningEffort: "low" },
  deepinfra: { reasoningEffort: "low" },
  anthropic: { thinking: { type: "enabled" as const, budgetTokens: 8000 } },
} as const;

const REASONING_ON_LIGHT = {
  togetherai: { reasoning: { enabled: true }, reasoningEffort: "low" },
  deepinfra: { reasoningEffort: "low" },
  anthropic: { thinking: { type: "enabled" as const, budgetTokens: 4000 } },
} as const;

function getNextPhaseConfig(
  lastToolName: string,
  state: ConvergenceState,
  iteration: number,
  models: StepModels | undefined,
  model: LanguageModel,
  encodeSystem: string,
  decodeSystem: string,
  measureSystem: string,
  convergeSystem: string,
  messages: ModelMessage[],
  formatFeedbackFn: (s: ConvergenceState, iter: number) => string,
) {
  if (lastToolName === "report_convergence") {
    const nextIter = iteration + 1;
    const feedbackMsg: ModelMessage = {
      role: "user",
      content: formatFeedbackFn(state, nextIter),
    };
    state.adjustmentSignals = [];
    return {
      model: models?.encode ?? model,
      system: encodeSystem,
      messages: [...messages, feedbackMsg],
      activeTools: ["report_encoding" as const],
      toolChoice: { type: "tool", toolName: "report_encoding" } as const,
      experimental_context: { state, iteration: nextIter } satisfies InternalCtx,
      providerOptions: REASONING_ON_LIGHT,
    };
  }
  if (lastToolName === "report_encoding") {
    return {
      model: models?.decode ?? model,
      system: decodeSystem,
      activeTools: ["report_decoded_plan" as const],
      toolChoice: { type: "tool", toolName: "report_decoded_plan" } as const,
      experimental_context: { state, iteration } satisfies InternalCtx,
      providerOptions: REASONING_ON,
    };
  }
  if (lastToolName === "report_decoded_plan") {
    return {
      model: models?.measure ?? model,
      system: measureSystem,
      activeTools: ["report_quality_vector" as const],
      toolChoice: { type: "tool", toolName: "report_quality_vector" } as const,
      experimental_context: { state, iteration } satisfies InternalCtx,
      providerOptions: REASONING_ON_LIGHT,
    };
  }
  if (lastToolName === "report_quality_vector") {
    return {
      model: models?.converge ?? model,
      system: convergeSystem,
      activeTools: ["report_convergence" as const],
      toolChoice: { type: "tool", toolName: "report_convergence" } as const,
      experimental_context: { state, iteration } satisfies InternalCtx,
      providerOptions: REASONING_ON_LIGHT,
    };
  }
  return undefined;
}

// ─── Tool factory ─────────────────────────────────────────────

export function createConvergenceTool(
  model: LanguageModel,
  models: StepModels | undefined,
  mode: string,
  outputMode: string,
  telemetry?: TelemetrySettings,
) {
  // Pre-assemble all system prompts once — they're constant per factory call.
  const encodeSystem = loadEncodeSystem(mode, outputMode);
  const decodeSystem = loadDecodeSystem(mode, outputMode);
  const measureSystem = loadMeasureSystem(mode, outputMode);
  const convergeSystem = loadConvergeSystem(mode, outputMode);

  const convergenceTelemetry = telemetry
    ? {
        ...telemetry,
        functionId: "crystallize.convergence-loop",
      }
    : undefined;

  const innerTools = {
    report_encoding: createEncodeReportTool(),
    report_decoded_plan: createDecodeReportTool(),
    report_quality_vector: createMeasureReportTool(),
    report_convergence: createConvergeReportTool(MAX_ITERATIONS),
  };
  type InnerTools = typeof innerTools;

  const onInnerToolCallFinish: ToolLoopAgentOnToolCallFinishCallback<InnerTools> = (
    event,
  ): void => {
    if (!event.success) return;
    const { state, iteration } = event.experimental_context as InternalCtx;
    const toolName = event.toolCall.toolName;
    const blockBase = TOOL_TO_BLOCK[toolName];
    if (!blockBase) return;
    const blockKey = iterBlockName(blockBase, iteration);
    state.stepDurations[blockKey] = event.durationMs;
  };

  const internalSubagent = new ToolLoopAgent({
    model: model,
    ...(convergenceTelemetry ? { experimental_telemetry: convergenceTelemetry } : {}),

    callOptionsSchema: z.object({ state: z.custom<ConvergenceState>() }),

    prepareCall: ({ options, ...baseArgs }) => ({
      ...baseArgs,
      model: models?.encode ?? model,
      instructions: encodeSystem,
      experimental_context: { state: options.state, iteration: 1 } satisfies InternalCtx,
      activeTools: ["report_encoding" as const],
      toolChoice: { type: "tool", toolName: "report_encoding" } as const,
      providerOptions: REASONING_ON_LIGHT,
    }),

    tools: innerTools,

    stopWhen: ({ steps }) => {
      const lastStep = steps[steps.length - 1];
      if (!lastStep) return false;
      const conv = lastStep.toolResults.find((r) => r.toolName === "report_convergence");
      if (!conv) return false;
      if (conv.output instanceof Error) return true;
      if (typeof conv.output === "object" && conv.output !== null) {
        if ("error" in conv.output) return true;
        if ("action" in conv.output) return (conv.output as { action: string }).action === "stop";
      }
      return false;
    },

    prepareStep: ({ steps, experimental_context, messages }) => {
      const { state, iteration } = experimental_context as InternalCtx;
      const lastStep = steps[steps.length - 1];
      const lastToolName = lastStep?.toolCalls[lastStep.toolCalls.length - 1]?.toolName;

      if (!lastToolName) return undefined;
      return getNextPhaseConfig(
        lastToolName,
        state,
        iteration,
        models,
        model,
        encodeSystem,
        decodeSystem,
        measureSystem,
        convergeSystem,
        messages,
        formatFeedback,
      );
    },

    experimental_onToolCallFinish: onInnerToolCallFinish,
  });

  return tool({
    description: "Phases 2-5: encode → decode → measure → converge. Run after navigate.",
    inputSchema: valibotSchema(
      v.object({
        navigate_summary: v.pipe(v.string(), v.description("Key findings from navigate phase.")),
      }),
    ),
    outputSchema: valibotSchema(V_CONVERGENCE_OUTPUT),
    execute: async (
      { navigate_summary }: { navigate_summary: string },
      { abortSignal }: { abortSignal?: AbortSignal },
    ): Promise<ConvergenceOutput> => {
      const state: ConvergenceState = {
        encoding: null,
        qualityVector: null,
        elbo: null,
        elboTrajectory: [],
        iterations: 0,
        converged: false,
        outputs: {},
        thinking: {},
        antibodies: [],
        adjustmentSignals: [],
        stepDurations: {},
      };

      const result = await withSpan(
        (span) =>
          internalSubagent
            .generate({
              prompt: `MODE: ${mode}\n\nNavigate output:\n${navigate_summary}\n\nRun convergence loop.`,
              options: { state },
              ...(abortSignal ? { abortSignal } : {}),
            })
            .then((r) => {
              span.setOutputs({
                converged: state.converged,
                iterations: state.iterations,
                elbo: state.elbo,
              });
              return r;
            }),
        { name: "crystallize.convergence", spanType: "CHAIN" as SpanType },
      );

      // Build per-block usages and durations from step results.
      const { usages, durations } = buildBlockUsages(result.steps, state);

      return {
        encoding: state.encoding,
        qualityVector: state.qualityVector,
        elbo: state.elbo,
        elboTrajectory: state.elboTrajectory,
        iterations: state.iterations,
        converged: state.converged,
        outputs: state.outputs,
        thinking: state.thinking,
        antibodies: state.antibodies,
        adjustmentSignals: state.adjustmentSignals,
        usage: {
          inputTokens: result.totalUsage.inputTokens ?? 0,
          outputTokens: result.totalUsage.outputTokens ?? 0,
          cacheReadTokens: result.totalUsage.inputTokenDetails?.cacheReadTokens ?? 0,
        },
        usages,
        durations,
      };
    },
  });
}
