/**
 * Crystallize Agent — public API.
 */

// ── Telemetry helper (optional — callers register this globally) ──
export type { TelemetryLogFn } from "./middleware/telemetry.js";
export { createCrystallizeIntegration } from "./middleware/telemetry.js";
// ── Types ─────────────────────────────────────────────────────
export type {
  AdjustmentSignal,
  CrystAntibody,
  CrystallizeConfig,
  CrystallizeParams,
  CrystallizeResult,
  CrystConstraint,
  CrystDimension,
  CrystGene,
  CrystIdentity,
  CrystMechanism,
  CrystMode,
  CrystOutput,
  CrystPersona,
  CrystQualityVector,
  CrystSeed,
  CrystStep,
  CrystVertex,
  PipelineContext,
  StoreBlockParams,
} from "./types.js";
export type { ProviderPricing } from "./lib/cost.js";
// ── Main entry point ──────────────────────────────────────────
export { createPipelineContext, runCrystallize } from "./orchestrator.js";
// ── Providers ─────────────────────────────────────────────────
export { ANTHROPIC_PRICING, createAnthropicModel } from "./providers/anthropic.js";
export { createGroqModel, GROQ_KIMI_K2_PRICING } from "./providers/groq.js";
export { createProvider } from "./providers/index.js";
export {
  createTogetherModel,
  TOGETHER_DEEPSEEK_V3_PRICING,
  TOGETHER_LLAMA33_70B_PRICING,
} from "./providers/togetherai.js";
