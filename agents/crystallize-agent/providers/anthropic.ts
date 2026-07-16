/**
 * Anthropic provider — creates a wrapped LanguageModel with pricing metadata.
 *
 * Apply middleware via wrapLanguageModel BEFORE passing to createPipelineContext.
 * Middleware is wired in orchestrator.ts, not here.
 */

import { createAnthropic } from "@ai-sdk/anthropic";
import type { LanguageModel } from "ai";
import type { ProviderPricing } from "../orchestrator.js";

/** Claude Sonnet 4.5 pricing (per million tokens). Update when pricing changes. */
export const ANTHROPIC_PRICING: ProviderPricing = {
  inputPerMillion: 3.0,
  outputPerMillion: 15.0,
  cacheWritePerMillion: 3.75,
  cacheReadPerMillion: 0.3,
};

export function createAnthropicModel(modelId = "claude-sonnet-4-5"): LanguageModel {
  return createAnthropic()(modelId) as unknown as LanguageModel;
}
