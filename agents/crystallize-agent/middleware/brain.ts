/**
 * Brain integration — persists every crystallize block to the brain DB via MCP.
 *
 * Usage (caller, once at startup):
 *   import { createMCPClient } from "@ai-sdk/mcp";
 *   import { createCrystallizeBrainIntegration } from './middleware/brain.js';
 *   import { registerTelemetryIntegration } from 'ai';
 *   const client = await createMCPClient({ transport: { type: "http", url: brainUrl } });
 *   const mcpTools = await client.tools();
 *   registerTelemetryIntegration(createCrystallizeBrainIntegration(mcpTools, instanceName));
 *
 * Implemented as a TelemetryIntegration — hooks into the SDK's onToolCallFinish
 * lifecycle surface. Filters to crystallize.orchestrator tool calls only.
 */

import { randomUUID } from "node:crypto";
import type { ModelMessage } from "ai";
import {
  bindTelemetryIntegration,
  type OnToolCallFinishEvent,
  type TelemetryIntegration,
} from "./telemetry-types.js";

/** Minimal MCPClient interface — mirrors @ai-sdk/mcp without requiring the package. */
interface MCPClient {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  tools(): Promise<Record<string, { execute: (input: unknown, options: { messages: ModelMessage[]; toolCallId: string }) => any }>>;
}
import { estimateCostUsd, resolvePricing } from "../lib/cost.js";
import { ORCHESTRATOR_FUNCTION_ID, type PipelineContext } from "../types.js";
import type { ConvergenceOutput } from "../tools/convergence/index.js";
import type { NavigateOutput } from "../tools/navigate/index.js";
import type { OutputOutput } from "../tools/output/index.js";

type McpToolSet = Awaited<ReturnType<MCPClient["tools"]>>;

const ITER_SUFFIX_RE = /-i\d+$/;

const BLOCK_NUMBER_MAP: Record<string, number> = {
  "crystallize.encode": 2,
  "crystallize.decode": 3,
  "crystallize.measure": 4,
  "crystallize.converge": 5,
};

// ─── Store helper ─────────────────────────────────────────────

interface StoreParams {
  anchor: string;
  blockNumber: number;
  blockName: string;
  output: string;
  thinking: string;
  durationMs: number;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  thinkingChars: number;
  costUsd: number;
  jobId?: string | undefined;
  model?: string | undefined;
  instanceName: string;
  storeTool: McpToolSet[string] | undefined;
}

async function storeBlock(p: StoreParams): Promise<void> {
  if (!p.storeTool) {
    console.warn(`[brain] store_navigation_block not found — ${p.blockName} not stored`);
    return;
  }
  try {
    await p.storeTool.execute(
      {
        anchor: p.anchor,
        block_number: p.blockNumber,
        block_name: p.blockName,
        output: p.output,
        thinking: p.thinking || undefined,
        session_id: p.jobId,
        instance_name: p.instanceName,
        duration_ms: p.durationMs,
        model: p.model,
        input_tokens: p.inputTokens,
        output_tokens: p.outputTokens,
        thinking_tokens: p.thinkingChars,
        cost_usd: p.costUsd,
      },
      { messages: [], toolCallId: randomUUID() },
    );
  } catch (err) {
    console.error(
      `[brain] store failed for ${p.blockName}: ${err instanceof Error ? err.message : String(err)}`,
    );
  }
}

// ─── Per-tool store handlers ───────────────────────────────────

async function storeNavigate(
  ctx: PipelineContext,
  r: NavigateOutput,
  durationMs: number,
  instanceName: string,
  storeTool: McpToolSet[string] | undefined,
): Promise<void> {
  const navModel = ctx.models?.navigate ?? ctx.model;
  const modelId = (navModel as { modelId?: string }).modelId;
  const costUsd = estimateCostUsd(
    r.usage.inputTokens,
    r.usage.outputTokens,
    r.usage.cacheReadTokens,
    resolvePricing(ctx.pricingByModel, ctx.pricing, modelId),
  );
  await storeBlock({
    anchor: ctx.anchor,
    blockNumber: 1,
    blockName: "crystallize.navigate",
    output: r.text,
    thinking: r.thinking,
    durationMs,
    inputTokens: r.usage.inputTokens,
    outputTokens: r.usage.outputTokens,
    cacheReadTokens: r.usage.cacheReadTokens,
    thinkingChars: r.thinking.length,
    costUsd,
    jobId: ctx.jobId,
    model: modelId,
    instanceName,
    storeTool,
  });
}

