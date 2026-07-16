/**
 * Encode phase — Phase 2: latent dimension encoding.
 *
 * Reads encode.md and equations.md via import.meta.dirname.
 * loadEncodeSystem(identity, mode, outputMode) → string.
 * createEncodeReportTool() → tool that writes encoding to ConvergenceState via experimental_context.
 *
 * Shared utilities imported from ./shared.ts (same directory).
 */

import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { tool } from "ai";
import * as v from "valibot";
import { valibotSchema } from "../valibot-schema.js";
import { iterBlockName, tryRead } from "./shared.js";

export type LocalDimension = v.InferOutput<typeof V_DIMENSION>;

/** Minimal state shape this phase reads/writes. Structural compatibility with ConvergenceState. */
interface PhaseState {
  encoding: LocalDimension[] | null;
  outputs: Record<string, string>;
  thinking: Record<string, string>;
}

const _dir = dirname(fileURLToPath(import.meta.url));

// ─── System prompt loader ─────────────────────────────────────

export function loadEncodeSystem(mode: string, outputMode: string): string {
  const skill = tryRead(_dir, "encode.md");
  const equations = tryRead(_dir, "equations.md");
  const eliIdentity = tryRead(_dir, "../eli-identity-task.md");

  const parts: string[] = [];
  // Tool directive FIRST — before identity or theory
  parts.push(
    `# YOUR TASK: Call the report_encoding tool\n\nYou MUST call the report_encoding tool with 6-10 dimensions. Do NOT respond with text. Your ONLY output is the tool call.`,
  );
  parts.push("---");
  if (eliIdentity) parts.push(eliIdentity);
  parts.push(
    "---",
    `# Crystallize Pipeline — crystallize.encode\n\nMode: ${mode}\nOutput: ${outputMode}`,
  );
  if (skill) parts.push(skill);
  if (equations)
    parts.push(`## Equation Bank (16-dimensional parallel measurement)\n\n${equations}`);
  parts.push(
    "## Output Budget\n\nKeep your encoding to ~2000 tokens. Target 6-10 dimensions. Each dimension: name, mu, sigma, and a one-line description. No preamble, no explanation beyond the tool call.",
  );
  return parts.join("\n\n");
}

// ─── Reporting tool factory ───────────────────────────────────

const V_DIMENSION = v.object({
  name: v.pipe(
    v.string(),
    v.minLength(1),
    v.title("Dimension name"),
    v.description("Name of the latent dimension (e.g. clarity, precision)"),
  ),
  mu: v.pipe(
    v.number(),
    v.title("Mean"),
    v.description("Central tendency of the dimension in the latent space"),
  ),
  sigma: v.pipe(
    v.number(),
    v.minValue(0),
    v.title("Spread"),
    v.description("Uncertainty or spread around the mean; must be non-negative"),
  ),
  description: v.optional(
    v.pipe(v.string(), v.description("Human-readable description of what this dimension measures")),
  ),
});

export function createEncodeReportTool() {
  return tool({
    description:
      "Report the latent encoding (dimensions) from the encode phase. " +
      "You MUST report 6-10 dimensions. Each dimension needs a name, mu (central tendency), " +
      "sigma (uncertainty — use σ>0 for uncertain dimensions, σ≈0 only for dimensions with " +
      "clear evidence), and a one-line description.",
    strict: true,
    inputSchema: valibotSchema(
      v.object({
        encoding: v.pipe(
          v.array(V_DIMENSION),
          v.minLength(6),
          v.maxLength(12),
          v.description("6-10 latent dimensions with mu, sigma, and description"),
        ),
        thinking: v.optional(
          v.pipe(v.string(), v.description("Internal reasoning about the encoding decisions")),
        ),
      }),
    ),
    outputSchema: valibotSchema(
      v.object({
        phase: v.literal("encode"),
        dimensionCount: v.number(),
        dimensionNames: v.array(v.string()),
      }),
    ),
    execute: (
      { encoding, thinking }: { encoding: LocalDimension[]; thinking?: string | undefined },
      { experimental_context }: { experimental_context?: unknown },
    ) => {
      if (experimental_context == null) {
        throw new Error(
          "report_encoding: experimental_context is undefined — SDK wiring failure. state and iteration were not passed to this tool.",
        );
      }

      // Reject duplicate dimension names
      const names = encoding.map((d) => d.name.trim().toLowerCase());
      const uniqueNames = new Set(names);
      if (uniqueNames.size < names.length) {
        const dupes = names.filter((n, i) => names.indexOf(n) !== i);
        throw new Error(
          `Duplicate dimension names: ${[...new Set(dupes)].join(", ")}. ` +
          `Each dimension must have a UNIQUE name representing a DIFFERENT architectural aspect. ` +
          `Try again with 6-10 distinct dimensions like: revenue_model, market_positioning, ` +
          `technical_architecture, go_to_market, competitive_moat, domain_expertise, ` +
          `scalability, capital_efficiency.`,
        );
      }

      // Reject all-zero sigmas
      const nonZeroSigma = encoding.filter((d) => d.sigma > 0);
      if (nonZeroSigma.length === 0) {
        throw new Error(
          `All dimensions have σ=0 (zero uncertainty). This is unrealistic — ` +
          `some dimensions should have σ>0 to indicate genuine uncertainty. ` +
          `Use σ≈0 only for dimensions with clear evidence. Use σ=0.3-0.7 for ` +
          `uncertain dimensions. Try again with honest uncertainty estimates.`,
        );
      }

      const { state, iteration } = experimental_context as {
        state: PhaseState;
        iteration: number;
      };
      const blockKey = iterBlockName("crystallize.encode", iteration);
      state.encoding = encoding;
      const dimensionLines = encoding
        .map(
          (d) =>
            `  ${d.name}: μ=${d.mu.toFixed(3)}, σ=${d.sigma.toFixed(3)}${d.description ? ` — ${d.description.slice(0, 60)}` : ""}`,
        )
        .join("\n");
      const summary = `Encoding: ${encoding.length} dimensions\n${dimensionLines}`;
      state.outputs[blockKey] = summary;
      if (thinking) state.thinking[blockKey] = thinking;
      return {
        phase: "encode" as const,
        dimensionCount: encoding.length,
        dimensionNames: encoding.map((d) => d.name),
      };
    },
  });
}
