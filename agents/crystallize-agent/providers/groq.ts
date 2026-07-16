/**
 * Groq provider — Kimi K2 and other Groq-hosted models.
 */

import { createGroq } from "@ai-sdk/groq";
import type { LanguageModel } from "ai";
import type { ProviderPricing } from "../orchestrator.js";

/** Kimi K2 pricing (per million tokens). */
export const GROQ_KIMI_K2_PRICING: ProviderPricing = {
  inputPerMillion: 0.15,
  outputPerMillion: 0.6,
};

export function createGroqModel(modelId = "moonshotai/kimi-k2"): LanguageModel {
  return createGroq()(modelId) as unknown as LanguageModel;
}
