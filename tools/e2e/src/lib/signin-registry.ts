// Shared types and AuthKind registry reader for `bun tools/e2e signin` (signin-e2e).
// Reads the wire kinds directly from AuthKind.kt so adding a new registered AuthKind without a
// matching sign-in check case fails the check by name instead of passing silently.
import { readFileSync } from "node:fs";
import { join } from "node:path";

export const PAGE_CLOSED = "The sign-in page was closed before the sign-in finished.";

export type SignInFlow = "loopback" | "device" | "paste";

export interface Step {
  step: number;
  name: string;
  ok: boolean;
  said: string;
  seen: Record<string, unknown>;
}

export interface KindReport {
  kind: string;
  flow: SignInFlow | "unknown";
  head: string;
  ok: boolean;
  steps: Step[];
}

export interface Report {
  ok: boolean;
  jar: string;
  missing: string[];
  kinds: KindReport[];
}

export function readRegistryKinds(repoRoot: string): string[] {
  const path = join(repoRoot, "core/src/main/kotlin/splice/core/topology/AuthKind.kt");
  const text = readFileSync(path, "utf8");
  const known = /val KNOWN\s*:\s*List<AuthKind>\s*=\s*listOf\(([\s\S]*?)\)/.exec(text)?.[1] ?? "";
  const kinds = [...known.matchAll(/AuthKind\.(\w+)/g)].map(([, name]) => {
    const wire = new RegExp(`data object ${name}\\s*:\\s*\\w+\\(\\s*"([^"]+)"`).exec(text)?.[1];
    if (!wire) throw new Error(`AuthKind.kt lists AuthKind.${name} in KNOWN without a wire spelling`);
    return wire;
  });
  if (kinds.length === 0) throw new Error("AuthKind.kt KNOWN list could not be parsed");
  return kinds;
}

export function formatFailure(kind: string, step: Step): string {
  return `signin: FAIL ${kind}, step ${step.step} (${step.name}): ${step.said}`;
}
