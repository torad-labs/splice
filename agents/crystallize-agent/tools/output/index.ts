/**
 * Output tool — Phase 6: final crystallized output.
 *
 * Self-contained: reads eli-identity.md, output-system-prompt.md, feature-system.md,
 * design-system.md, product-voice-state.md, doc-*.md via import.meta.dirname.
 *
 * Architecture (product/html modes):
 *   Outer ToolLoopAgent (lean orchestration prompt — NO design system, NO voice state)
 *     ├─ generate_index  → inner generateText (voice + doc instructions + design system)
 *     ├─ generate_prd    → inner generateText (voice + doc instructions + design system)
 *     ├─ generate_rejection → inner generateText (voice + doc instructions + design system)
 *     └─ report_summary  → captures text + antibodies
 *
 * Factory: createOutputTool(model, system, telemetry?) → Tool.
 * loadOutputSystem(params, warn?) → string (lean system prompt).
 * OutputToolContext is defined here — orchestrator imports this type.
 *
 * Zero imports from parent folders. Drop tools/output/ into any project.
 */

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import type {
  FlexibleSchema,
  LanguageModel,
  TelemetrySettings,
  ToolCallRepairFunction,
  ToolSet,
} from "ai";
import { generateText, NoSuchToolError, Output, ToolLoopAgent, tool } from "ai";
import { load as cheerioLoad } from "cheerio";
import type { SpanType } from "mlflow-tracing";
import { withSpan } from "mlflow-tracing";
import * as v from "valibot";
import { valibotSchema } from "../valibot-schema.js";

interface TokenUsage {
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
}

export interface LocalAntibody {
  pattern: string;
  type: "training_pull" | "default_override" | "bias_caught";
  strength: number;
}

// ─── Output types ─────────────────────────────────────────────

export interface LocalFile {
  name: string;
  content: string;
}

export interface OutputOutput {
  text: string;
  antibodies: LocalAntibody[];
  files?: LocalFile[] | undefined;
  thinking: string;
  usage: TokenUsage;
}

// ─── OutputToolContext — defined here, imported by orchestrator ──

export interface OutputToolContext {
  /** Pre-formatted pipeline context string (navigate + convergence outputs + quality vector). */
  pipelineContext: string;
  elboSummary: string;
  iterations: number;
  converged: boolean;
  slug?: string | undefined;
  outputMode: string;
}

// ─── Schema helpers ───────────────────────────────────────────

const V_ANTIBODY = v.object({
  pattern: v.pipe(
    v.string(),
    v.minLength(1),
    v.title("Pattern"),
    v.description("The bias or training-pull pattern being flagged"),
  ),
  type: v.pipe(
    v.picklist(["training_pull", "default_override", "bias_caught"]),
    v.title("Antibody type"),
    v.description("Classification of the antibody"),
  ),
  strength: v.pipe(
    v.number(),
    v.minValue(0),
    v.maxValue(1),
    v.title("Strength"),
    v.description("Signal strength in [0, 1]"),
  ),
});

export const V_OUTPUT_OUTPUT = v.object({
  text: v.pipe(v.string(), v.description("Crystallized output text")),
  thinking: v.string(),
  antibodies: v.array(V_ANTIBODY),
  files: v.optional(
    v.array(
      v.object({
        name: v.pipe(v.string(), v.minLength(1), v.description("File name")),
        content: v.pipe(v.string(), v.description("Complete file content")),
      }),
    ),
  ),
  usage: v.object({
    inputTokens: v.pipe(v.number(), v.integer(), v.minValue(0)),
    outputTokens: v.pipe(v.number(), v.integer(), v.minValue(0)),
    cacheReadTokens: v.pipe(v.number(), v.integer(), v.minValue(0)),
  }),
});

const V_OUTPUT_CONTEXT = v.object({
  pipelineContext: v.string(),
  elboSummary: v.string(),
  iterations: v.number(),
  converged: v.boolean(),
  slug: v.optional(v.string()),
  outputMode: v.string(),
});

// ─── Skill file loading ───────────────────────────────────────

const _dir = dirname(fileURLToPath(import.meta.url));

function tryRead(filename: string, warn: (msg: string) => void): string {
  try {
    return readFileSync(join(_dir, filename), "utf-8");
  } catch (err) {
    const code = (err as NodeJS.ErrnoException).code;
    if (code === "ENOENT") {
      warn(`output_skill_missing: ${filename}`);
    } else {
      console.error(
        `[output] tryRead: unexpected error reading "${filename}" (${code ?? "unknown"}) — ${err instanceof Error ? err.message : String(err)}`,
      );
    }
    return "";
  }
}

