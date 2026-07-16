/**
 * Local telemetry types — TelemetryIntegration, bindTelemetryIntegration, and event types.
 *
 * These types are NOT exported by ai v6 but are defined internally.
 * We derive the event types from the exported callback type parameters.
 *
 * TelemetryIntegration and bindTelemetryIntegration are fully local — the ai SDK v6
 * does not have this abstraction. They are pure structural types used within this package.
 */

import type {
  ToolLoopAgentOnFinishCallback,
  ToolLoopAgentOnStartCallback,
  ToolLoopAgentOnStepFinishCallback,
  ToolLoopAgentOnStepStartCallback,
  ToolLoopAgentOnToolCallFinishCallback,
  ToolLoopAgentOnToolCallStartCallback,
  ToolSet,
} from "ai";

// Derive event types from the exported callback types.
export type OnStartEvent<TOOLS extends ToolSet = ToolSet> = Parameters<
  ToolLoopAgentOnStartCallback<TOOLS>
>[0];

export type OnStepStartEvent<TOOLS extends ToolSet = ToolSet> = Parameters<
  ToolLoopAgentOnStepStartCallback<TOOLS>
>[0];

export type OnToolCallStartEvent<TOOLS extends ToolSet = ToolSet> = Parameters<
  ToolLoopAgentOnToolCallStartCallback<TOOLS>
>[0];

export type OnToolCallFinishEvent<TOOLS extends ToolSet = ToolSet> = Parameters<
  ToolLoopAgentOnToolCallFinishCallback<TOOLS>
>[0];

export type OnStepFinishEvent<TOOLS extends ToolSet = ToolSet> = Parameters<
  ToolLoopAgentOnStepFinishCallback<TOOLS>
>[0];

export type OnFinishEvent<TOOLS extends ToolSet = ToolSet> = Parameters<
  ToolLoopAgentOnFinishCallback<TOOLS>
>[0];

/**
 * Lifecycle hooks for a telemetry integration.
 * All hooks are optional — implement only what you need.
 */
export interface TelemetryIntegration {
  onStart?: (event: OnStartEvent) => void | Promise<void>;
  onStepStart?: (event: OnStepStartEvent) => void | Promise<void>;
  onToolCallStart?: (event: OnToolCallStartEvent) => void | Promise<void>;
  onToolCallFinish?: (event: OnToolCallFinishEvent) => void | Promise<void>;
  onStepFinish?: (event: OnStepFinishEvent) => void | Promise<void>;
  onFinish?: (event: OnFinishEvent) => void | Promise<void>;
}

/**
 * Identity function — returns the integration as-is.
 * Provided for API compatibility with code that expects bindTelemetryIntegration().
 */
export function bindTelemetryIntegration(integration: TelemetryIntegration): TelemetryIntegration {
  return integration;
}
