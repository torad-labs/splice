/** Helpers for reading the PreToolUse data envelope. Tool data shapes (Claude Code hook contract):
 *
 *    Write     -> tool_input.file_path, tool_input.content
 *    Edit      -> tool_input.file_path, tool_input.old_string, tool_input.new_string
 *    MultiEdit -> tool_input.file_path, tool_input.edits[].old_string / new_string
 */

type HookEvent = Record<string, unknown>;

/** The tool_input envelope, or an empty object when it is absent or the wrong shape. Every
 *  accessor below goes through this, so a malformed payload yields empty strings rather than
 *  throwing — the hook contract has no room for a crash on an odd event. */
function toolInputOf(data: HookEvent): Record<string, unknown> {
  const toolInput = data.tool_input;
  return toolInput !== null && typeof toolInput === "object" && !Array.isArray(toolInput)
    ? (toolInput as Record<string, unknown>)
    : {};
}

export function filePathOf(data: HookEvent): string {
  return String(toolInputOf(data).file_path ?? "");
}

export function isWriteOrEdit(data: HookEvent): boolean {
  return ["Write", "Edit", "MultiEdit"].includes(String(data.tool_name ?? ""));
}

/** Guarantee a leading slash so `/build/`-style segment fragments match both
 *  absolute paths (/…/build/x) and relative ones (build/x). */
function seg(filePath: string): string {
  return filePath.startsWith("/") ? filePath : "/" + filePath;
}

// Generated / vendored — the whole hook chain skips these (build artifacts).
const EXEMPT_PATH_FRAGMENTS: readonly string[] = [
  "/dist/", "/build/", "/node_modules/", "/.gradle/",
];

export function isPathExempt(filePath: string): boolean {
  return EXEMPT_PATH_FRAGMENTS.some((frag) => seg(filePath).includes(frag));
}
