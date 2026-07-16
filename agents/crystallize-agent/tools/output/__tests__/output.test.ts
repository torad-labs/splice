/**
 * Output tool tests — per-file generation, voice state ordering, file collection, telemetry.
 *
 * Uses MockLanguageModelV3 from ai/test. No real API calls.
 */

import { describe, expect, it } from "vitest";
import { MockLanguageModelV3 } from "ai/test";
import type {
  LanguageModelV3CallOptions,
  LanguageModelV3GenerateResult,
} from "@ai-sdk/provider";
import {
  buildDocSystem,
  createGenerateFileTool,
  createOutputTool,
  extractModeSection,
  type OutputOutput,
} from "../index.js";

// ─── Helpers ──────────────────────────────────────────────────

const MOCK_HTML = `<!DOCTYPE html><html><head><title>Test</title></head><body><h1>Crystallization Report</h1><p>Decision Velocity at coupling 0.940 is the crossbar mechanism. All other mechanisms configure around this. Engineers need to extract a decision and act within 10 seconds of entering the document.</p></body></html>`;

const MOCK_USAGE = {
  inputTokens: { total: 100, noCache: 100, cacheRead: undefined, cacheWrite: undefined },
  outputTokens: { total: 500, text: 500, reasoning: undefined },
};

function makeTextResult(text: string): LanguageModelV3GenerateResult {
  return {
    content: [{ type: "text", text }],
    finishReason: { unified: "stop", raw: undefined },
    usage: MOCK_USAGE,
    warnings: [],
  };
}

function makeToolCallResult(
  toolName: string,
  args: Record<string, unknown>,
): LanguageModelV3GenerateResult {
  return {
    content: [
      {
        type: "tool-call",
        toolCallId: `tc_${toolName}`,
        toolName,
        input: JSON.stringify(args),
      },
    ],
    finishReason: { unified: "tool-calls", raw: undefined },
    usage: MOCK_USAGE,
    warnings: [],
  };
}

function extractSystemFromPrompt(options: LanguageModelV3CallOptions): string {
  return options.prompt
    .filter((m): m is { role: "system"; content: string } => m.role === "system")
    .map((m) => m.content)
    .join("\n");
}

// ─── extractModeSection ───────────────────────────────────────

describe("extractModeSection", () => {
  const DOC = `# Index Document

## generate
Hub page instructions for generate mode.
Lead with the core finding.

## verify
Verification summary instructions.

## compare
Comparison summary instructions.`;

  it("extracts the correct mode section", () => {
    const section = extractModeSection(DOC, "generate");
    expect(section).toContain("Hub page instructions for generate mode");
    expect(section).not.toContain("Verification summary");
  });

  it("returns empty string for unknown mode", () => {
    expect(extractModeSection(DOC, "nonexistent")).toBe("");
  });

  it("extracts last section correctly (no trailing ##)", () => {
    const section = extractModeSection(DOC, "compare");
    expect(section).toContain("Comparison summary instructions");
  });
});

// ─── buildDocSystem ───────────────────────────────────────────

describe("buildDocSystem", () => {
  it("places voice state BEFORE design system in system prompt", () => {
    const warn = () => undefined;
    const system = buildDocSystem("index", "generate", warn);

    const voiceIdx = system.indexOf("Product Voice State");
    const designIdx = system.indexOf("Design System Reference");

    expect(voiceIdx).toBeGreaterThan(-1);
    expect(designIdx).toBeGreaterThan(-1);
    expect(voiceIdx).toBeLessThan(designIdx);
  });

  it("includes doc-specific instructions for the mode", () => {
    const warn = () => undefined;
    const system = buildDocSystem("index", "generate", warn);
    expect(system).toContain("Document Instructions");
  });

  it("includes design system as CSS reference", () => {
    const warn = () => undefined;
    const system = buildDocSystem("prd", "generate", warn);
    expect(system).toContain("Design System Reference");
  });
});

// ─── createGenerateFileTool ───────────────────────────────────

