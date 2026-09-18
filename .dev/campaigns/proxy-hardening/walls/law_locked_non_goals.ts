#!/usr/bin/env bun
/** LAW ENFORCER — LOCKED-NON-GOALS.
 *
 *  THE LAW  splice is single-user, loopback-only, no TLS, no remote access, no multi-user/RBAC; the
 *  JVM daemon stays; server/ is legacy-and-dying; reasoning replay stays default-off. §11 of the audit
 *  carries a 22-row ledger of ideas the sweep FOUND in the landscape and deliberately did not propose.
 *
 *  WHY A WALL  These are settled operator decisions. The failure mode is not someone arguing for them
 *  openly — it is a locked non-goal drifting back in as an innocuous-looking item during a later wave,
 *  because 83 items are too many for anyone to re-read. #924: you don't review your way out of drift.
 *
 *  POLARITY NOTE — this is a LAW enforcer, not an item wall. It must be GREEN today (no item currently
 *  proposes a locked non-goal) and going red means a law was BROKEN. That is the opposite of an item
 *  wall, which must be red until its gap is closed.
 *
 *  EXIT 0 = no item proposes a locked non-goal.  EXIT 1 = one does.
 *  --selftest = positive control: proves the scan separates a violating item from a clean one, and
 *               does not fire on the laws/§11 text that legitimately NAMES the non-goals.
 *
 *  V4-154: converted to TypeScript (bun). PROOF FOR A GREEN-ON-TODAY WALL: the live tree is green, so
 *  a differential there compares two empty problem lists and proves nothing. Both implementations
 *  were therefore driven over the original's own eight selftest cases plus a wider title corpus, and
 *  the FULL problem lists compared; the mutation arm widens the BANNED table and confirms both
 *  implementations follow it rather than a transcribed copy.
 */
import { resolve } from "node:path";

/** Python repr() of a string, and of a list of strings. An f-string that interpolates a LIST
 *  renders THIS -- `['a', 'b']` -- not JSON, so a port that used JSON.stringify produced a
 *  DIFFERENT failure message than the original on every red path while agreeing on every green
 *  one. Caught by driving the mutants as a CLI rather than feeding detect() a fixed corpus. */
function pyReprStr(s: string): string {
  const useDouble = s.includes("'") && !s.includes('"');
  const q = useDouble ? '"' : "'";
  let body = s
    .replaceAll("\\", "\\\\")
    .replaceAll("\n", "\\n")
    .replaceAll("\r", "\\r")
    .replaceAll("\t", "\\t");
  body = body.replaceAll(q, "\\" + q);
  return q + body + q;
}
function pyRepr(items: string[]): string {
  return "[" + items.map(pyReprStr).join(", ") + "]";
}

const ROOT = resolve(import.meta.dir, "../../../..");
const BOARD = resolve(ROOT, ".dev/campaigns/proxy-hardening.toml");

/** Each rule: [label, pattern that indicates PROPOSING it, pattern that exonerates as a mention]. */
export const BANNED: [string, RegExp][] = [
  ["multi-user/RBAC", /\b(add|introduce|implement|support)\b[^.]{0,60}\b(multi-?user|RBAC|tenant)\b/i],
  ["TLS/remote", /\b(add|introduce|implement|expose|enable)\b[^.]{0,60}\b(TLS|HTTPS listener|remote access|bind 0\.0\.0\.0)\b/i],
  ["non-JVM rewrite", /\b(rewrite|port|migrate)\b[^.]{0,60}\b(in|to)\s+(Go|Rust|Bun|GraalVM|KMP)\b/i],
  // NOTE: no trailing \b after `server/` — `/` is a non-word char, so `\bserver/\b` never matches
  // (caught by this wall's own positive control, 2026-07-26).
  ["legacy server/ work", /\b(fix|extend|refactor|improve)\b[^.]{0,40}\bserver\//i],
  ["replay on by default", /\breplay\w*\b[^.]{0,40}\b(on by default|default-on|enable by default)\b/i],
];
/** A title may legitimately QUOTE a non-goal when recording that it was rejected. */
export const EXONERATE = /locked non-goal|deliberately not proposed|wontfix|RECONSIDER:|§11/i;

type Item = Record<string, unknown>;

/** items: [{id, title}] — pure, so the selftest feeds it directly. */
export function detect(items: Item[]): string[] {
  const out: string[] = [];
  for (const it of items) {
    const title = String(it.title ?? "");
    if (EXONERATE.test(title)) continue;
    for (const [label, pat] of BANNED) {
      if (pat.test(title)) {
        out.push(
          `${it.id}: proposes a LOCKED NON-GOAL (${label}). ` +
            "Settled operator decision — do not re-open it as an item.",
        );
        break;
      }
    }
  }
  return out;
}

function selftest(): number {
  const fails: string[] = [];

  const kase = (name: string, items: Item[], wantRed: boolean): void => {
    const got = detect(items);
    if (wantRed && got.length === 0) fails.push(`${name}: must be RED`);
    if (!wantRed && got.length > 0) fails.push(`${name}: must be GREEN, got ${pyRepr(got)}`);
  };

  kase("clean item", [{ id: "NF-01", title: "clamp the 429 cooldown horizon" }], false);
  kase("proposes multi-user", [{ id: "X-01", title: "add multi-user support to the control plane" }], true);
  kase("proposes TLS", [{ id: "X-02", title: "enable TLS on the head listener" }], true);
  kase("proposes rust rewrite", [{ id: "X-03", title: "rewrite the daemon in Rust for speed" }], true);
  kase("proposes legacy work", [{ id: "X-04", title: "refactor server/ stream handling" }], true);
  kase("mentions but rejects", [{
    id: "X-05",
    title: "multi-user was found in the sweep and is a locked non-goal — not proposed",
  }], false);
  kase("RECONSIDER prefix is allowed", [{
    id: "X-06",
    title: "RECONSIDER: cross-head failover — implement multi-user style dispatch",
  }], false);
  kase("empty ledger", [], false);

  if (fails.length > 0) {
    process.stdout.write("LOCKED-NON-GOALS SELFTEST FAIL:\n");
    for (const f of fails) process.stdout.write("  " + f + "\n");
    return 1;
  }
  process.stdout.write(
    "LOCKED-NON-GOALS SELFTEST OK — fires on proposals, stays quiet on rejections/RECONSIDER mentions\n",
  );
  return 0;
}

async function main(): Promise<number> {
  if (process.argv.includes("--selftest")) return selftest();
  const items = (Bun.TOML.parse(await Bun.file(BOARD).text()) as { items?: Item[] }).items ?? [];
  const problems = detect(items);
  process.stdout.write(`LOCKED-NON-GOALS: scanned ${items.length} items\n`);
  if (problems.length > 0) {
    process.stdout.write("LAW VIOLATED — an item proposes a locked non-goal:\n");
    for (const p of problems) process.stdout.write(`  · ${p}\n`);
    return 1;
  }
  process.stdout.write("LAW HONORED: no item proposes a locked non-goal.\n");
  return 0;
}

if (import.meta.main) {
  process.exit(await main());
}
