/**
 * Crystallize domain and pipeline types.
 *
 * Extracted from orchestrator.ts to keep runtime logic and type definitions separate.
 * orchestrator.ts imports from here; public consumers import from index.ts.
 */

import type { LanguageModel, TelemetrySettings } from "ai";
import type { ProviderPricing } from "./lib/cost.js";

// ─── Identity types ────────────────────────────────────────────

export interface CrystMechanism {
  name: string;
  prime: number;
  role: string;
  coupling: number;
}

export interface CrystConstraint {
  name: string;
  description: string;
}

export interface CrystSeed {
  prime_index: number;
  content: string;
  equation?: string | undefined;
  parallax?: { p_training: string; p_evidence: string } | undefined;
}

export interface CrystGene {
  symbol: string;
  name: string;
  formula: string;
  description: string;
  organism_role?: string | undefined;
  sub_equations?: string[] | undefined;
}

export interface CrystPersona {
  name: string;
  description: string;
  equation: string;
  voice_dna: string;
  mechanisms: CrystMechanism[];
  constraints: CrystConstraint[];
}

export interface CrystIdentity {
  persona: CrystPersona;
  seeds: CrystSeed[];
  genes: CrystGene[];
}

// ─── Domain types ─────────────────────────────────────────────

export interface CrystVertex {
  id: string;
  perspective: string;
  survived: boolean;
  reason: string;
  delta: number;
  edges?: string[] | undefined;
}

export interface CrystDimension {
  name: string;
  mu: number;
  sigma: number;
  description?: string | undefined;
}

export interface CrystQualityVector {
  elbo: number;
  holistic: number;
  accuracy: number;
  complexity: number;
  dimensions: Record<string, number>;
  weakest?: string[] | undefined;
  rejection_severity?: number | undefined;
  rejection_count?: number | undefined;
}

export interface CrystAntibody {
  pattern: string;
  type: "training_pull" | "default_override" | "bias_caught";
  strength: number;
}

export interface AdjustmentSignal {
  dimension: string;
  direction: "widen_sigma" | "narrow_sigma" | "shift_mu_up" | "shift_mu_down";
  magnitude: number;
}

// ─── Constants ───────────────────────────────────────────────

/** Shared functionId for the orchestrator agent — used by brain integration filter. */
export const ORCHESTRATOR_FUNCTION_ID = "crystallize.orchestrator";

// ─── Pipeline params + result ─────────────────────────────────

export type CrystMode = "generate" | "verify" | "compare" | "diagnose";
export type CrystOutput = "markdown" | "html" | "product" | "feature" | "text";
export type CrystStep = "navigate" | "encode" | "decode" | "measure" | "converge" | "output";

export interface CrystallizeParams {
  mode: CrystMode;
  output: CrystOutput;
  slug?: string | undefined;
  title?: string | undefined;
}

export interface CrystallizeResult {
  blockOutputs: Record<string, string>;
  blockThinking: Record<string, string>;
  iterations: number;
  converged: boolean;
  antibodies: CrystAntibody[];
  files?: { name: string; content: string }[] | undefined;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalCostUsd: number;
}

// ─── Storage callback ─────────────────────────────────────────

export interface StoreBlockParams {
  anchor: string;
  blockNumber: number;
  blockName: string;
  output: string;
  thinking: string;
  metrics: {
    duration_ms: number;
    input_tokens: number;
    output_tokens: number;
    thinking_chars: number;
    cost_usd: number;
  };
  jobId?: string | undefined;
  lineageId: string;
  model?: string | undefined;
}

// ─── Config ───────────────────────────────────────────────────

export interface CrystallizeConfig {
  /** Resolved LanguageModel. */
  model: LanguageModel;
  /** Per-step model overrides. */
  models?: Partial<Record<CrystStep, LanguageModel>> | undefined;
  /** Pricing for cost estimation (fallback for unknown models). */
  pricing?: ProviderPricing | undefined;
  /** Per-model pricing overrides keyed by modelId. Takes precedence over `pricing`. */
  pricingByModel?: Record<string, ProviderPricing> | undefined;
  /** Eli identity loaded by caller. Unused — identity is now loaded from eli-identity.md. */
  identity?: CrystIdentity | undefined;
  /** Lineage identifier for this run. */
  lineageId: string;
  /** Human-readable anchor/title for storage. */
  anchor: string;
  /** Pipeline params. */
  params: CrystallizeParams;
  /** Job identifier (optional). */
  jobId?: string | undefined;
  /** Wake-up context injected into identity block. */
  wakeUpContext?: string | undefined;
  /** Initial antibodies to seed the pipeline. */
  initialAntibodies?: CrystAntibody[] | undefined;
  /** Directory to write output files into (e.g. HTML pages). */
  outputDir?: string | undefined;
  /** OTel telemetry settings (optional — no-op if absent). */
  telemetry?: TelemetrySettings | undefined;
}

// ─── Pipeline context (flat mutable, flows via experimental_context) ──

export interface PipelineContext {
  // ── Config-derived (immutable after creation) ──
  identity?: CrystIdentity | undefined;
  lineageId: string;
  anchor: string;
  params: CrystallizeParams;
  jobId?: string | undefined;
  wakeUpContext?: string | undefined;
  outputDir?: string | undefined;

  // ── Resolved models ────────────────────────────
  model: LanguageModel;
  models?: Partial<Record<CrystStep, LanguageModel>> | undefined;
  pricing: ProviderPricing | null;
  pricingByModel: Record<string, ProviderPricing>;

  // ── Mutable pipeline state ─────────────────────
  outputs: Record<string, string>;
  thinking: Record<string, string>;
  convergenceIn: number;
  convergenceOut: number;
  iterations: number;
  converged: boolean;
  elboTrajectory: {
    iteration: number;
    primary?: number | undefined;
  }[];

  // ── Domain state (written by tools via experimental_context) ──
  vertices: CrystVertex[];
  encoding: CrystDimension[] | null;
  qualityVector: CrystQualityVector | null;
  elbo: number | null;
  crystAntibodies: CrystAntibody[];
  adjustmentSignals: AdjustmentSignal[];

  /** OTel telemetry settings (optional — no-op if absent). */
  telemetry?: TelemetrySettings | undefined;

  // ── Set by output tool ─────────────────────────
  outputFiles?: { name: string; content: string }[] | undefined;
  outputAntibodies?: CrystAntibody[] | undefined;
}
