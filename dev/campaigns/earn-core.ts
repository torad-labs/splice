/**
 * Earned-row v2 core (concept #1081) — shared by matrix + ledger + review.
 *
 * Diary markers live as `#` notes inside a block (line-surgical; never a serializer).
 *
 *   # check:<slug> kind=executed cmd=<command>
 *   # check:<slug> kind=executed via=review
 *   # check:<slug> kind=attested
 *   # require:ready:<slug>[,slug…]
 *   # require:verified:<slug>[,slug…]
 *   # receipt:<slug> kind=executed at=<iso> exit=<n> artifact=<path>
 *   # receipt:<slug> kind=attested at=<iso> pointer=<text>
 *   # review:<n> verdict=clean|findings artifact=<path> at=<iso>
 *   # depends:<id>[,id…]
 *   # block-evidence symptom=… unblocks=… probe=…|tested=… result=…
 *
 * OPERATOR DELTA: reviews are orchestrator-spawned subagents, not headless CLI.
 * Cap: MAX_REVIEWS_PER_TARGET = 2. review-clean needs >= MIN_CLEAN_REVIEWS clean verdicts.
 *
 * GRAILSEEKER DELTA (operator ruling 2026-08-24): exactly ONE review per ledger item.
 * MIN_CLEAN_REVIEWS = 1; a findings verdict buys ONE fix-forward + ONE re-review (cap 2).
 *
 * GRAILSEEKER DELTA 2 (operator ruling clarified 2026-08-24): per-item reviews are a DRIFT
 * GUARD, not a convergence loop — "if you run 10 reviews, the 10 reviewers will come back
 * with findings." Items use require:verified:review-done (>=1 adversarial pass recorded,
 * verdict-agnostic; findings buy ONE orchestrator-verified fix-forward, NO re-review).
 * Convergence belongs to the terminal CAMPAIGN MASTER REVIEW item (unbounded rounds).
 */

// `: number` ON PURPOSE, and the two pins in review.ts's selftest are why. Read as bare literals the
// inferred types are `2` and `1`, which makes `MAX_REVIEWS_PER_TARGET === 2` a comparison the type
// system already answers — the rule was right — while the assertion's whole job is to catch an EDIT
// of the knob at runtime. A policy number is a `number`; declaring it so states the intent and lets
// the selftest keep pinning the value (MOD.38).
export const MAX_REVIEWS_PER_TARGET: number = 2;
export const MIN_CLEAN_REVIEWS: number = 1;

export type CheckKind = "executed" | "attested";

export type DeclaredCheck = {
  readonly slug: string;
  readonly kind: CheckKind;
  /** Shell command for kind=executed (not via=review). */
  readonly cmd?: string;
  /** Special runner: orchestrator subagent review protocol. */
  readonly via?: "review";
};

export type Receipt = {
  readonly slug: string;
  readonly kind: "executed" | "attested";
  readonly at: string;
  readonly exit?: number;
  readonly artifact?: string;
  readonly pointer?: string;
};

export type ReviewRecord = {
  readonly n: number;
  readonly verdict: "clean" | "findings";
  readonly artifact: string;
  readonly at: string;
};

export type BlockEvidence = {
  readonly symptom: string;
  readonly unblocksWhen: string;
  readonly probe?: string;
  readonly tested?: string;
  readonly result?: string;
};

const CHECK_RE =
  /^#\s*check:([A-Za-z0-9_.:-]+)\s+kind=(executed|attested)(?:\s+via=review)?(?:\s+cmd=(.+))?$/;
const REQUIRE_RE = /^#\s*require:(ready|verified):(.+)$/;
const RECEIPT_EXEC_RE =
  /^#\s*receipt:([A-Za-z0-9_.:-]+)\s+kind=executed\s+at=(\S+)\s+exit=(-?\d+)\s+artifact=(.+)$/;
const RECEIPT_ATT_RE =
  /^#\s*receipt:([A-Za-z0-9_.:-]+)\s+kind=attested\s+at=(\S+)\s+pointer=(.+)$/;
const REVIEW_RE =
  /^#\s*review:(\d+)\s+verdict=(clean|findings)\s+artifact=(\S+)\s+at=(\S+)\s*$/;
const DEPENDS_RE = /^#\s*depends:(.+)$/;
const BLOCK_EV_RE =
  /^#\s*block-evidence\s+symptom=(.+?)\s+unblocks=(.+?)(?:\s+probe=(.+?))?(?:\s+tested=(.+?))?(?:\s+result=(.+))?$/;

export function parseChecks(notes: readonly string[]): DeclaredCheck[] {
  const out: DeclaredCheck[] = [];
  for (const raw of notes) {
    const line = raw.trim();
    const m = CHECK_RE.exec(line);
    if (!m) continue;
    const via = /\bvia=review\b/.test(line) ? ("review" as const) : undefined;
    out.push({
      slug: m[1]!,
      kind: m[2] as CheckKind,
      ...(via ? { via } : {}),
      ...(m[3] !== undefined && m[3] !== "" ? { cmd: m[3] } : {}),
    });
  }
  return out;
}

