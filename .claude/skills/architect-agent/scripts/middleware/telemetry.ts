/**
 * Architect Agent — Middleware & Telemetry
 *
 * DevTools middleware for development visibility.
 * Custom telemetry integration for Hamiltonian coherence tracking.
 * Provider normalization for multi-model pipeline.
 *
 * SDK patterns:
 *   - wrapLanguageModel + LanguageModelV3Middleware for provider normalization
 *   - devToolsMiddleware for development inspection
 *   - TelemetryIntegration for custom lifecycle hooks
 *   - defaultSettingsMiddleware for shared config
 */

import type { LanguageModelV3 } from "@ai-sdk/provider";
import {
  bindTelemetryIntegration,
  defaultSettingsMiddleware,
  type LanguageModel,
  type LanguageModelMiddleware,
  type TelemetryIntegration,
  wrapLanguageModel,
} from "ai";

// ─── DevTools Middleware ──────────────────────────────────────────────────────
// Wraps models for development inspection. All calls are captured to
// .devtools/generations.json and viewable at http://localhost:4983.
//
// Usage: wrap any model before passing to ToolLoopAgent
//   const model = withArchitectMiddleware(anthropic("claude-sonnet-4.5"))

/**
 * Compose all architect-agent middleware onto a model.
 *
 * Order matters — innermost middleware runs first:
 * 1. defaultSettingsMiddleware (sets maxOutputTokens, temperature)
 * 2. provider normalization (handles tool call format differences)
 * 3. devToolsMiddleware (outermost — captures the final request/response)
 */
export function withArchitectMiddleware(
  model: LanguageModel,
  options?: { devTools?: boolean; maxOutputTokens?: number }
): LanguageModel {
  const { maxOutputTokens = 8192 } = options ?? {};

  let wrapped = wrapLanguageModel({
    model: model as unknown as LanguageModelV3,
    middleware: defaultSettingsMiddleware({
      settings: {
        maxOutputTokens,
        temperature: 0, // deterministic for architectural decisions
      },
    }),
  });

  // Provider normalization: handle differences in tool call format
  wrapped = wrapLanguageModel({
    model: wrapped as unknown as LanguageModelV3,
    middleware: providerNormalizationMiddleware,
  });

  return wrapped;
}

// ─── Provider Normalization Middleware ────────────────────────────────────────
// Handles differences between providers (Anthropic, OpenAI, Google) in how
// they format tool calls, handle structured output, and report tokens.
// This lives in middleware, never in orchestration code (anti-pattern R-11).

const providerNormalizationMiddleware: LanguageModelMiddleware = {
  specificationVersion: "v3",
  // Transform request before sending to provider
  // biome-ignore lint/suspicious/noExplicitAny: SDK middleware params type is not publicly exported with full shape
  transformParams: async ({ params }: any) => {
    // Ensure tool descriptions don't exceed provider limits
    if (params.tools) {
      return {
        ...params,
        // biome-ignore lint/suspicious/noExplicitAny: SDK tool params shape varies by provider
        tools: params.tools.map((tool: any) => ({
          ...tool,
          description: tool.description?.slice(0, 4096),
        })),
      };
    }
    return params;
  },
};

// ─── Architect Telemetry Integration ─────────────────────────────────────────
// Tracks per-block execution metrics for the architect pipeline.
// Implements TelemetryIntegration from the SDK.

export interface ArchitectMetrics {
  blockName: string;
  durationMs: number;
  inputTokens: number;
  outputTokens: number;
  costEstimateUsd: number;
  coherenceScore?: number;
}

export function createArchitectTelemetry(options?: {
  onBlockComplete?: (metrics: ArchitectMetrics) => void | Promise<void>;
  onPipelineComplete?: (metrics: ArchitectMetrics[]) => void | Promise<void>;
}): {
  integration: TelemetryIntegration;
  getMetrics: () => ArchitectMetrics[];
} {
  const metrics: ArchitectMetrics[] = [];
  const stepTimers = new Map<number, number>();

  // Class-based integration using bindTelemetryIntegration for correct `this` binding.
  // This is the SDK-recommended pattern over plain objects.
  class ArchitectTelemetryIntegration implements TelemetryIntegration {
    async onStart(_event: { model: { modelId: string }; functionId?: string }) {
      // Log pipeline start
    }

    async onStepStart(event: { stepNumber: number }) {
      stepTimers.set(event.stepNumber, Date.now());
    }

    async onToolCallStart(_event: { toolCall: { toolName: string } }) {
      // Tool execution starting — could log input size
    }

    async onToolCallFinish(event: {
      toolCall: { toolName: string };
      durationMs: number;
      success: boolean;
      output?: unknown;
      error?: unknown;
    }) {
      if (event.success) {
        // Extract coherence score from validate tool output
        const output = event.output as Record<string, unknown> | undefined;
        if (output && typeof output === "object" && "coherenceScore" in output) {
          const lastMetric = metrics[metrics.length - 1];
          if (lastMetric) {
            lastMetric.coherenceScore = output.coherenceScore as number;
          }
        }
      }
    }

    async onStepFinish(event: {
      stepNumber: number;
      usage?: { inputTokens?: number; outputTokens?: number };
      finishReason?: string;
      toolCalls?: Array<{ toolName: string }>;
    }) {
      const startTime = stepTimers.get(event.stepNumber);
      const durationMs = startTime ? Date.now() - startTime : 0;
      stepTimers.delete(event.stepNumber);

      const toolNames = event.toolCalls?.map((tc) => tc.toolName) ?? [];
      const blockName = toolNames[0] ?? `step-${event.stepNumber}`;

      const metric: ArchitectMetrics = {
        blockName,
        durationMs,
        inputTokens: event.usage?.inputTokens ?? 0,
        outputTokens: event.usage?.outputTokens ?? 0,
        costEstimateUsd: estimateCost(
          event.usage?.inputTokens ?? 0,
          event.usage?.outputTokens ?? 0
        ),
      };

      metrics.push(metric);
      await options?.onBlockComplete?.(metric);
    }

    async onFinish(_event: { totalUsage: { totalTokens?: number }; steps: unknown[] }) {
      await options?.onPipelineComplete?.(metrics);
    }
  }

  return {
    integration: bindTelemetryIntegration(new ArchitectTelemetryIntegration()),
    getMetrics: () => [...metrics],
  };
}

// ─── Cost Estimation ─────────────────────────────────────────────────────────
// Rough estimates per 1M tokens. Updated as pricing changes.

function estimateCost(inputTokens: number, outputTokens: number): number {
  // Default to Sonnet-class pricing
  const inputCostPer1M = 3.0;
  const outputCostPer1M = 15.0;

  return (inputTokens / 1_000_000) * inputCostPer1M + (outputTokens / 1_000_000) * outputCostPer1M;
}
