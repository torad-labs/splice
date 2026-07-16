/**
 * Telemetry integrations — lifecycle hooks for crystallize pipeline calls.
 *
 * createCrystallizeTelemetry(phase) — per-call TelemetryIntegration for console logging.
 *   Pass as `integrations: [createCrystallizeTelemetry("navigate")]` in experimental_telemetry.
 *   Logs phase-tagged start/step/tool/finish lines with timing.
 *
 * createCrystallizeIntegration(log, pricing?) — global TelemetryIntegration for structured logging.
 *   Register once: registerTelemetryIntegration(createCrystallizeIntegration(logFn, pricing))
 *   Logs every model call with tokens, cost (if pricing provided), and timing.
 *   Handles nested calls (orchestrator → convergence) correctly via LIFO start-time stack.
 *
 * Zero imports from parent folders.
 */

import {
  bindTelemetryIntegration,
  type OnFinishEvent,
  type OnStartEvent,
  type OnStepFinishEvent,
  type OnStepStartEvent,
  type OnToolCallFinishEvent,
  type OnToolCallStartEvent,
  type TelemetryIntegration,
} from "./telemetry-types.js";
import { estimateCostUsd, type PricingTable, resolvePricing } from "../lib/cost.js";

export type { PricingTable } from "../lib/cost.js";

// ─── Types ────────────────────────────────────────────────────

/** Minimal log function — pass console.log, pino, or any logger method. */
export type TelemetryLogFn = (event: string, data?: Record<string, unknown>) => void;

/** Structural type for AI SDK step entries in OnFinishEvent — sdk doesn't export these fields typed. */
interface StepLike {
  model: { modelId: string };
  usage: {
    inputTokens?: number;
    outputTokens?: number;
    inputTokenDetails?: { cacheReadTokens?: number };
    outputTokenDetails?: { reasoningTokens?: number };
  };
}

// ─── Helpers ──────────────────────────────────────────────────

function fmtMs(ms: number): string {
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
}

// ─── Per-phase lifecycle logger ───────────────────────────────

/**
 * Per-call TelemetryIntegration for console-logging phase lifecycle.
 * Pass as `integrations: [createCrystallizeTelemetry("navigate")]` in experimental_telemetry.
 */
export function createCrystallizeTelemetry(phase: string): TelemetryIntegration {
  const prefix = `[crystallize:${phase}]`;
  const stepStartTimes = new Map<number, number>();
  let agentStartTime = 0;

  return bindTelemetryIntegration({
    onStart(event: OnStartEvent) {
      agentStartTime = Date.now();
      console.log(`${prefix} start — ${event.model.modelId}`);
    },
    onStepStart(event: OnStepStartEvent) {
      stepStartTimes.set(event.stepNumber, Date.now());
      console.log(`${prefix} step ${event.stepNumber} start`);
    },
    onToolCallStart(event: OnToolCallStartEvent) {
      console.log(`${prefix}   → ${event.toolCall.toolName}`);
    },
    onToolCallFinish(event: OnToolCallFinishEvent) {
      const status = event.success ? "ok" : "error";
      console.log(
        `${prefix}   ← ${event.toolCall.toolName} (${fmtMs(event.durationMs)}) — ${status}`,
      );
    },
    onStepFinish(event: OnStepFinishEvent) {
      const start = stepStartTimes.get(event.stepNumber);
      const elapsed = start !== undefined ? fmtMs(Date.now() - start) : "?";
      stepStartTimes.delete(event.stepNumber);
      const tools =
        event.toolCalls.length === 0
          ? "—"
          : event.toolCalls.map((t: { toolName: string }) => t.toolName).join(", ");
      const reasoning = event.usage.outputTokenDetails?.reasoningTokens;
      console.log(
        `${prefix} step ${event.stepNumber} done — [${tools}] (${event.usage.inputTokens}in + ${event.usage.outputTokens}out${reasoning ? ` + ${reasoning}think` : ""}, ${elapsed})`,
      );
    },
    onFinish(event: OnFinishEvent) {
      const elapsed = agentStartTime ? fmtMs(Date.now() - agentStartTime) : "?";
      console.log(
        `${prefix} done — ${event.steps.length} steps, ${event.totalUsage.inputTokens}in + ${event.totalUsage.outputTokens}out, ${elapsed}`,
      );
    },
  });
}

// ─── Global structured logger ─────────────────────────────────

/**
 * Global TelemetryIntegration for structured per-call logging.
 * Handles nested calls (e.g. orchestrator wrapping convergence) via LIFO start-time stack.
 *
 * Register once: registerTelemetryIntegration(createCrystallizeIntegration(logFn, pricing))
 */
export function createCrystallizeIntegration(
  log: TelemetryLogFn,
  pricing: PricingTable = {},
): TelemetryIntegration {
  // LIFO stack — correct for single-threaded Node.js where onStart/onFinish pairs nest.
  // Map<spanId> doesn't work: onStart fires inside the span context, onFinish fires after
  // the span ends (different active span), so keys never match.
  const startStack: { startMs: number; modelId: string }[] = [];

  return bindTelemetryIntegration({
    onStart(event: OnStartEvent) {
      startStack.push({ startMs: Date.now(), modelId: event.model.modelId });
    },

    onStepFinish(event: OnStepFinishEvent) {
      const modelId = event.model.modelId;
      const { inputTokens, outputTokens } = event.usage;
      const cacheReadTokens = event.usage.inputTokenDetails.cacheReadTokens;
      const reasoningTokens = event.usage.outputTokenDetails?.reasoningTokens;
      log("step", {
        stepNumber: event.stepNumber,
        model: modelId,
        inputTokens,
        outputTokens,
        cacheReadTokens,
        reasoningTokens,
        costUsd: estimateCostUsd(
          inputTokens ?? 0,
          outputTokens ?? 0,
          cacheReadTokens ?? 0,
          resolvePricing(pricing, null, modelId),
          reasoningTokens,
        ),
        finishReason: event.finishReason,
        warnings: event.warnings ?? [],
      });
    },

    onToolCallFinish(event: OnToolCallFinishEvent) {
      if (!event.success) {
        log("tool_error", {
          toolName: event.toolCall.toolName,
          durationMs: event.durationMs,
          error: String(event.error),
        });
      }
    },

    onFinish(event: OnFinishEvent) {
      const ctx = startStack.pop();
      if (!ctx) {
        console.warn("[telemetry] onFinish fired with empty startStack — lifecycle mismatch");
        return;
      }
      const durationMs = Date.now() - ctx.startMs;
      const { inputTokens, outputTokens } = event.totalUsage;
      // Sum cost per step using each step's actual model (multi-model agents like convergence).
      const costUsd = (event.steps as StepLike[]).reduce<number>((acc, step) => {
        const p = resolvePricing(pricing, null, step.model.modelId);
        return (
          acc +
          estimateCostUsd(
            step.usage.inputTokens ?? 0,
            step.usage.outputTokens ?? 0,
            step.usage.inputTokenDetails?.cacheReadTokens ?? 0,
            p,
            step.usage.outputTokenDetails?.reasoningTokens,
          )
        );
      }, 0);
      log("generation_done", {
        model: ctx.modelId,
        inputTokens,
        outputTokens,
        costUsd,
        durationMs,
        steps: event.steps.length,
      });
    },
  });
}