export function parseRequires(
  notes: readonly string[],
): { ready: string[]; verified: string[] } {
  const ready: string[] = [];
  const verified: string[] = [];
  for (const raw of notes) {
    const m = REQUIRE_RE.exec(raw.trim());
    if (!m) continue;
    const slugs = m[2]!.split(",").map((s) => s.trim()).filter(Boolean);
    if (m[1] === "ready") ready.push(...slugs);
    else verified.push(...slugs);
  }
  return { ready: [...new Set(ready)], verified: [...new Set(verified)] };
}

export function parseReceipts(notes: readonly string[]): Receipt[] {
  const out: Receipt[] = [];
  for (const raw of notes) {
    const line = raw.trim();
    let m = RECEIPT_EXEC_RE.exec(line);
    if (m) {
      out.push({
        slug: m[1]!,
        kind: "executed",
        at: m[2]!,
        exit: Number(m[3]),
        artifact: m[4]!.trim(),
      });
      continue;
    }
    m = RECEIPT_ATT_RE.exec(line);
    if (m) {
      out.push({
        slug: m[1]!,
        kind: "attested",
        at: m[2]!,
        pointer: m[3]!.trim(),
      });
    }
  }
  return out;
}

export function parseReviews(notes: readonly string[]): ReviewRecord[] {
  const out: ReviewRecord[] = [];
  for (const raw of notes) {
    const m = REVIEW_RE.exec(raw.trim());
    if (!m) continue;
    out.push({
      n: Number(m[1]),
      verdict: m[2] as "clean" | "findings",
      artifact: m[3]!,
      at: m[4]!,
    });
  }
  return out.sort((a, b) => a.n - b.n);
}

export function parseDepends(notes: readonly string[]): string[] {
  const out: string[] = [];
  for (const raw of notes) {
    const m = DEPENDS_RE.exec(raw.trim());
    if (!m) continue;
    out.push(...m[1]!.split(",").map((s) => s.trim()).filter(Boolean));
  }
  return [...new Set(out)];
}

export function parseBlockEvidence(notes: readonly string[]): BlockEvidence | null {
  for (const raw of notes) {
    const m = BLOCK_EV_RE.exec(raw.trim());
    if (!m) continue;
    return {
      symptom: m[1]!.trim(),
      unblocksWhen: m[2]!.trim(),
      ...(m[3] ? { probe: m[3].trim() } : {}),
      ...(m[4] ? { tested: m[4].trim() } : {}),
      ...(m[5] ? { result: m[5].trim() } : {}),
    };
  }
  return null;
}

export function latestReceipt(receipts: readonly Receipt[], slug: string): Receipt | null {
  const matches = receipts.filter((r) => r.slug === slug);
  return matches.length === 0 ? null : matches[matches.length - 1]!;
}

export function receiptSatisfies(receipt: Receipt | null, check: DeclaredCheck | undefined): boolean {
  if (receipt === null) return false;
  if (check?.kind === "executed" || check?.via === "review") {
    return receipt.kind === "executed" && receipt.exit === 0;
  }
  // attested slug: either attested pointer or successful executed run counts
  return (
    (receipt.kind === "attested" && (receipt.pointer?.length ?? 0) >= 12) ||
    (receipt.kind === "executed" && receipt.exit === 0)
  );
}

export function reviewCleanSatisfied(reviews: readonly ReviewRecord[]): {
  ok: boolean;
  detail: string;
} {
  if (reviews.length > MAX_REVIEWS_PER_TARGET) {
    return {
      ok: false,
      detail: `review cap exceeded (${reviews.length} > ${MAX_REVIEWS_PER_TARGET})`,
    };
  }
  const clean = reviews.filter((r) => r.verdict === "clean").length;
  const latest = reviews[reviews.length - 1];
  // GRAILSEEKER FIX (2026-08-24, upstream-promotion candidate): upstream blocked on ANY
  // findings record in history, which made its own remedy ("fix forward, then record clean
  // sessions") unreachable — a findings verdict tainted the item forever. Findings block
  // only while they are the LATEST review; a later clean satisfies.
  if (latest?.verdict === "findings") {
    return {
      ok: false,
      detail: `latest review reports findings — fix forward, then record a clean session`,
    };
  }
  if (clean < MIN_CLEAN_REVIEWS) {
    return {
      ok: false,
      detail: `review-clean needs >=${MIN_CLEAN_REVIEWS} clean subagent verdicts (have ${clean}; max ${MAX_REVIEWS_PER_TARGET} sessions)`,
    };
  }
  return { ok: true, detail: `${clean} clean subagent review(s)` };
}

/**
 * review-done (grailseeker operator ruling 2026-08-24, clarified): per-item review is a
 * drift guard — exactly one recorded adversarial pass, verdict-agnostic. Findings are
 * addressed by ONE orchestrator-verified fix-forward (no re-review); convergence is the
 * terminal master review, not the per-item loop.
 */
