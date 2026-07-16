/**
 * Converge phase — Phase 5: convergence decision.
 *
 * Reads converge.md via import.meta.dirname.
 * loadConvergeSystem(identity, mode, outputMode) → string.
 * createConvergeReportTool(maxIterations) → tool that writes convergence result to ConvergenceState.
 * MAX_ITERATIONS exported for use by convergence index.ts.
 *
 * Zero imports from parent folders or sibling tool files.
 */

import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { tool } from "ai";
import * as v from "valibot";
import { valibotSchema } from "../valibot-schema.js";
import { iterBlockName, tryRead } from "./shared.js";
export type LocalAntibody = v.InferOutput<typeof V_ANTIBODY>;
export type LocalAdjustmentSignal = v.InferOutput<typeof V_ADJUSTMENT>;

/** Minimal state shape this phase reads/writes. */
interface PhaseState {
  converged: boolean;
  iterations: number;
  elbo: number | null;
  elboTrajectory: { iteration: number; primary?: number | undefined }[];
  adjustmentSignals: LocalAdjustmentSignal[];
  antibodies: LocalAntibody[];
  outputs: Record<string, string>;
  thinking: Record<string, string>;
}

// ─── Constants ────────────────────────────────────────────────

export const MAX_ITERATIONS = 3;

const _dir = dirname(fileURLToPath(import.meta.url));

// ─── System prompt loader ─────────────────────────────────────

export function loadConvergeSystem(mode: string, outputMode: string): string {
  const skill = tryRead(_dir, "converge.md");
  const eliIdentity = tryRead(_dir, "../eli-identity-task.md");

  const parts: string[] = [];
  parts.push(
    `# YOUR TASK: Call the report_convergence tool\n\nYou MUST call the report_convergence tool with converged (true/false), ELBO score, and adjustment signals. Do NOT respond with text. Your ONLY output is the tool call.`,
  );
  parts.push("---");
  if (eliIdentity) parts.push(eliIdentity);
  parts.push(
    "---",
    `# Crystallize Pipeline — crystallize.converge\n\nMode: ${mode}\nOutput: ${outputMode}`,
  );
  if (skill) parts.push(skill);
  return parts.join("\n\n");
}

// ─── Reporting tool factory ───────────────────────────────────

const V_ADJUSTMENT = v.object({
  dimension: v.pipe(
    v.string(),
    v.minLength(1),
    v.title("Dimension name"),
    v.description("Name of the latent dimension to adjust"),
  ),
  direction: v.pipe(
    v.picklist(["widen_sigma", "narrow_sigma", "shift_mu_up", "shift_mu_down"]),
    v.title("Adjustment direction"),
    v.description("Direction of the adjustment signal"),
  ),
  magnitude: v.pipe(
    v.number(),
    v.minValue(0),
    v.title("Magnitude"),
    v.description("Strength of the adjustment; non-negative"),
  ),
});

const V_ANTIBODY = v.object({
  pattern: v.pipe(
    v.string(),
    v.minLength(1),
    v.title("Pattern"),
    v.description("The bias or training-pull pattern being flagged"),
  ),
  type: v.pipe(
    v.picklist(["training_pull", "default_override", "bias_caught"]),
    v.title("Antibody type"),
    v.description("Classification of the antibody"),
  ),
  strength: v.pipe(
    v.number(),
    v.minValue(0),
    v.maxValue(1),
    v.title("Strength"),
    v.description("Strength of the antibody signal in [0, 1]"),
  ),
});

function mergeAntibody(state: PhaseState, ab: LocalAntibody): void {
  const existingIdx = state.antibodies.findIndex((a) => a.pattern === ab.pattern);
  const existing = existingIdx !== -1 ? state.antibodies[existingIdx] : undefined;
  if (!existing) {
    state.antibodies.push(ab);
  } else if (ab.strength > existing.strength) {
    state.antibodies[existingIdx] = ab;
  }
}

function applyConvergenceReport(
  state: PhaseState,
  input: {
    converged: boolean;
    adjustment_signals?: LocalAdjustmentSignal[] | undefined;
    antibodies?: LocalAntibody[] | undefined;
    elbo?: number | undefined;
    thinking?: string | undefined;
  },
  iteration: number,
  blockKey: string,
): void {
  const { converged, adjustment_signals, antibodies, elbo, thinking } = input;
  state.converged = converged;
  state.iterations = iteration;

  if (adjustment_signals) state.adjustmentSignals.push(...adjustment_signals);

  if (antibodies) {
    for (const ab of antibodies) {
      mergeAntibody(state, ab);
    }
  }

  if (elbo !== undefined) state.elbo = elbo;

  state.elboTrajectory.push({
    iteration,
    primary: state.elbo ?? undefined,
  });

  const summary = `converged=${converged} | ELBO=${(state.elbo ?? 0).toFixed(3)} | iteration=${iteration}`;
  state.outputs[blockKey] = summary;
  if (thinking) state.thinking[blockKey] = thinking;
}

export function createConvergeReportTool(maxIterations: number) {
  return tool({
    description: "Report convergence. Returns 'STOP ...' when done, 'CONTINUE ...' to iterate.",
    strict: true,
    inputSchema: valibotSchema(
      v.object({
        converged: v.boolean(),
        adjustment_signals: v.optional(v.array(V_ADJUSTMENT)),
        antibodies: v.optional(v.array(V_ANTIBODY)),
        elbo: v.optional(v.number()),
        thinking: v.optional(v.string()),
      }),
    ),
    outputSchema: valibotSchema(
      v.object({
        phase: v.literal("converge"),
        action: v.picklist(["stop", "continue"]),
        iteration: v.number(),
        elbo: v.nullable(v.number()),
      }),
    ),
    execute: (
      {
        converged,
        adjustment_signals,
        antibodies,
        elbo,
        thinking,
      }: {
        converged: boolean;
        adjustment_signals?: LocalAdjustmentSignal[] | undefined;
        antibodies?: LocalAntibody[] | undefined;
        elbo?: number | undefined;
        thinking?: string | undefined;
      },
      { experimental_context }: { experimental_context?: unknown },
    ) => {
      if (experimental_context == null) {
        throw new Error(
          "report_convergence: experimental_context is undefined — SDK wiring failure. state and iteration were not passed to this tool.",
        );
      }
      const { state, iteration } = experimental_context as {
        state: PhaseState;
        iteration: number;
      };
      const blockKey = iterBlockName("crystallize.converge", iteration);

      try {
        applyConvergenceReport(
          state,
          { converged, adjustment_signals, antibodies, elbo, thinking },
          iteration,
          blockKey,
        );
      } catch (err) {
        state.outputs[blockKey] =
          `ERROR at iteration=${iteration}: ${err instanceof Error ? err.message : String(err)}`;
        throw err;
      }

      const done = converged || iteration >= maxIterations;
      return {
        phase: "converge" as const,
        action: done ? ("stop" as const) : ("continue" as const),
        iteration,
        elbo: state.elbo,
      };
    },
  });
}