// ─── Product nav injection ────────────────────────────────────

/** Canonical mapping: docType → file name. Single source of truth. */
const DOC_TYPE_FILES: Record<string, string> = {
  index: "index.html",
  prd: "prd.html",
  rejection: "rejection.html",
};

const PRODUCT_REQUIRED_FILES = Object.values(DOC_TYPE_FILES);

/** Inverse of DOC_TYPE_FILES: file name → docType. Derived to prevent drift. */
const DOC_TYPE_MAP: Record<string, string> = Object.fromEntries(
  Object.entries(DOC_TYPE_FILES).map(([docType, file]) => [file, docType]),
);

const SLUG_SAFE_RE = /^[a-zA-Z0-9_-]+$/;
const HTML_EXT_RE = /\.html$/u;

function injectProductNav(files: LocalFile[], slug: string | undefined): void {
  if (!slug) return;
  if (!SLUG_SAFE_RE.test(slug)) {
    throw new Error(`injectProductNav: slug contains unsafe characters — "${slug}"`);
  }
  for (const f of files) {
    const docType = DOC_TYPE_MAP[f.name] ?? f.name.replace(HTML_EXT_RE, "");
    const $ = cheerioLoad(f.content);
    const active = (doc: string) =>
      doc === docType ? "color:#fff;font-weight:600;" : "color:#8b8ba7;";
    const link = (href: string, label: string, doc: string) =>
      `<a href="${href}" style="text-decoration:none;${active(doc)}"${doc === docType ? ' aria-current="page"' : ""}>${label}</a>`;
    const nav = `<nav style="display:flex;gap:1rem;padding:0.75rem 1.5rem;background:#1a1a2e;border-bottom:1px solid #333;font-family:system-ui,sans-serif;font-size:0.85rem;">
  ${link(`/navigations/${slug}`, "Summary", "index")}
  ${link(`/navigations/${slug}-prd`, "PRD", "prd")}
  ${link(`/navigations/${slug}-rejection`, "Rejection Framework", "rejection")}
</nav>`;
    $("body").prepend(nav);
    f.content = $.html();
  }
}

// ─── System prompt loader (lean orchestration — no voice/design system) ──

const TOOL_NOTE = `## Custom Tools

You have custom tool calls for reporting structured output. You MUST call the reporting tool to deliver your structured data — the tool call is required, not optional.`;

export function loadOutputSystem(
  params: { mode: string; output: string },
  warn: (msg: string) => void = () => undefined,
): string {
  const eliIdentity = tryRead("../eli-identity.md", warn);
  const skill = tryRead("output-system-prompt.md", warn);

  const parts: string[] = [];
  if (eliIdentity) parts.push(eliIdentity);
  parts.push(
    "---",
    `# Crystallize Pipeline — crystallize.output\n\nMode: ${params.mode}\nOutput: ${params.output}`,
  );

  if (skill) parts.push(skill);

  // Feature mode: include feature system in outer prompt (model generates inline)
  if (params.output === "feature") {
    const fs = tryRead("feature-system.md", warn);
    if (fs) parts.push(`## Feature System Reference\n\n${fs}`);
  }

  parts.push(TOOL_NOTE);
  return parts.join("\n\n");
}

// ─── Per-file system prompt builder ───────────────────────────

/** @internal Exported for testing. */
export function extractModeSection(doc: string, mode: string): string {
  const pattern = new RegExp(`^## ${mode}\\b`, "m");
  const match = doc.match(pattern);
  if (!match || match.index === undefined) return "";
  const start = match.index + match[0].length;
  const nextHeader = doc.indexOf("\n## ", start);
  const end = nextHeader !== -1 ? nextHeader : doc.length;
  return doc.slice(start, end).trim();
}

function parseMode(system: string, warn: (msg: string) => void): string {
  const match = system.match(/(?:^|\n)Mode:\s*(\w+)/);
  if (!match?.[1]) {
    warn("could not extract mode from system prompt — defaulting to 'generate'");
    return "generate";
  }
  return match[1];
}

/** Pre-read shared files so they're loaded once, not once per doc type. */
interface SharedResources {
  voiceState: string;
  designSystem: string;
}

function readSharedResources(warn: (msg: string) => void): SharedResources {
  return {
    voiceState: tryRead("product-voice-state.md", warn),
    designSystem: tryRead("design-system.md", warn),
  };
}

