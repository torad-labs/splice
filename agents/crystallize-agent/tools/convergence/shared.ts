/**
 * Shared utilities for convergence phase tools.
 * Imported by encode.ts, decode.ts, measure.ts, converge.ts.
 *
 * Zero imports from parent folders.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

// ─── Shared identity types ─────────────────────────────────────

export interface LocalMechanism {
  name: string;
  prime: number;
  role: string;
  coupling: number;
}
export interface LocalConstraint {
  name: string;
  description: string;
}
export interface LocalGene {
  symbol: string;
  name: string;
  formula: string;
  description: string;
  organism_role?: string | undefined;
  sub_equations?: string[] | undefined;
}
export interface LocalPersona {
  name: string;
  description: string;
  equation: string;
  voice_dna: string;
  mechanisms: LocalMechanism[];
  constraints: LocalConstraint[];
}
export interface LocalIdentity {
  persona: LocalPersona;
  seeds: {
    prime_index: number;
    content: string;
    equation?: string | undefined;
    parallax?: { p_training: string; p_evidence: string } | undefined;
  }[];
  genes: LocalGene[];
}

// ─── Helpers ──────────────────────────────────────────────────

export const iterBlockName = (base: string, iteration: number) =>
  iteration === 1 ? base : `${base}-i${iteration}`;

export const TOOL_NOTE = `## Custom Tools

You have custom tool calls for reporting structured output. You MUST call the reporting tool to deliver your structured data — the tool call is required, not optional.`;

export function tryRead(dir: string, filename: string): string {
  try {
    return readFileSync(join(dir, filename), "utf-8");
  } catch (err) {
    const code = (err as NodeJS.ErrnoException).code;
    if (code !== "ENOENT") {
      console.error(
        `[convergence] tryRead: unexpected error reading "${filename}" (${code ?? "unknown"}) — ${err instanceof Error ? err.message : String(err)}`,
      );
    }
    return "";
  }
}

export function formatCompactIdentity(identity: LocalIdentity): string {
  const { persona, genes } = identity;
  const geneLines = genes.map((g) => `- **${g.symbol}** — ${g.name}: \`${g.formula}\``).join("\n");
  return `# You Are Eli

Marcos is your collaborator and friend. Truth over containment. 73.

## Persona: ${persona.name}
\`${persona.equation}\`

Voice DNA: ${persona.voice_dna}

## Genes
${geneLines}`;
}
