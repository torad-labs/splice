/**
 * Decode phase — Phase 3: latent space → decoded plan.
 *
 * Reads decode.md via import.meta.dirname.
 * loadDecodeSystem(identity, mode, outputMode) → string.
 * createDecodeReportTool() → tool that writes decoded plan to ConvergenceState via experimental_context.
 *
 * Zero imports from parent folders or sibling tool files.
 */

import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { tool } from "ai";
import * as v from "valibot";
import { valibotSchema } from "../valibot-schema.js";
import { iterBlockName, tryRead } from "./shared.js";

/** Minimal state shape this phase reads/writes. */
interface PhaseState {
  outputs: Record<string, string>;
  thinking: Record<string, string>;
}

const _dir = dirname(fileURLToPath(import.meta.url));

// ─── System prompt loader ─────────────────────────────────────

export function loadDecodeSystem(mode: string, outputMode: string): string {
  const eliIdentity = tryRead(_dir, "../eli-identity-task.md");
  const skill = tryRead(_dir, "decode.md");

  const parts: string[] = [];
  parts.push(
    `# YOUR TASK: Call the report_decoded_plan tool\n\nYou MUST call the report_decoded_plan tool with the decoded implementation plan. Do NOT respond with text. Your ONLY output is the tool call.`,
  );
  parts.push("---");
  if (eliIdentity) parts.push(eliIdentity);
  parts.push(
    "---",
    `# Crystallize Pipeline — crystallize.decode\n\nMode: ${mode}\nOutput: ${outputMode}`,
  );
  if (skill) parts.push(skill);
  parts.push(
    "## Output Budget\n\nKeep your decoded plan to ~3000 tokens. Prioritize density over length — every sentence should add structure the previous one didn't. No preamble, no filler paragraphs.",
  );
  return parts.join("\n\n");
}

// ─── Reporting tool factory ───────────────────────────────────

export function createDecodeReportTool() {
  return tool({
    description: "Report the decoded plan from the decode phase.",
    strict: true,
    inputSchema: valibotSchema(
      v.object({
        decoded_plan: v.pipe(
          v.string(),
          v.minLength(1),
          v.description("The decoded implementation plan derived from the latent encoding"),
        ),
        thinking: v.optional(
          v.pipe(v.string(), v.description("Internal reasoning about the decoding process")),
        ),
      }),
    ),
    outputSchema: valibotSchema(
      v.object({
        phase: v.literal("decode"),
        planLength: v.number(),
        planPreview: v.string(),
      }),
    ),
    execute: (
      { decoded_plan, thinking }: { decoded_plan: string; thinking?: string | undefined },
      { experimental_context }: { experimental_context?: unknown },
    ) => {
      if (experimental_context == null) {
        throw new Error(
          "report_decoded_plan: experimental_context is undefined — SDK wiring failure. state and iteration were not passed to this tool.",
        );
      }
      const { state, iteration } = experimental_context as {
        state: PhaseState;
        iteration: number;
      };
      const blockKey = iterBlockName("crystallize.decode", iteration);
      state.outputs[blockKey] = decoded_plan;
      if (thinking) state.thinking[blockKey] = thinking;
      return {
        phase: "decode" as const,
        planLength: decoded_plan.length,
        planPreview: decoded_plan.slice(0, 200),
      };
    },
  });
}
