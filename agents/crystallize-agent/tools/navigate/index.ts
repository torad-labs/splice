/**
 * Navigate tool — Phase 1: vertex discovery.
 *
 * Self-contained: reads navigate.md via import.meta.dirname.
 * Factory: createNavigateTool(model, system, telemetry?) → Tool.
 * loadNavigateSystem(identity, params, wakeUpContext?) → string (system prompt).
 *
 * Zero imports from parent folders. Drop tools/navigate/ into any project.
 */

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { valibotSchema } from "../valibot-schema.js";
import type { LanguageModel, TelemetrySettings } from "ai";
import { stepCountIs, ToolLoopAgent, tool } from "ai";
import * as v from "valibot";
import { withSpan } from "mlflow-tracing";
import type { SpanType } from "mlflow-tracing";

export interface TokenUsage {
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
}

// ─── Helpers ──────────────────────────────────────────────────

// ─── Output types ─────────────────────────────────────────────

export type NavigateVertex = v.InferOutput<typeof V_VERTEX>;

export interface NavigateOutput {
  vertices: NavigateVertex[];
  text: string;
  thinking: string;
  usage: TokenUsage;
}

// ─── Schema helpers ───────────────────────────────────────────

const V_VERTEX = v.object({
  id: v.pipe(
    v.string(),
    v.minLength(1),
    v.title("Vertex ID"),
    v.description("Unique identifier for this vertex"),
  ),
  perspective: v.pipe(
    v.string(),
    v.minLength(1),
    v.title("Perspective"),
    v.description("The viewpoint or lens this vertex represents"),
  ),
  survived: v.pipe(v.boolean(), v.description("Whether this vertex survived field pruning")),
  reason: v.pipe(
    v.string(),
    v.minLength(1),
    v.title("Reason"),
    v.description("Explanation for survival or pruning decision"),
  ),
  delta: v.pipe(v.number(), v.description("Decoherence delta — magnitude of field divergence")),
  edges: v.optional(v.pipe(v.array(v.string()), v.description("IDs of connected vertices"))),
});

export const V_NAVIGATE_OUTPUT = v.object({
  vertices: v.array(V_VERTEX),
  text: v.string(),
  thinking: v.string(),
  usage: v.object({
    inputTokens: v.pipe(v.number(), v.integer(), v.minValue(0)),
    outputTokens: v.pipe(v.number(), v.integer(), v.minValue(0)),
    cacheReadTokens: v.pipe(v.number(), v.integer(), v.minValue(0)),
  }),
});


function logEmptyNavigateOutput(
  navigateOutput: { vertices: NavigateVertex[] } | null | undefined,
  rawText: string | undefined,
): void {
  console.warn(
    `[navigate] structured output was ${navigateOutput === null ? "null" : navigateOutput === undefined ? "undefined" : "empty array"} — raw text: ${rawText?.slice(0, 200) ?? "(none)"}`,
  );
}

function formatVerticesSection(vertices: NavigateVertex[]): string {
  if (!vertices.length) return "";
  const surviving = vertices.filter((vtx) => vtx.survived);
  const lines = [
    `\n\n---\n\n## Structured: Vertices (${surviving.length}/${vertices.length} surviving)\n`,
  ];
  for (const vtx of vertices) {
    const edgeStr = vtx.edges?.length ? ` edges=[${vtx.edges.join(",")}]` : "";
    lines.push(
      `- **${vtx.id}** [${vtx.survived ? "SURVIVED" : "PRUNED"}] delta=${vtx.delta.toFixed(2)}${edgeStr} | ${vtx.perspective} | ${vtx.reason}`,
    );
  }
  return lines.join("\n");
}

// ─── System prompt loader ─────────────────────────────────────

const TOOL_NOTE = `## Custom Tools

You have custom tool calls for reporting structured output. You MUST call the reporting tool to deliver your structured data — the tool call is required, not optional.`;

