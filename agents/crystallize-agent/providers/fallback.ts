/**
 * Fallback model wrapper — tries primary; on any error, retries with fallback.
 *
 * Uses wrapLanguageModel + middleware from "ai" (AI SDK v6 pattern).
 * If primary fails, immediately tries fallback with the same params.
 * If fallback also fails, exception propagates to the AI SDK retry loop.
 */

import type { LanguageModelV3 } from "@ai-sdk/provider";
import { wrapLanguageModel } from "ai";

export function createFallbackModel(
  primary: LanguageModelV3,
  fallback: LanguageModelV3,
): LanguageModelV3 {
  return wrapLanguageModel({
    model: primary,
    middleware: {
      specificationVersion: "v3",
      wrapGenerate: async ({ doGenerate, params }) => {
        try {
          return await doGenerate();
        } catch (err) {
          console.warn(
            `[fallback] ${primary.modelId} failed — trying ${fallback.modelId}: ${err instanceof Error ? err.message : String(err)}`,
          );
          return await fallback.doGenerate(params);
        }
      },
      wrapStream: async ({ doStream, params }) => {
        try {
          return await doStream();
        } catch (err) {
          console.warn(
            `[fallback] ${primary.modelId} failed (stream) — trying ${fallback.modelId}: ${err instanceof Error ? err.message : String(err)}`,
          );
          return await fallback.doStream(params);
        }
      },
    },
  });
}