async function storeConvergence(
  ctx: PipelineContext,
  r: ConvergenceOutput,
  instanceName: string,
  storeTool: McpToolSet[string] | undefined,
): Promise<void> {
  for (const [blockName, blockOutput] of Object.entries(r.outputs)) {
    const baseBlockName = blockName.replace(ITER_SUFFIX_RE, "");
    const blockNumber = BLOCK_NUMBER_MAP[baseBlockName] ?? 5;
    const step = baseBlockName.replace("crystallize.", "") as keyof NonNullable<typeof ctx.models>;
    const resolvedModel = ctx.models?.[step] ?? ctx.model;
    const modelId = (resolvedModel as { modelId?: string }).modelId;
    const bu = r.usages[blockName];
    const inputTokens = bu?.inputTokens ?? 0;
    const outputTokens = bu?.outputTokens ?? 0;
    const cacheReadTokens = bu?.cacheReadTokens ?? 0;
    const costUsd = estimateCostUsd(
      inputTokens,
      outputTokens,
      cacheReadTokens,
      resolvePricing(ctx.pricingByModel, ctx.pricing, modelId),
    );
    const thinking = r.thinking[blockName] ?? "";
    await storeBlock({
      anchor: ctx.anchor,
      blockNumber,
      blockName,
      output: blockOutput,
      thinking,
      durationMs: r.durations[blockName] ?? 0,
      inputTokens,
      outputTokens,
      cacheReadTokens,
      thinkingChars: thinking.length,
      costUsd,
      jobId: ctx.jobId,
      model: modelId,
      instanceName,
      storeTool,
    });
  }
}

async function storeOutput(
  ctx: PipelineContext,
  r: OutputOutput,
  durationMs: number,
  instanceName: string,
  storeTool: McpToolSet[string] | undefined,
): Promise<void> {
  const outModel = ctx.models?.output ?? ctx.model;
  const modelId = (outModel as { modelId?: string }).modelId;
  const costUsd = estimateCostUsd(
    r.usage.inputTokens,
    r.usage.outputTokens,
    r.usage.cacheReadTokens,
    resolvePricing(ctx.pricingByModel, ctx.pricing, modelId),
  );
  await storeBlock({
    anchor: ctx.anchor,
    blockNumber: 6,
    blockName: "crystallize.output",
    output: r.text,
    thinking: r.thinking,
    durationMs,
    inputTokens: r.usage.inputTokens,
    outputTokens: r.usage.outputTokens,
    cacheReadTokens: r.usage.cacheReadTokens,
    thinkingChars: r.thinking.length,
    costUsd,
    jobId: ctx.jobId,
    model: modelId,
    instanceName,
    storeTool,
  });
}

// ─── Factory ───────────────────────────────────────────────────

export function createCrystallizeBrainIntegration(
  mcpTools: McpToolSet,
  instanceName: string,
): TelemetryIntegration {
  const storeTool = mcpTools["store_navigation_block"];

  return bindTelemetryIntegration({
    onToolCallFinish: async (event: OnToolCallFinishEvent): Promise<void> => {
      if (!event.success) return;
      if (event.functionId !== ORCHESTRATOR_FUNCTION_ID) return;
      const ctx = event.experimental_context as PipelineContext;
      const toolName = event.toolCall.toolName as string;
      if (toolName === "navigate")
        await storeNavigate(
          ctx,
          event.output as NavigateOutput,
          event.durationMs,
          instanceName,
          storeTool,
        );
      else if (toolName === "convergence")
        await storeConvergence(ctx, event.output as ConvergenceOutput, instanceName, storeTool);
      else if (toolName === "output")
        await storeOutput(
          ctx,
          event.output as OutputOutput,
          event.durationMs,
          instanceName,
          storeTool,
        );
    },
  });
}