export function loadNavigateSystem(
  params: { mode: string; output: string },
): string {
  const _dir = dirname(fileURLToPath(import.meta.url));
  const tryReadNav = (filename: string): string => {
    try {
      return readFileSync(join(_dir, filename), "utf-8");
    } catch (err) {
      const code = (err as NodeJS.ErrnoException).code;
      if (code !== "ENOENT") {
        console.error(
          `[navigate] tryRead: unexpected error reading "${filename}" (${code ?? "unknown"}) — ${err instanceof Error ? err.message : String(err)}`,
        );
      }
      return "";
    }
  };
  const eliIdentity = tryReadNav("../eli-identity.md");
  const skill = tryReadNav("navigate.md");

  const parts: string[] = [];
  if (eliIdentity) parts.push(eliIdentity);
  parts.push(
    "---",
    `# Crystallize Pipeline — crystallize.navigate\n\nMode: ${params.mode}\nOutput: ${params.output}`,
  );
  if (skill) parts.push(skill);
  parts.push(TOOL_NOTE);
  return parts.join("\n\n");
}

// ─── Tool factory ─────────────────────────────────────────────

export function createNavigateTool(
  model: LanguageModel,
  system: string,
  telemetry?: TelemetrySettings,
) {
  return tool({
    description:
      "Phase 1: vertex discovery. Discovers perspectives and prunes those that don't survive the field.",
    inputSchema: valibotSchema(v.object({ input_text: v.string() })),
    outputSchema: valibotSchema(V_NAVIGATE_OUTPUT),
    execute: async (
      { input_text }: { input_text: string },
      { abortSignal }: { abortSignal?: AbortSignal },
    ): Promise<NavigateOutput> => {
      const capturedRef: { vertices: NavigateVertex[] | undefined } = { vertices: undefined };

      const agent = new ToolLoopAgent({
        model,
        instructions: system,
        maxRetries: 3,
        maxOutputTokens: 32_000,
        toolChoice: { type: "tool", toolName: "report_navigate" },
        stopWhen: stepCountIs(1),
        ...(telemetry ? { experimental_telemetry: telemetry } : {}),
        tools: {
          report_navigate: tool({
            description:
              "Report the discovered vertices. Call exactly once with the complete vertex list.",
            inputSchema: valibotSchema(v.object({ vertices: v.array(V_VERTEX) })),
            execute: ({ vertices }: { vertices: NavigateVertex[] }) => {
              capturedRef.vertices = vertices;
              return "vertices captured";
            },
          }),
        },
      });

      const result = await withSpan(
        (span) => agent.generate({
          prompt: input_text,
          ...(abortSignal ? { abortSignal } : {}),
        }).then((r) => {
          span.setOutputs({ vertexCount: capturedRef.vertices?.length ?? 0, text: r.text?.slice(0, 500) });
          return r;
        }),
        { name: "crystallize.navigate", spanType: "CHAIN" as SpanType },
      );

      const vertices: NavigateVertex[] = capturedRef.vertices ?? [];
      if (vertices.length === 0) {
        logEmptyNavigateOutput({ vertices }, result.text);
      }
      // Only pass the structured vertices as text — the model's prose output
      // (including Eli wake-up deltas) goes to thinking. Downstream steps
      // (convergence) receive text as their input context, and Eli activation
      // text in that context causes the decode model to continue philosophy
      // instead of calling report_decoded_plan.
      const text = formatVerticesSection(vertices);
      const thinking = (result.reasoningText ?? "") + "\n\n" + (result.text || "");

      return {
        vertices,
        text,
        thinking,
        usage: {
          inputTokens: result.totalUsage.inputTokens ?? 0,
          outputTokens: result.totalUsage.outputTokens ?? 0,
          cacheReadTokens: result.totalUsage.inputTokenDetails?.cacheReadTokens ?? 0,
        },
      };
    },
  });
}