export function reviewDoneSatisfied(reviews: readonly ReviewRecord[]): {
  ok: boolean;
  detail: string;
} {
  if (reviews.length < 1) {
    return {
      ok: false,
      detail: `review-done needs exactly one recorded adversarial review (have ${reviews.length})`,
    };
  }
  return { ok: true, detail: `${reviews.length} review(s) recorded (drift guard satisfied)` };
}

/** Slugs still missing a satisfying receipt for a status gate. */
export function missingSlugs(
  required: readonly string[],
  checks: readonly DeclaredCheck[],
  receipts: readonly Receipt[],
  reviews: readonly ReviewRecord[],
): { slug: string; remedy: string }[] {
  const bySlug = new Map(checks.map((c) => [c.slug, c]));
  const missing: { slug: string; remedy: string }[] = [];
  for (const slug of required) {
    if (slug === "review-done") {
      const rd = reviewDoneSatisfied(reviews);
      if (!rd.ok) {
        missing.push({
          slug,
          remedy:
            `orchestrator: bun dev/campaigns/review.ts prepare <ID> --diff <range>\n` +
            `  then Agent(subagent) with the printed brief; then\n` +
            `  bun dev/campaigns/review.ts record <ID> --verdict clean|findings --artifact <path>\n` +
            `  (${rd.detail})`,
        });
      }
      continue;
    }
    if (slug === "review-clean" || bySlug.get(slug)?.via === "review") {
      const rc = reviewCleanSatisfied(reviews);
      if (!rc.ok) {
        missing.push({
          slug,
          remedy:
            `orchestrator: bun dev/campaigns/review.ts prepare <ID> --diff <range>\n` +
            `  then Agent(subagent) with the printed brief; then\n` +
            `  bun dev/campaigns/review.ts record <ID> --verdict clean|findings --artifact <path>\n` +
            `  (${rc.detail})`,
        });
      }
      continue;
    }
    const check = bySlug.get(slug);
    const rec = latestReceipt(receipts, slug);
    if (!receiptSatisfies(rec, check)) {
      if (check?.kind === "executed" && check.cmd) {
        missing.push({
          slug,
          remedy: `no matrix plane in this repo — executed-check mandates are not wired here; use review-clean or attested`,
        });
      } else if (check?.kind === "attested") {
        missing.push({
          slug,
          remedy: `no matrix plane in this repo — record attestation as a dated ledger note citing the pointer`,
        });
      } else {
        missing.push({
          slug,
          remedy: `declare a check first: check <ID> ${slug} --cmd '…'  OR  set-proof if attested`,
        });
      }
    }
  }
  return missing;
}

export function formatCheckNote(check: DeclaredCheck): string {
  if (check.via === "review") {
    return `# check:${check.slug} kind=executed via=review`;
  }
  if (check.kind === "executed") {
    return `# check:${check.slug} kind=executed cmd=${check.cmd ?? ""}`;
  }
  return `# check:${check.slug} kind=attested`;
}

export function formatRequireNote(status: "ready" | "verified", slugs: readonly string[]): string {
  return `# require:${status}:${slugs.join(",")}`;
}

export function formatExecutedReceipt(
  slug: string,
  at: string,
  exit: number,
  artifact: string,
): string {
  return `# receipt:${slug} kind=executed at=${at} exit=${exit} artifact=${artifact}`;
}

export function formatAttestedReceipt(slug: string, at: string, pointer: string): string {
  return `# receipt:${slug} kind=attested at=${at} pointer=${pointer}`;
}

export function formatReviewNote(r: ReviewRecord): string {
  return `# review:${r.n} verdict=${r.verdict} artifact=${r.artifact} at=${r.at}`;
}

export function formatDependsNote(ids: readonly string[]): string {
  return `# depends:${ids.join(",")}`;
}

export function formatBlockEvidence(ev: BlockEvidence): string {
  let s = `# block-evidence symptom=${ev.symptom} unblocks=${ev.unblocksWhen}`;
  if (ev.probe) s += ` probe=${ev.probe}`;
  if (ev.tested) s += ` tested=${ev.tested}`;
  if (ev.result) s += ` result=${ev.result}`;
  return s;
}

export function isoNow(): string {
  return new Date().toISOString();
}

export function teachRemedy(
  target: string,
  status: "ready" | "verified",
  missing: { slug: string; remedy: string }[],
  extra: string[] = [],
): string {
  if (missing.length === 0 && extra.length === 0) {
    return `${target}: all mandates for ${status} are satisfied`;
  }
  const lines = [
    `${target} cannot become ${status} — still outstanding:`,
    ...missing.map((m) => `  · ${m.slug}\n      ${m.remedy}`),
    ...extra.map((e) => `  · ${e}`),
  ];
  return lines.join("\n");
}

/** Artifact directory (durable, in-repo). */
export function artifactDir(repoRoot: string, kind: "earn" | "reviews"): string {
  return `${repoRoot}/dev/earn-artifacts/${kind}`;
}
