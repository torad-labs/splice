/**
 * Together.ai provider — DeepSeek-V3 and other Together-hosted models.
 *
 * Reads TOGETHER_API_KEY from environment automatically.
 */

import { createTogetherAI } from "@ai-sdk/togetherai";
import type { LanguageModel } from "ai";
import type { ProviderPricing } from "../orchestrator.js";

/** DeepSeek-V3 pricing on Together.ai (per million tokens). */
export const TOGETHER_DEEPSEEK_V3_PRICING: ProviderPricing = {
  inputPerMillion: 1.25,
  outputPerMillion: 1.25,
};

/** Llama 3.3 70B Instruct Turbo pricing on Together.ai (per million tokens). */
export const TOGETHER_LLAMA33_70B_PRICING: ProviderPricing = {
  inputPerMillion: 0.88,
  outputPerMillion: 0.88,
};

export function createTogetherModel(modelId = "deepseek-ai/DeepSeek-V3"): LanguageModel {
  return createTogetherAI()(modelId) as unknown as LanguageModel;
}
