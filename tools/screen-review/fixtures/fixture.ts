// NEW: proof-only screen fixtures never seed the operator's proposal or feedback directories.
import { cpSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

export function fixture() {
  const root = mkdtempSync(join(tmpdir(), "screen-review-"));
  const screensRoot = join(root, "screens");
  cpSync(join(import.meta.dir, "screens"), screensRoot, { recursive: true });
  return { root, screensRoot, feedbackFile: join(root, "feedback.json") };
}