describe("createGenerateFileTool", () => {
  it("calls generateText and returns { name, content }", async () => {
    const innerModel = new MockLanguageModelV3({
      doGenerate: makeTextResult(MOCK_HTML),
    });

    const genTool = createGenerateFileTool(
      innerModel,
      "index",
      "## Voice State\nTest voice",
      "Pipeline context here",
    );

    const result = (await genTool.execute!(
      { focus: "hub page summary" },
      { toolCallId: "test", messages: [] },
    )) as { name: string; content: string };
    expect(result).toEqual({ name: "index.html", content: MOCK_HTML });
  });

  it("passes voice state before design system in system prompt to inner generateText", async () => {
    let capturedSystem = "";
    const innerModel = new MockLanguageModelV3({
      doGenerate: async (options: LanguageModelV3CallOptions) => {
        capturedSystem = extractSystemFromPrompt(options);
        return makeTextResult(MOCK_HTML);
      },
    });

    const genTool = createGenerateFileTool(
      innerModel,
      "prd",
      "## Voice State\nDecision Velocity\n---\n## Design System Reference\nCSS vars",
      "Pipeline context",
    );

    await genTool.execute!(
      { focus: "architecture document" },
      { toolCallId: "test", messages: [] },
    );
    expect(capturedSystem).toContain("Decision Velocity");
    expect(capturedSystem).toContain("Design System Reference");
    expect(capturedSystem.indexOf("Decision Velocity")).toBeLessThan(
      capturedSystem.indexOf("Design System Reference"),
    );
  });

  it("passes experimental_telemetry — model is invoked", async () => {
    const innerModel = new MockLanguageModelV3({
      doGenerate: async () => makeTextResult(MOCK_HTML),
    });

    const genTool = createGenerateFileTool(
      innerModel,
      "rejection",
      "system",
      "pipeline",
      { isEnabled: true },
    );

    await genTool.execute!(
      { focus: "boundary document" },
      { toolCallId: "test", messages: [] },
    );

    expect(innerModel.doGenerateCalls.length).toBe(1);
  });

  it("uses correct file name per docType", async () => {
    const innerModel = new MockLanguageModelV3({
      doGenerate: makeTextResult(MOCK_HTML),
    });

    const indexTool = createGenerateFileTool(innerModel, "index", "sys", "ctx");
    const prdTool = createGenerateFileTool(innerModel, "prd", "sys", "ctx");
    const rejTool = createGenerateFileTool(innerModel, "rejection", "sys", "ctx");

    const opts = { toolCallId: "t", messages: [] as [] };
    const r1 = (await indexTool.execute!({ focus: "f" }, opts)) as { name: string };
    const r2 = (await prdTool.execute!({ focus: "f" }, opts)) as { name: string };
    const r3 = (await rejTool.execute!({ focus: "f" }, opts)) as { name: string };

    expect(r1.name).toBe("index.html");
    expect(r2.name).toBe("prd.html");
    expect(r3.name).toBe("rejection.html");
  });
});

// ─── createOutputTool (integration) ───────────────────────────

describe("createOutputTool", () => {
  it("produces OutputOutput with files for product mode", async () => {
    const model = new MockLanguageModelV3({
      doGenerate: async (options: LanguageModelV3CallOptions) => {
        const hasTools = options.tools && Object.keys(options.tools).length > 0;
        if (!hasTools) {
          return makeTextResult(MOCK_HTML);
        }

        const forcedTool =
          options.toolChoice &&
          typeof options.toolChoice === "object" &&
          "toolName" in options.toolChoice
            ? (options.toolChoice as { toolName: string }).toolName
            : undefined;

        if (forcedTool === "generate_index") {
          return makeToolCallResult("generate_index", { focus: "hub page" });
        }
        if (forcedTool === "generate_prd") {
          return makeToolCallResult("generate_prd", { focus: "constructive" });
        }
        if (forcedTool === "generate_rejection") {
          return makeToolCallResult("generate_rejection", { focus: "boundaries" });
        }
        if (forcedTool === "report_summary") {
          return makeToolCallResult("report_summary", {
            text: "Pipeline produced 3 substantive documents.",
            antibodies: [
              { pattern: "generic_summary", type: "training_pull", strength: 0.7 },
            ],
          });
        }

        return makeToolCallResult("report_summary", { text: "fallback", antibodies: [] });
      },
    });

    const system =
      "# Crystallize Pipeline — crystallize.output\n\nMode: generate\nOutput: product\n\nTest system prompt.";

    const outputTool = createOutputTool(model, system);

    const result = (await outputTool.execute!(
      { convergence_summary: "1 iteration, converged=true, ELBO=0.74" },
      {
        toolCallId: "ot1",
        messages: [],
        experimental_context: {
          pipelineContext: "Navigate found 5 vertices. Encode produced 16 dimensions.",
          elboSummary: "ELBO: 0.740",
          iterations: 1,
          converged: true,
          slug: "test-slug",
          outputMode: "product",
        },
      },
    )) as OutputOutput;

    expect(result.text).toContain("Pipeline produced");
    expect(result.antibodies.length).toBeGreaterThan(0);
    expect(result.files).toBeDefined();
    expect(result.files?.length).toBe(3);
    expect(result.files?.map((f) => f.name).sort()).toEqual([
      "index.html",
      "prd.html",
      "rejection.html",
    ]);
    for (const f of result.files ?? []) {
      expect(f.content.length).toBeGreaterThan(100);
    }
  }, 30_000);

  it("produces OutputOutput with text only for markdown mode", async () => {
    const model = new MockLanguageModelV3({
      doGenerate: async (options: LanguageModelV3CallOptions) => {
        const forcedTool =
          options.toolChoice &&
          typeof options.toolChoice === "object" &&
          "toolName" in options.toolChoice
            ? (options.toolChoice as { toolName: string }).toolName
            : undefined;

        if (forcedTool === "report_summary") {
          return makeToolCallResult("report_summary", {
            text: "## Feature Crystallization\n\nKey findings here.",
            antibodies: [],
          });
        }
        return makeToolCallResult("report_summary", { text: "fallback", antibodies: [] });
      },
    });

    const system =
      "# Crystallize Pipeline — crystallize.output\n\nMode: generate\nOutput: markdown\n\nTest.";

    const outputTool = createOutputTool(model, system);

    const result = (await outputTool.execute!(
      { convergence_summary: "1 iteration, ELBO=0.74" },
      {
        toolCallId: "ot2",
        messages: [],
        experimental_context: {
          pipelineContext: "Navigate output here.",
          elboSummary: "ELBO: 0.740",
          iterations: 1,
          converged: true,
          outputMode: "markdown",
        },
      },
    )) as OutputOutput;

    expect(result.text).toContain("Feature Crystallization");
    expect(result.files).toBeUndefined();
  }, 15_000);
});
