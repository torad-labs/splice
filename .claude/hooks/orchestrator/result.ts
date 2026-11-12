/** Hook module result type — drives the runner state machine.
 *
 *  Stolen verbatim from kandi-main / qgre-agent
 *  .claude/hooks/orchestrator/result.ts.
 *
 *  Python used a frozen dataclass; a frozen interface plus a constructor function is the same
 *  contract with the same immutability guarantee at the type level. The runner TYPE-CHECKS the
 *  return (`isinstance(result, HookResult)` there), so `isHookResult` is that check's port and is
 *  load-bearing rather than decorative: a module returning a bare object must be ignored, exactly
 *  as Python ignored it.
 */
export type ResultKind = "block" | "inject" | "warn" | "pass";

export interface HookResult {
  kind: ResultKind;
  payload: string;
  moduleName: string;
}

const KINDS: ReadonlySet<string> = new Set(["block", "inject", "warn", "pass"]);

export function hookResult(kind: ResultKind, payload: string, moduleName: string): HookResult {
  return { kind, payload, moduleName };
}

/** The port of `isinstance(result, HookResult)`. Structural, because TypeScript has no runtime
 *  nominal check and an interface compiles away — so this verifies the shape the runner depends on
 *  and rejects anything else, which is what the Python check did. */
export function isHookResult(value: unknown): value is HookResult {
  if (value === null || typeof value !== "object") return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.kind === "string" &&
    KINDS.has(candidate.kind) &&
    typeof candidate.payload === "string" &&
    typeof candidate.moduleName === "string"
  );
}