/** Human-readable doc type labels for system prompt directives. */
const DOC_TYPE_LABELS: Record<string, string> = {
  index: "index.html (the hub/summary page)",
  prd: "prd.html (the constructive architecture page)",
  rejection: "rejection.html (the boundaries/rejection page)",
};

/** @internal Exported for testing. */
export function buildDocSystem(
  docType: "index" | "prd" | "rejection",
  mode: string,
  warn: (msg: string) => void,
  shared?: SharedResources,
): string {
  const { voiceState: vs, designSystem: ds } = shared ?? readSharedResources(warn);
  const parts: string[] = [];

  // 1. DIRECTIVE — what this model is doing (prevents generating all docs)
  const docLabel = DOC_TYPE_LABELS[docType] ?? `${docType}.html`;
  parts.push(
    `# Your Task\n\n` +
    `You are generating ONLY ${docLabel}. Do NOT generate any other document. ` +
    `Do NOT generate index.html, prd.html, and rejection.html together. ` +
    `Output ONLY the raw HTML for ${docType}.html — no markdown, no code fences, no \`\`\`html wrapper. ` +
    `Start your response with <!DOCTYPE html> and end with </html>.`,
  );

  parts.push("---");

  // 2. Doc-specific instructions for current mode
  const docInstructions = tryRead(`doc-${docType}.md`, warn);
  if (docInstructions) {
    const modeSection = extractModeSection(docInstructions, mode);
    if (modeSection) {
      parts.push(`## Document Instructions\n\n${modeSection}`);
    }
  }

  parts.push("---");

  // 3. Design system (CSS reference material — middle position)
  if (ds) parts.push(`## Design System Reference\n\n${ds}`);

  parts.push("---");

  // 4. Voice state LAST (highest recency attention — governs every sentence)
  if (vs) parts.push(`## Product Voice State — APPLY TO EVERY SENTENCE\n\n${vs}`);

  // Guard: if all content files are missing, the system prompt is just separators
  if (!vs && !docInstructions && !ds) {
    warn(
      `buildDocSystem(${docType}): all resource files missing — inner generateText will have no voice/design guidance`,
    );
  }

  return parts.join("\n\n");
}

// ─── Per-file generate tool factory ───────────────────────────

