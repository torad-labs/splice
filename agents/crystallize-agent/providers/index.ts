/**
 * Provider factory — resolves a LanguageModel from config.
 *
 * The model is created here (bare, no middleware).
 * Middleware is applied in orchestrator.ts via wrapLanguageModel.
 */

import type { LanguageModel } from "ai";
import type { ProviderPricing } from "../orchestrator.js";
import { ANTHROPIC_PRICING, createAnthropicModel } from "./anthropic.js";
import { createGroqModel, GROQ_KIMI_K2_PRICING } from "./groq.js";
import { createTogetherModel, TOGETHER_DEEPSEEK_V3_PRICING } from "./togetherai.js";

export type ProviderName = "anthropic" | "groq" | "togetherai";

export interface ProviderConfig {
  provider: ProviderName;
  modelId?: string | undefined;
}

export interface ResolvedProvider {
  model: LanguageModel;
  pricing: ProviderPricing;
}

export { createFallbackModel } from "./fallback.js";

export function createProvider(config: ProviderConfig): ResolvedProvider {
  switch (config.provider) {
    case "anthropic":
      return {
        model: createAnthropicModel(config.modelId),
        pricing: ANTHROPIC_PRICING,
      };
    case "groq":
      return {
        model: createGroqModel(config.modelId),
        pricing: GROQ_KIMI_K2_PRICING,
      };
    case "togetherai":
      return {
        model: createTogetherModel(config.modelId),
        pricing: TOGETHER_DEEPSEEK_V3_PRICING,
      };
    default:
      throw new Error(
        `Unknown provider: "${(config as { provider: string }).provider}". Valid options: anthropic, groq, togetherai.`,
      );
  }
}
