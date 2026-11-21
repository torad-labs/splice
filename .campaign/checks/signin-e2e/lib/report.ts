// signin-e2e: the sign-in check's report (`bun tools/e2e signin --json FILE`), run and judged the same way by every
// check of the feature. The kinds come from the code's own registry, read here on their own, so a report that leaves a
// kind out cannot pass by leaving it out.

import { expect } from "bun:test";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

export const ROOT = spawnSync("git", ["rev-parse", "--show-toplevel"], { cwd: import.meta.dir, encoding: "utf8" }).stdout.trim();

/** The sentence the console shows when the provider's page is closed before a sign-in finishes. */
export const PAGE_CLOSED = "The sign-in page was closed before the sign-in finished.";

/** How each kind splice has today signs in. A kind added later may sign in either way. */
export const FLOWS: Record<string, "loopback" | "device" | "paste"> = {
  "chatgpt-oauth": "loopback",
  "grok-oauth": "loopback",
  "kimi-oauth": "device",
  "muse-oauth": "device",
  client: "paste",
};

const LOOPBACK = new Set(["127.0.0.1", "localhost", "[::1]", "::1"]);

/** The auth kinds splice signs in: AuthKindRegistry's KNOWN list in AuthKind.kt, each by its wire spelling. */
export function registryKinds(): string[] {
  const text = readFileSync(join(ROOT, "core/src/main/kotlin/splice/core/topology/AuthKind.kt"), "utf8");
  const known = /val KNOWN\s*:\s*List<AuthKind>\s*=\s*listOf\(([\s\S]*?)\)/.exec(text)?.[1] ?? "";
  const kinds = [...known.matchAll(/AuthKind\.(\w+)/g)].map(([, name]) => {
    const wire = new RegExp(`data object ${name}\\s*:\\s*\\w+\\(\\s*"([^"]+)"`).exec(text)?.[1];
    if (!wire) throw new Error(`AuthKind.kt names AuthKind.${name} in KNOWN and no wire kind for it was read`);
    return wire;
  });
  if (kinds.length < Object.keys(FLOWS).length) throw new Error(`read ${kinds.length} kinds from AuthKind.kt: its shape changed`);
  return kinds;
}

export interface Step {
  step: number;
  name: string;
  ok: boolean;
  said: string;
  seen: Record<string, unknown>;
}