/** Strip markdown code fences that models wrap around HTML output. */
function stripCodeFences(text: string): string {
  let result = text.trim();
  // Remove leading ```html or ``` (with optional language tag)
  result = result.replace(/^```(?:html|HTML)?\s*\n?/, "");
  // Remove trailing ```
  result = result.replace(/\n?```\s*$/, "");
  return result.trim();
}

/** @internal Exported for testing. */
export function createGenerateFileTool(
  model: LanguageModel,
  docType: "index" | "prd" | "rejection",
  docSystem: string,
  pipelineContext: string,
  telemetry?: TelemetrySettings,
) {
  const fileName = DOC_TYPE_FILES[docType] ?? `${docType}.html`;
  return tool({
    description: `Generate the ${docType} HTML document.`,
    inputSchema: valibotSchema(
      v.object({
        focus: v.pipe(
          v.string(),
          v.description("Key findings and content plan for this document"),
        ),
      }),
    ),
    outputSchema: valibotSchema(
      v.object({
        name: v.pipe(v.literal(fileName), v.description("File name")),
        content: v.pipe(
          v.string(),
          v.minLength(100),
          v.description("Complete HTML document content"),
        ),
      }),
    ),
    execute: async ({ focus }: { focus: string }) => {
      try {
        const { text } = await generateText({
          model,
          system: docSystem,
          prompt: `${pipelineContext}\n\n---\n\nDocument focus:\n${focus}`,
          ...(telemetry
            ? {
                experimental_telemetry: {
                  ...telemetry,
                  functionId: `crystallize.output.${docType}`,
                  recordInputs: false,
                  recordOutputs: true,
                },
              }
            : {}),
        });
        return { name: fileName, content: stripCodeFences(text) };
      } catch (err) {
        throw new Error(
          `generate_${docType} failed: inner generateText error — ${err instanceof Error ? err.message : String(err)}`,
          { cause: err },
        );
      }
    },
  });
}

// ─── Output execute helpers ───────────────────────────────────

function parseOutputContext(experimental_context: unknown): OutputToolContext {
  if (experimental_context == null) {
    throw new Error(
      "output: experimental_context is undefined — SDK wiring failure. ctx was not passed to this tool.",
    );
  }
  try {
    return v.parse(V_OUTPUT_CONTEXT, experimental_context);
  } catch (err) {
    throw new Error(
      `output: experimental_context has wrong shape — ${err instanceof v.ValiError ? JSON.stringify(v.flatten(err.issues)) : String(err)}`,
      { cause: err },
    );
  }
}

function buildOutputPrompt(ctx: OutputToolContext): string {
  let fileInstruction: string;
  switch (ctx.outputMode) {
    case "feature":
      fileInstruction =
        "Submit each file with submit_file (one at a time), then call report_summary.";
      break;
    case "product":
    case "html":
      fileInstruction =
        "Use the generate_* tools to produce each HTML document, then call report_summary.";
      break;
    default:
      fileInstruction =
        "Call report_summary with text set to the complete output. Do NOT submit any files — markdown mode requires only report_summary.";
      break;
  }
  return `${ctx.pipelineContext}\n\n---\n\nConvergence: ${ctx.iterations} iteration(s), converged=${ctx.converged}, ${ctx.elboSummary}\n\nGenerate the final crystallized output. ${fileInstruction}`;
}

function validateFileName(name: string): string | null {
  if (name.includes("/") || name.includes("..") || name.includes("\\")) {
    return `Error: invalid file name "${name}" — path separators and ".." are not allowed.`;
  }
  return null;
}

/** Only used in feature mode — model generates files inline and submits them one at a time. */
function buildSubmitFileTool(submittedFiles: LocalFile[]) {
  return tool({
    description:
      "Submit one generated file. Call once per file — do not batch multiple files into one call.",
    inputSchema: valibotSchema(
      v.object({
        name: v.pipe(v.string(), v.description("File name, e.g. index.html")),
        content: v.pipe(v.string(), v.description("Complete file content")),
      }),
    ),
    execute: ({ name, content }: { name: string; content: string }) => {
      const validationError = validateFileName(name);
      if (validationError) return validationError;
      submittedFiles.push({ name, content });
      const submittedNames = submittedFiles.map((f) => f.name);
      const remaining = PRODUCT_REQUIRED_FILES.filter((f) => !submittedNames.includes(f));
      if (remaining.length > 0) {
        return `${name} received. Still required: ${remaining.join(", ")} — submit with submit_file before calling report_summary.`;
      }
      return `${name} received. All required files submitted — call report_summary now.`;
    },
  });
}

const REQUIRED_FILES_BY_MODE: Record<string, string[]> = {
  product: PRODUCT_REQUIRED_FILES,
  html: ["index.html"],
  feature: PRODUCT_REQUIRED_FILES,
};

function buildReportSummaryTool(
  ctx: OutputToolContext,
  submittedFiles: LocalFile[],
  capturedSummaryRef: { value: { text: string; antibodies?: LocalAntibody[] | undefined } | null },
) {
  const required = REQUIRED_FILES_BY_MODE[ctx.outputMode] ?? [];
  return tool({
    description:
      "Report the text summary and antibodies. Call ONLY after all required documents have been generated.",
    inputSchema: valibotSchema(
      v.object({ text: v.string(), antibodies: v.optional(v.array(V_ANTIBODY)) }),
    ),
    execute: (input: { text: string; antibodies?: LocalAntibody[] | undefined }) => {
      const submittedNames = submittedFiles.map((f) => f.name);
      const missing = required.filter((n) => !submittedNames.includes(n));
      if (missing.length > 0) {
        throw new Error(
          `report_summary called before all files ready — missing: ${missing.join(", ")}`,
        );
      }
      capturedSummaryRef.value = input;
      return "done";
    },
  });
}

function buildRepairToolCall(repairModel: LanguageModel): ToolCallRepairFunction<ToolSet> {
  return async ({ toolCall, tools, inputSchema, error }) => {
    if (NoSuchToolError.isInstance(error)) {
      console.warn(`[output] repair: model tried nonexistent tool "${toolCall.toolName}" — skipping`);
      return null;
    }
    const targetTool = tools[toolCall.toolName];
    if (!targetTool) return null;
    try {
      const { output: repairedArgs } = await generateText({
        model: repairModel,
        output: Output.object({ schema: targetTool.inputSchema as FlexibleSchema<unknown> }),
        prompt: [
          `The model tried to call the tool "${toolCall.toolName}" with the following inputs:`,
          JSON.stringify(toolCall.input),
          `The tool accepts the following schema:`,
          JSON.stringify(await inputSchema({ toolName: toolCall.toolName })),
          "Please fix the inputs.",
        ].join("\n"),
      });
      return { ...toolCall, input: JSON.stringify(repairedArgs) };
    } catch (repairErr) {
      console.warn(
        `[output] repair: failed to repair "${toolCall.toolName}" — ${
          repairErr instanceof Error ? repairErr.message : String(repairErr)
        }`,
      );
      return null;
    }
  };
}

interface CapturedSummaryRef {
  value: { text: string; antibodies?: LocalAntibody[] | undefined } | null;
}

// ─── Per-file generate tools builder ──────────────────────────

type DocType = "index" | "prd" | "rejection";

function buildGenerateTools(
  model: LanguageModel,
  mode: string,
  docTypes: readonly DocType[],
  pipelineContext: string,
  telemetry: TelemetrySettings | undefined,
  warn: (msg: string) => void,
): Record<string, ReturnType<typeof createGenerateFileTool>> {
  // Read voice state (4.5K) and design system (57K) once, share across all doc types
  const shared = readSharedResources(warn);
  const tools: Record<string, ReturnType<typeof createGenerateFileTool>> = {};
  for (const docType of docTypes) {
    const docSystem = buildDocSystem(docType, mode, warn, shared);
    tools[`generate_${docType}`] = createGenerateFileTool(
      model,
      docType,
      docSystem,
      pipelineContext,
      telemetry,
    );
  }
  return tools;
}

// ─── Mode config builder ──────────────────────────────────────

type PrepareStepFn = () => {
  activeTools?: string[];
  toolChoice?: { type: "tool"; toolName: string };
};

interface ModeConfig {
  agentTools: ToolSet;
  prepareStepFn: PrepareStepFn;
}

function buildModeConfig(
  ctx: OutputToolContext,
  model: LanguageModel,
  mode: string,
  submittedFiles: LocalFile[],
  capturedSummaryRef: CapturedSummaryRef,
  telemetry: TelemetrySettings | undefined,
  warn: (msg: string) => void,
): ModeConfig {
  const reportSummary = buildReportSummaryTool(ctx, submittedFiles, capturedSummaryRef);

  if (ctx.outputMode === "product" || ctx.outputMode === "html") {
    const docTypes: readonly DocType[] =
      ctx.outputMode === "product"
        ? (["index", "prd", "rejection"] as const)
        : (["index"] as const);

    const generateTools = buildGenerateTools(
      model,
      mode,
      docTypes,
      ctx.pipelineContext,
      telemetry,
      warn,
    );

    return {
      agentTools: { ...generateTools, report_summary: reportSummary },
      // Force sequential generation: generate_index → generate_prd → generate_rejection → report_summary
      // Check submittedFiles (actual collected output), not tool call history — if a generate_*
      // tool fails, its file won't be in submittedFiles and prepareStep will retry it.
      prepareStepFn: () => {
        for (const dt of docTypes) {
          const fileName = DOC_TYPE_FILES[dt] ?? `${dt}.html`;
          if (!submittedFiles.some((f) => f.name === fileName)) {
            return {
              activeTools: [`generate_${dt}`],
              toolChoice: { type: "tool" as const, toolName: `generate_${dt}` },
            };
          }
        }
        return {
          activeTools: ["report_summary"],
          toolChoice: { type: "tool" as const, toolName: "report_summary" },
        };
      },
    };
  }

  if (ctx.outputMode === "feature") {
    return {
      agentTools: {
        submit_file: buildSubmitFileTool(submittedFiles),
        report_summary: reportSummary,
      },
      // Feature mode: submit files then report_summary
      prepareStepFn: () => {
        const submittedNames = submittedFiles.map((f) => f.name);
        const allSubmitted = PRODUCT_REQUIRED_FILES.every((n) => submittedNames.includes(n));
        if (allSubmitted) {
          return {
            activeTools: ["report_summary"],
            toolChoice: { type: "tool" as const, toolName: "report_summary" },
          };
        }
        return {
          activeTools: ["submit_file"],
          toolChoice: { type: "tool" as const, toolName: "submit_file" },
        };
      },
    };
  }

  // Markdown/raw/text: just report_summary
  return {
    agentTools: { report_summary: reportSummary },
    prepareStepFn: () => ({
      activeTools: ["report_summary"],
      toolChoice: { type: "tool" as const, toolName: "report_summary" },
    }),
  };
}

// ─── Output generation ───────────────────────────────────────

async function runOutputGeneration(
  model: LanguageModel,
  system: string,
  ctx: OutputToolContext,
  submittedFiles: LocalFile[],
  capturedSummaryRef: CapturedSummaryRef,
  abortSignal: AbortSignal | undefined,
  telemetry?: TelemetrySettings,
) {
  const prompt = buildOutputPrompt(ctx);
  const effectiveSignal = abortSignal
    ? AbortSignal.any([abortSignal, AbortSignal.timeout(1_800_000)])
    : AbortSignal.timeout(1_800_000);

  const warn = (msg: string) => console.warn(`[output] ${msg}`);
  const mode = parseMode(system, warn);

  // Build tools and prepareStep based on output mode
  const { agentTools, prepareStepFn } = buildModeConfig(
    ctx,
    model,
    mode,
    submittedFiles,
    capturedSummaryRef,
    telemetry,
    warn,
  );

  const agent = new ToolLoopAgent({
    model,
    instructions: system,
    temperature: 0.6,
    topP: 0.95,
    maxRetries: 2,
    experimental_repairToolCall: buildRepairToolCall(model),
    stopWhen: () => capturedSummaryRef.value !== null,
    prepareStep: prepareStepFn,
    experimental_onToolCallFinish: (event) => {
      if (!event.success) {
        console.error(
          `[output] ${event.toolCall.toolName} failed — ${
            event.error instanceof Error ? event.error.message : String(event.error ?? "unknown")
          }`,
        );
        return;
      }
      const output = event.output;
      if (
        typeof output === "object" &&
        output !== null &&
        "name" in output &&
        "content" in output
      ) {
        const file = output as LocalFile;
        if (!submittedFiles.some((f) => f.name === file.name)) {
          submittedFiles.push(file);
        }
      }
    },
    ...(telemetry ? { experimental_telemetry: telemetry } : {}),
    tools: agentTools,
  });

  return agent.generate({ prompt, abortSignal: effectiveSignal });
}

// ─── Tool factory ─────────────────────────────────────────────

export function createOutputTool(
  model: LanguageModel,
  system: string,
  telemetry?: TelemetrySettings,
) {
  return tool({
    description: "Phase 6: final crystallized output. Run after convergence.",
    inputSchema: valibotSchema(
      v.object({
        convergence_summary: v.pipe(
          v.string(),
          v.description("Convergence results: iterations, ELBO, converged status."),
        ),
      }),
    ),
    outputSchema: valibotSchema(V_OUTPUT_OUTPUT),
    execute: async (
      _input: unknown,
      {
        experimental_context,
        abortSignal,
      }: { experimental_context?: unknown; abortSignal?: AbortSignal },
    ): Promise<OutputOutput> => {
      const ctx = parseOutputContext(experimental_context);

      const submittedFiles: LocalFile[] = [];
      const capturedSummaryRef: CapturedSummaryRef = { value: null };

      const result = await withSpan(
        (span) =>
          runOutputGeneration(
            model,
            system,
            ctx,
            submittedFiles,
            capturedSummaryRef,
            abortSignal,
            telemetry,
          ).then((r) => {
            span.setOutputs({
              fileCount: submittedFiles.length,
              text: capturedSummaryRef.value?.text?.slice(0, 500) ?? "",
            });
            return r;
          }),
        { name: "crystallize.output", spanType: "CHAIN" as SpanType },
      );

      if (!capturedSummaryRef.value) {
        if (result.finishReason === "length") {
          throw new Error(
            "crystallize.output: context window exceeded — report_summary was never called",
          );
        }
        throw new Error("report_summary was never called");
      }

      const { text, antibodies } = capturedSummaryRef.value;
      const resolvedFiles: LocalFile[] = [...submittedFiles];

      if (ctx.outputMode === "product" && resolvedFiles.length > 0) {
        try {
          injectProductNav(resolvedFiles, ctx.slug);
        } catch (err) {
          throw new Error(
            `injectProductNav failed — generated HTML may be malformed: ${
              err instanceof Error ? err.message : String(err)
            }`,
            { cause: err },
          );
        }
      }

      return {
        text,
        antibodies: antibodies ?? [],
        files: resolvedFiles.length > 0 ? resolvedFiles : undefined,
        thinking: result.reasoningText ?? "",
        usage: {
          inputTokens: result.totalUsage.inputTokens ?? 0,
          outputTokens: result.totalUsage.outputTokens ?? 0,
          cacheReadTokens: result.steps.reduce(
            (acc, step) => acc + (step.usage.inputTokenDetails?.cacheReadTokens ?? 0),
            0,
          ),
        },
      };
    },
  });
}
