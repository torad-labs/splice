/** Helpers for reading the PreToolUse / Bash data envelope.
 *
 *  Adapted from kandi/qgre lib/tool_input.ts for torad-fleet: the brain is
 *  TypeScript under src/ + the Kotlin :domain source sets (both compile into the
 *  scanned dist/); host/ is the unscanned process-exec seam. No Android/iOS source
 *  sets. Tool data shapes (Claude Code hook contract):
 *
 *    Write     -> tool_input.file_path, tool_input.content
 *    Edit      -> tool_input.file_path, tool_input.old_string, tool_input.new_string
 *    MultiEdit -> tool_input.file_path, tool_input.edits[].old_string / new_string
 *    Bash      -> tool_input.command
 */

export type HookEvent = Record<string, unknown>;

/** The tool_input envelope, or an empty object when it is absent or the wrong shape. Every
 *  accessor below goes through this, so a malformed payload yields empty strings rather than
 *  throwing — the hook contract has no room for a crash on an odd event. */
function toolInputOf(data: HookEvent): Record<string, unknown> {
  const toolInput = data.tool_input;
  return toolInput !== null && typeof toolInput === "object" && !Array.isArray(toolInput)
    ? (toolInput as Record<string, unknown>)
    : {};
}

// D16: a shell command's quoted spans are DATA (prose, a note's text, a commit -m, a script
// literal), not command structure. Guards that match tool/marker tokens must run on the skeleton
// (quoted spans blanked) so prose naming a line-editor or a write verb never triggers them — the
// D13 family, shared by module 11 (astgrep-first) and module 07 (enforcement-layer Bash scan).
const QUOTED_SPAN = /'[^']*'|"[^"]*"/g;

/** `command` with single/double-quoted spans replaced by spaces. Imperfect on nested/escaped
 *  quotes (rare in guarded shapes); the same-checker commit re-run backstops the exotic. */
export function unquotedSkeleton(command: string): string {
  return command.replace(QUOTED_SPAN, " ");
}

export function filePathOf(data: HookEvent): string {
  return String(toolInputOf(data).file_path ?? "");
}

export function commandOf(data: HookEvent): string {
  return String(toolInputOf(data).command ?? "");
}

/** Each post-edit content fragment proposed by the tool call. */
export function proposedContents(data: HookEvent): string[] {
  const toolName = String(data.tool_name ?? "");
  const toolInput = toolInputOf(data);

  if (toolName === "Write") {
    const content = toolInput.content;
    return typeof content === "string" ? [content] : [];
  }
  if (toolName === "Edit") {
    const newS = toolInput.new_string;
    return typeof newS === "string" ? [newS] : [];
  }
  if (toolName === "MultiEdit") {
    const edits = toolInput.edits;
    if (!Array.isArray(edits)) return [];
    const out: string[] = [];
    for (const edit of edits) {
      if (edit !== null && typeof edit === "object") {
        const newS = (edit as Record<string, unknown>).new_string;
        if (typeof newS === "string") out.push(newS);
      }
    }
    return out;
  }
  return [];
}

export function isWriteOrEdit(data: HookEvent): boolean {
  return ["Write", "Edit", "MultiEdit"].includes(String(data.tool_name ?? ""));
}

export function isBash(data: HookEvent): boolean {
  return data.tool_name === "Bash";
}

export function hasKtExtension(filePath: string): boolean {
  return filePath.endsWith(".kt");
}

export function hasTsExtension(filePath: string): boolean {
  return [".ts", ".mts", ".cts", ".tsx"].some((ext) => filePath.endsWith(ext));
}

// Source that compiles / bundles into the scanned dist/ (TS brain + Kotlin).
const COMPILED_SOURCE_EXT: readonly string[] = [
  ".ts", ".mts", ".cts", ".tsx", ".js", ".mjs", ".cjs", ".jsx", ".kt",
];

export function hasCompiledSourceExt(filePath: string): boolean {
  return COMPILED_SOURCE_EXT.some((ext) => filePath.endsWith(ext));
}

/** Guarantee a leading slash so `/host/`-style segment fragments match both
 *  absolute paths (/…/host/x) and relative ones (host/x). */
function seg(filePath: string): string {
  return filePath.startsWith("/") ? filePath : "/" + filePath;
}

function projectRelativeParts(filePath: string): string[] {
  const parts = filePath.replace(/\\/g, "/").split("/").filter((part) => part !== "");
  for (let index = 0; index < parts.length - 1; index += 1) {
    if (parts[index] === "plugins" && parts[index + 1] === "fleet") {
      return parts.slice(index + 2);
    }
  }
  return parts;
}

// Generated / vendored — the whole hook chain skips these (mirrors kandi's
// is_path_exempt, repurposed: build artifacts, not an AI-SDK port).
const EXEMPT_PATH_FRAGMENTS: readonly string[] = [
  "/dist/", "/build/", "/node_modules/", "/.gradle/",
];

export function isPathExempt(filePath: string): boolean {
  return EXEMPT_PATH_FRAGMENTS.some((frag) => seg(filePath).includes(frag));
}

// Test source sets — the scan-clean seam rules target production source that
// ships in dist/, not test fakes/fixtures.
const TEST_PATH_FRAGMENTS: readonly string[] = [
  "/test/", "/commonTest/", "/jvmTest/", "/jsTest/",
];

export function isTestPath(filePath: string): boolean {
  return TEST_PATH_FRAGMENTS.some((frag) => seg(filePath).includes(frag));
}

// The host/ helper is a standalone, NEVER-scanned Node process — the one place
// child_process is legal. It is the seam the scan-clean rules exempt.
const HOST_SEAM_FRAGMENTS: readonly string[] = ["/host/"];

export function isHostSeam(filePath: string): boolean {
  return HOST_SEAM_FRAGMENTS.some((frag) => seg(filePath).includes(frag));
}

/** True iff this Write/Edit lands in source that compiles into the scanned
 *  dist/ — i.e. production TS/JS under the plugin src/ seam or production
 *  Kotlin source sets, not host/, scripts, e2e, tests, or generated files. */
export function targetsScannedDist(data: HookEvent): boolean {
  const filePath = filePathOf(data);
  if (!(isWriteOrEdit(data) && hasCompiledSourceExt(filePath))) return false;
  if (isPathExempt(filePath) || isTestPath(filePath) || isHostSeam(filePath)) return false;

  const relParts = projectRelativeParts(filePath);
  if (hasKtExtension(filePath)) {
    return relParts.includes("src") && (relParts.includes("commonMain") || relParts.includes("jsMain"));
  }
  return relParts.length > 0 && relParts[0] === "src";
}