export interface KindReport {
  kind: string;
  flow: string;
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

export interface Ran {
  code: number;
  out: string;
  report: Report | null;
}

/** `bun tools/e2e signin <args> --json <file>` from the repository's root, read to its end. */
export function runCheck(args: string[]): Ran {
  const dir = mkdtempSync(join(tmpdir(), "signin-e2e-check-"));
  const file = join(dir, "report.json");
  try {
    const r = spawnSync("bun", ["tools/e2e", "signin", ...args, "--json", file], { cwd: ROOT, encoding: "utf8", maxBuffer: 256 << 20 });
    const out = `${r.stdout ?? ""}${r.stderr ?? ""}`;
    const report = existsSync(file) ? (JSON.parse(readFileSync(file, "utf8")) as Report) : null;
    return { code: r.status ?? -1, out, report };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

const str = (v: unknown): string => (typeof v === "string" ? v : "");
const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" ? (v as Record<string, unknown>) : {});

/** Every step of one kind passed, and what the report says it saw is what passing means. */
export function judge(k: KindReport): void {
  const at = (n: number, what: string) => `${k.kind}, step ${n}: ${what}`;
  expect(k.steps.map((s) => s.step), `${k.kind}: the report has its four steps`).toEqual([1, 2, 3, 4]);
  for (const s of k.steps) expect(s.ok, at(s.step, `${s.name} failed: ${s.said}`)).toBe(true);
  expect(k.ok, `${k.kind}: the kind passed`).toBe(true);
  if (FLOWS[k.kind]) expect(k.flow, `${k.kind}: signs in by its own flow`).toBe(FLOWS[k.kind]!);
  const [one, two, three, four] = k.steps.map((s) => s.seen);

  // 1. The page opens: the tab the press opened is on the provider's own page, and splice login opens the same page.
  const tab = str(one!.console_tab);
  const page = str(one!.provider_page);
  expect(page, at(1, "names the provider's sign-in page")).not.toBe("");
  expect(tab.startsWith(page), at(1, `the tab is on ${page}, not ${tab}`)).toBe(true);
  const tabUrl = new URL(tab);
  expect(tabUrl.protocol, at(1, "the tab is on the provider's https page")).toBe("https:");
  expect(LOOPBACK.has(tabUrl.hostname), at(1, "the tab is on the provider's address, not this machine's")).toBe(false);
  const cli = new URL(str(one!.cli_page) || "about:blank");
  expect(`${cli.origin}${cli.pathname}`, at(1, "splice login opens the same page")).toBe(`${tabUrl.origin}${tabUrl.pathname}`);
  if (k.flow === "device") {
    expect(str(one!.card_code), at(1, "the card shows the code")).not.toBe("");
    expect(str(one!.page_code), at(1, "the page asks for the code the card shows")).toBe(str(one!.card_code));
  }

  // 2. The login completes: the card says so by the login's label, without a reload, and again after a removal.
  const label = str(two!.label);
  const again = str(two!.again_label);
  expect(label, at(2, "names the login's label")).not.toBe("");
  expect(str(two!.card), at(2, "the card says the login landed")).toContain(`Signed in as ${label}`);
  expect(two!.reloaded, at(2, "the page was not reloaded")).toBe(false);
  expect(again, at(2, "names the label of the login signed in after the removal")).not.toBe("");
  expect(str(two!.again_card), at(2, "the card says the login that replaced the removed one landed")).toContain(`Signed in as ${again}`);

  // 3. The gateway serves a turn on the login just made, with its credential and no other.
  expect(str(three!.reply), at(3, "the turn is answered")).not.toBe("");
  expect(str(three!.reply), at(3, "the answer is the provider's")).toBe(str(three!.expected_reply));
  expect(str(three!.minted), at(3, "names the credential the provider minted for this login")).not.toBe("");
  expect(str(three!.credential), at(3, "the turn carries this login's credential")).toBe(str(three!.minted));
  expect(str(three!.removed), at(3, "names the removed login's credential")).not.toBe("");
  expect(str(three!.credential), at(3, "the turn does not carry the removed login's credential")).not.toBe(str(three!.removed));
  expect(three!.others, at(3, "the turn carries no other credential")).toEqual([]);

  // 4. A failure says so on the card, and a tab that never got a page is closed.
  const refused = obj(four!.refused);
  expect(str(refused.provider_said), at(4, "the provider's refusal is named")).not.toBe("");
  expect(str(refused.card), at(4, "the card names the refusal in the provider's words")).toContain(str(refused.provider_said));
  expect(str(obj(four!.page_closed).card), at(4, "the card says the page was closed")).toContain(PAGE_CLOSED);
  expect(str(obj(four!.expired).card), at(4, "the card says the code expired")).toMatch(/expired/i);
  const unnamed = obj(four!.unnamed);
  expect(unnamed.tab_closed, at(4, "the tab of a sign-in that never named a page is closed")).toBe(true);
  expect(str(unnamed.card), at(4, "the card says why a sign-in with no page ended")).not.toBe("");
}

/** One kind's run, judged: it exits 0 and its report holds the kind, passed. */
export function judgeRun(kind: string): void {
  const r = runCheck(["--kind", kind]);
  expect(r.report, `no report was written:\n${r.out.slice(-6000)}`).not.toBeNull();
  const k = r.report!.kinds.find((x) => x.kind === kind);
  expect(k, `the report has no ${kind}:\n${r.out.slice(-6000)}`).toBeDefined();
  judge(k!);
  expect(r.code, r.out.slice(-6000)).toBe(0);
}
