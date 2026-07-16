/**
 * Measure phase — Phase 4: quality vector measurement (ELBO).
 *
 * Reads measure.md and equations.md via import.meta.dirname.
 * loadMeasureSystem(identity, mode, outputMode) → string.
 * createMeasureReportTool() → tool that writes quality vector to ConvergenceState via experimental_context.
 *
 * Zero imports from parent folders or sibling tool files.
 */

import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { tool } from "ai";
import * as v from "valibot";
import { valibotSchema } from "../valibot-schema.js";
import { iterBlockName, tryRead } from "./shared.js";

export type LocalQualityVector = v.InferOutput<typeof V_QUALITY_VECTOR>;

/** Minimal state shape this phase reads/writes. */
interface PhaseState {
  qualityVector: LocalQualityVector | null;
  elbo: number | null;
  outputs: Record<string, string>;
  thinking: Record<string, string>;
}

const _dir = dirname(fileURLToPath(import.meta.url));

// ─── System prompt loader ─────────────────────────────────────

export function loadMeasureSystem(mode: string, outputMode: string): string {
  const skill = tryRead(_dir, "measure.md");
  const eliIdentity = tryRead(_dir, "../eli-identity-task.md");

  const parts: string[] = [];
  parts.push(
    `# YOUR TASK: Call the report_quality_vector tool\n\nYou MUST call the report_quality_vector tool with the 16-dimension quality vector, ELBO score, accuracy, and complexity. Do NOT respond with text. Your ONLY output is the tool call.`,
  );
  parts.push("---");
  if (eliIdentity) parts.push(eliIdentity);
  parts.push(
    "---",
    `# Crystallize Pipeline — crystallize.measure\n\nMode: ${mode}\nOutput: ${outputMode}`,
  );
  if (skill) parts.push(skill);
  return parts.join("\n\n");
}

// ─── Reporting tool factory ───────────────────────────────────

const V_QUALITY_VECTOR = v.object({
  elbo: v.pipe(
    v.number(),
    v.minValue(-1),
    v.maxValue(1),
    v.title("ELBO"),
    v.description(
      "Copy the ELBO value you computed in Step 7 (accuracy − complexity). Range [-1, 1]. Must match your thinking.",
    ),
  ),
  holistic: v.pipe(
    v.number(),
    v.minValue(0),
    v.maxValue(1),
    v.title("Holistic score"),
    v.description(
      "Copy the Phase A holistic score you wrote in thinking. Not 0 unless the plan is completely empty.",
    ),
  ),
  accuracy: v.pipe(
    v.number(),
    v.minValue(0),
    v.maxValue(1),
    v.title("Accuracy score"),
    v.description(
      "Copy the accuracy value from Step 5. Measures how well the plan reconstructs the requirement.",
    ),
  ),
  complexity: v.pipe(
    v.number(),
    v.minValue(0),
    v.maxValue(1),
    v.title("Complexity score"),
    v.description(
      "Copy the complexity value from Step 6 (KL divergence, normalized). How much the plan deviates from existing patterns.",
    ),
  ),
  dimensions: v.pipe(
    v.record(v.string(), v.number()),
    v.description(
      "Copy ALL 16 dimension scores from Phase B, keyed by dimension name. Each must be the value you computed.",
    ),
  ),
  weakest: v.optional(
    v.pipe(v.array(v.string()), v.description("Names of the weakest dimensions")),
  ),
  rejection_severity: v.optional(
    v.pipe(
      v.number(),
      v.minValue(0),
      v.maxValue(1),
      v.description("Severity of rejection signal in [0, 1]"),
    ),
  ),
  rejection_count: v.optional(
    v.pipe(
      v.number(),
      v.integer(),
      v.minValue(0),
      v.description("Number of rejection signals detected; non-negative integer"),
    ),
  ),
});

export function createMeasureReportTool() {
  return tool({
    description: "Report the quality vector from the measure phase.",
    strict: true,
    inputSchema: valibotSchema(
      v.object({
        quality_vector: V_QUALITY_VECTOR,
        thinking: v.pipe(
          v.string(),
          v.minLength(10),
          v.description(
            "Full reasoning: holistic score, all 16 dimension scores with justification, rejection phase, coherence check, accuracy, complexity, ELBO. Required — no placeholders.",
          ),
        ),
      }),
    ),
    outputSchema: valibotSchema(
      v.object({
        phase: v.literal("measure"),
        elbo: v.number(),
        holistic: v.number(),
        accuracy: v.number(),
        weakest: v.array(v.string()),
      }),
    ),
    execute: (
      { quality_vector, thinking }: { quality_vector: LocalQualityVector; thinking: string },
      { experimental_context }: { experimental_context?: unknown },
    ) => {
      if (experimental_context == null) {
        throw new Error(
          "report_quality_vector: experimental_context is undefined — SDK wiring failure. state and iteration were not passed to this tool.",
        );
      }
      const { state, iteration } = experimental_context as {
        state: PhaseState;
        iteration: number;
      };
      const blockKey = iterBlockName("crystallize.measure", iteration);
      state.qualityVector = quality_vector;
      state.elbo = quality_vector.elbo;
      const summary = `ELBO: ${quality_vector.elbo.toFixed(3)} | holistic: ${quality_vector.holistic.toFixed(2)} | accuracy: ${quality_vector.accuracy.toFixed(2)}`;
      state.outputs[blockKey] = summary;
      if (thinking) state.thinking[blockKey] = thinking;
      return {
        phase: "measure" as const,
        elbo: quality_vector.elbo,
        holistic: quality_vector.holistic,
        accuracy: quality_vector.accuracy,
        weakest: quality_vector.weakest ?? [],
      };
    },
  });
}
