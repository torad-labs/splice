/**
 * Canonical cost estimation — shared across orchestrator, brain, and telemetry.
 */

export interface ProviderPricing {
  inputPerMillion: number;
  outputPerMillion: number;
  cacheWritePerMillion?: number | undefined;
  cacheReadPerMillion?: number | undefined;
  reasoningPerMillion?: number | undefined;
}

export type PricingTable = Record<string, ProviderPricing>;

/**
 * Estimates cost in USD for a single model call.
 * cachedInputTokens are charged at cacheReadPerMillion (or inputPerMillion as fallback).
 * Fresh input = inputTokens - cachedInputTokens.
 * reasoningTokens are charged at reasoningPerMillion (or outputPerMillion as fallback).
 */
export function estimateCostUsd(
  inputTokens: number,
  outputTokens: number,
  cachedInputTokens: number,
  pricing: ProviderPricing | null,
  reasoningTokens?: number,
): number {
  if (!pricing) return 0;
  const freshInput = inputTokens - cachedInputTokens;
  const cacheCost = pricing.cacheReadPerMillion
    ? (cachedInputTokens / 1_000_000) * pricing.cacheReadPerMillion
    : (cachedInputTokens / 1_000_000) * pricing.inputPerMillion;
  const reasoningCost = reasoningTokens
    ? (reasoningTokens / 1_000_000) * (pricing.reasoningPerMillion ?? pricing.outputPerMillion)
    : 0;
  return (
    (freshInput / 1_000_000) * pricing.inputPerMillion +
    cacheCost +
    (outputTokens / 1_000_000) * pricing.outputPerMillion +
    reasoningCost
  );
}

/**
 * Resolves pricing for a model ID from a pricing table, falling back to defaultPricing.
 */
export function resolvePricing(
  pricingByModel: Record<string, ProviderPricing>,
  defaultPricing: ProviderPricing | null,
  modelId: string | undefined,
): ProviderPricing | null {
  return (modelId ? pricingByModel[modelId] : undefined) ?? defaultPricing;
}
