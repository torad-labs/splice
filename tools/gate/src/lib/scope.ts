// The pre-push scope rules, read from tools/gate/config/scope.json. A changed path is full, docs, a law input, or
// none of those. The lists live in the file so a new input of every module's build is a config line, not a regex in
// this tree. A glob that is only `**` or `*` matches every path; it selects nothing, because a row that named one
// ran on every push.

import { readFileSync } from "node:fs";
import { join } from "node:path";

/** One path class: the glob, why a match matters, and paths the glob would otherwise swallow. */
export interface ScopeRule {
  readonly glob: string;
  readonly why: string;
  readonly except?: readonly string[];
}

/** The three classes a changed path can fall into. Anything else is a module's own file. */
export interface ScopeRules {
  readonly full: readonly ScopeRule[];
  readonly docs: readonly ScopeRule[];
  readonly laws: readonly ScopeRule[];
}

const SCOPE_FILE = join(import.meta.dir, "../../config/scope.json");

const compiled = new Map<string, Bun.Glob>();

/** Whether [pattern] matches [path]. A match-all pattern matches nothing: it would select every push. */
export function globMatches(pattern: string, path: string): boolean {
  if (isMatchAll(pattern)) return false;
  let glob = compiled.get(pattern);
  if (glob === undefined) {
    glob = new Bun.Glob(pattern);
    compiled.set(pattern, glob);
  }
  return glob.match(path);
}

/** Whether any of [patterns] matches [path]. Match-all patterns are ignored. */
export function matchesAny(patterns: readonly string[], path: string): boolean {
  return patterns.some((pattern) => globMatches(pattern, path));
}

/**
 * A glob whose only tokens are `*` and `**`. `**`, `**\/*` and `*` match every path, so a ladder row that listed one
 * was selected on every push and the scope never narrowed. They select nothing; the row names the paths it reads.
 */
export function isMatchAll(pattern: string): boolean {
  return pattern.length > 0 && pattern.replace(/\/?\*\*/g, "").replace(/\*/g, "").replace(/\//g, "") === "";
}

function asRules(value: unknown, name: string): ScopeRule[] {
  if (!Array.isArray(value)) throw new Error(`tools/gate/config/scope.json: ${name} is not a list`);
  return value.map((entry, index) => {
    const rule = entry as { glob?: unknown; why?: unknown; except?: unknown };
    if (typeof rule.glob !== "string" || rule.glob.length === 0) throw new Error(`tools/gate/config/scope.json: ${name}[${index}] has no glob`);
    if (typeof rule.why !== "string" || rule.why.length === 0) throw new Error(`tools/gate/config/scope.json: ${name}[${index}] has no why`);
    const except = rule.except === undefined ? undefined : asExcept(rule.except, `${name}[${index}]`);
    return except === undefined ? { glob: rule.glob, why: rule.why } : { glob: rule.glob, why: rule.why, except };
  });
}

function asExcept(value: unknown, where: string): string[] {
  if (!Array.isArray(value) || value.some((item) => typeof item !== "string")) {
    throw new Error(`tools/gate/config/scope.json: ${where}.except is not a list of paths`);
  }
  return value as string[];
}

/** The rules in [text], or the committed scope file when [text] is omitted. */
export function loadScope(text: string = readFileSync(SCOPE_FILE, "utf8")): ScopeRules {
  const parsed = JSON.parse(text) as { full?: unknown; docs?: unknown; laws?: unknown };
  return { full: asRules(parsed.full, "full"), docs: asRules(parsed.docs, "docs"), laws: asRules(parsed.laws, "laws") };
}

let cached: ScopeRules | undefined;

/** The committed scope file, read once. */
export function repoScope(): ScopeRules {
  return (cached ??= loadScope());
}

function ruleFor(rules: readonly ScopeRule[], path: string): ScopeRule | undefined {
  for (const rule of rules) {
    if (rule.except?.some((excluded) => excluded === path || globMatches(excluded, path))) continue;
    if (globMatches(rule.glob, path)) return rule;
  }
  return undefined;
}

/** The full-suite rule [path] matches, or undefined when a change to it does not affect every module. */
export function fullRule(path: string, scope: ScopeRules = repoScope()): ScopeRule | undefined {
  return ruleFor(scope.full, path);
}

/** The docs rule [path] matches, or undefined when the path is not prose no law reads. */
export function docsRule(path: string, scope: ScopeRules = repoScope()): ScopeRule | undefined {
  return ruleFor(scope.docs, path);
}

/** The law rule [path] matches, or undefined when no law suite reads it. */
export function lawRule(path: string, scope: ScopeRules = repoScope()): ScopeRule | undefined {
  return ruleFor(scope.laws, path);
}
