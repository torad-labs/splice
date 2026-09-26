// WHERE THE LAUNCH SHIM IS, AND WHAT VERSIONS IT CLAIMS — one answer, read by three callers.
//
// checks/release/launcher-test.sh:11-12 and checks/release/accept.sh:28-30 each spelled their own
// `awk -F'"' '/^SPLICE_GATEWAY_VERSION="/'`, which is two implementations of one rule; PR 6 moves the
// shim to the application's dist layout as a NODE script, so the marker is a JS const and the old
// awk would read nothing at all — silently, because `awk` prints an empty string for no match and
// both callers then compared "" to "". One reader, and it refuses on no match.
import { readFileSync } from "node:fs";
import { isAbsolute, join } from "node:path";

/** The shipped shim, relative to the repository root. `bin/` is gone as of PR 6. */
export const SHIM_RELATIVE = "app/src/main/dist/bin/splice-launch";

export interface ShimMarkers {
  /** the gateway version the shim demands of the daemon it talks to */
  readonly gateway: string;
  /** the shim's own generation marker, which the daemon reports back as wantShimVersion */
  readonly shim: string;
}

/** `SPLICE_SHIM` overrides the path, for a test that runs a fixture shim instead of the shipped one. */
export function shimPath(repoRoot: string, env: Record<string, string | undefined> = Bun.env): string {
  const override = env.SPLICE_SHIM;
  if (override) return isAbsolute(override) ? override : join(repoRoot, override);
  return join(repoRoot, SHIM_RELATIVE);
}

const MARKERS = {
  gateway: /^const SPLICE_GATEWAY_VERSION = "([^"]+)";$/m,
  shim: /^const SPLICE_SHIM_VERSION = "([^"]+)";$/m,
} as const;

/** Both markers, or an Error naming the file — never a blank that compares equal to another blank. */
export function shimMarkers(path: string): ShimMarkers | Error {
  let text: string;
  try {
    text = readFileSync(path, "utf8");
  } catch {
    return new Error(`launcher test: could not read version markers from ${path}`);
  }
  const gateway = MARKERS.gateway.exec(text)?.[1];
  const shim = MARKERS.shim.exec(text)?.[1];
  if (!gateway || !shim) return new Error(`launcher test: could not read version markers from ${path}`);
  return { gateway, shim };
}

/** The gateway marker alone, the way `release accept` reads it off the STAGED shim. */
export function stagedGatewayVersion(path: string): string | undefined {
  try {
    return MARKERS.gateway.exec(readFileSync(path, "utf8"))?.[1];
  } catch {
    return undefined;
  }
}
