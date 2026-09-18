#!/usr/bin/env bun
/**
 * REVIEW-CLEAN protocol (HARNESS-3) — orchestrator-spawned SUBAGENTS only.
 *
 * NOT a headless Claude CLI runner. The orchestrator seat:
 *   1. `prepare <ID> --diff <range>`  → brief file + spawn instructions
 *   2. Agent tool with that brief (fresh subagent; diff-only context in the brief)
 *   3. `record <ID> --verdict clean|findings --artifact <path>`
 *   4. repeat until MIN_CLEAN_REVIEWS clean or MAX_REVIEWS_PER_TARGET hit
 *   5. matrix/ledger `earn` for slug review-clean reads these records
 *
 * Cap: 2 review sessions per target (item or matrix row id) — grailseeker operator ruling
 * 2026-08-24: ONE clean review per item; a findings verdict buys one fix-forward + one re-review.
 */
import { mkdirSync, writeFileSync, readFileSync, existsSync } from "node:fs";
import {
  MAX_REVIEWS_PER_TARGET,
  MIN_CLEAN_REVIEWS,
  formatReviewNote,
  isoNow,
  parseReviews,
  reviewCleanSatisfied,
  reviewDoneSatisfied,
  artifactDir,
} from "./earn-core.ts";
import { mutate, readLines, LedgerError } from "./ledger-core.ts";

const USAGE = `usage: bun .dev/campaigns/review.ts <command> [args]

  prepare <ID> --diff <git-range> [--ledger path] [--matrix path]
      Write a subagent brief (diff-only). Prints SPAWN instructions for the orchestrator.
      Does NOT call Claude. You spawn Agent() with the brief.

  record <ID> --verdict clean|findings --artifact <path> [--ledger path] [--matrix path]
      Append a review:N diary note on the ledger item and/or matrix row.
      Refuses when ${MAX_REVIEWS_PER_TARGET} sessions already recorded.

  status <ID> [--ledger path] [--matrix path]
      How many reviews, whether review-clean is satisfied.

  selftest
      Cap + clean-threshold checks.

OPERATOR RULES
  · Max ${MAX_REVIEWS_PER_TARGET} subagent review sessions per ID
  · review-clean needs >= ${MIN_CLEAN_REVIEWS} clean verdicts and zero findings
  · Subagents get the brief file only — no "implementer rationale" channel
`;

function flag(argv: readonly string[], name: string): string | null {
  const i = argv.indexOf(`--${name}`);
  return i === -1 ? null : (argv[i + 1] ?? null);
}

function repoRoot(): string {
  return Bun.spawnSync(["git", "rev-parse", "--show-toplevel"], {
    stdout: "pipe",
  }).stdout.toString().trim();
}

function defaultLedger(root: string): string {
  return `${root}/.dev/campaigns/v0.4.0.toml`;
}

function defaultMatrix(root: string): string {
  return `${root}/dev/matrix.toml`; // no matrix plane vendored here — existsSync guards make it absent
}

/** Notes from a TOML block whose id= field matches. */
function notesForId(lines: readonly string[], id: string, header: RegExp): string[] {
  let start = -1;
  let end = -1;
  for (let i = 0; i < lines.length; i++) {
    if (!header.test(lines[i] ?? "")) continue;
    let e = i + 1;
    while (e < lines.length && !/^\s*\[/.test(lines[e] ?? "")) e++;
    let last = e;
    while (last > i + 1 && (lines[last - 1] ?? "").trim() === "") last--;
    const body = lines.slice(i, last);
    const idLine = body.find((l) => /^\s*id\s*=/.test(l));
    const m = idLine?.match(/id\s*=\s*"([^"]+)"/);
    if (m?.[1] === id) {
      start = i;
      end = last;
      break;
    }
  }
  if (start < 0) return [];
  return lines
    .slice(start, end)
    .filter((l) => l.trimStart().startsWith("#"))
    .map((l) => l.trim());
}

function appendNoteToId(
  lines: readonly string[],
  id: string,
  header: RegExp,
  note: string,
): string[] {
  const next = [...lines];
  for (let i = 0; i < next.length; i++) {
    if (!header.test(next[i] ?? "")) continue;
    let e = i + 1;
    while (e < next.length && !/^\s*\[/.test(next[e] ?? "")) e++;
    let last = e;
    while (last > i + 1 && (next[last - 1] ?? "").trim() === "") last--;
    const body = next.slice(i, last);
    const idLine = body.find((l) => /^\s*id\s*=/.test(l));
    const m = idLine?.match(/id\s*=\s*"([^"]+)"/);
    if (m?.[1] !== id) continue;
    next.splice(last, 0, note);
    return next;
  }
  throw new LedgerError(`no block with id "${id}"`);
}

async function collectReviews(
  id: string,
  ledgerPath: string | null,
  matrixPath: string | null,
): Promise<ReturnType<typeof parseReviews>> {
  const notes: string[] = [];
  if (ledgerPath && existsSync(ledgerPath)) {
    notes.push(...notesForId(await readLines(ledgerPath), id, /^\[\[items\]\]\s*$/));
  }
  if (matrixPath && existsSync(matrixPath)) {
    notes.push(...notesForId(await readLines(matrixPath), id, /^\[\[rows\]\]\s*$/));
  }
  return parseReviews(notes);
}

const MAX_DIFF_BYTES = 200_000;
interface ReviewInputs {
  version: 1;
  diffSha256: string;
  diffBytes: number;
  chunks: { file: string; bytes: number; sha256: string }[];
}

function sha256(text: string): string {
  return new Bun.CryptoHasher("sha256").update(text).digest("hex");
}

function coverageLine(inputs: ReviewInputs): string {
  return `COVERAGE: ${inputs.diffSha256} ${inputs.chunks.map((chunk) => chunk.sha256).join(" ")}`;
}

/** Preserve every byte, keeping fitting files whole; continuation chunks still belong to ONE review. */
function prepareInputs(dir: string, id: string, n: number, diff: string): ReviewInputs {
  const chunks: string[] = [];
  let current = "";
  for (const file of diff.split(/(?=^diff --git )/m)) {
    if (!file.startsWith("diff --git ")) throw new LedgerError("unrecognized diff boundary");
    if (Buffer.byteLength(file) > MAX_DIFF_BYTES) {
      if (current) chunks.push(current);
      current = "";
      const bytes = Buffer.from(file);
      for (let start = 0; start < bytes.length;) {
        let end = Math.min(start + MAX_DIFF_BYTES, bytes.length);
        // The next chunk must start at a codepoint, never a UTF-8 continuation byte.
        while (end < bytes.length && (bytes[end]! & 0xc0) === 0x80) end--;
        chunks.push(new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(bytes.subarray(start, end)));
        start = end;
      }
      continue;
    }
    if (current && Buffer.byteLength(current) + Buffer.byteLength(file) > MAX_DIFF_BYTES) {
      chunks.push(current);
      current = "";
    }
    current += file;
  }
  if (current) chunks.push(current);
  if (chunks.join("") !== diff) throw new LedgerError("review chunk coverage mismatch");
  const inputs: ReviewInputs = {
    version: 1,
    diffSha256: sha256(diff),
    diffBytes: Buffer.byteLength(diff),
    chunks: chunks.map((text, i) => ({ file: `${id}-${n}-diff-${i + 1}.patch`, bytes: Buffer.byteLength(text), sha256: sha256(text) })),
  };
  const paths = [...inputs.chunks.map((chunk) => `${dir}/${chunk.file}`), `${dir}/${id}-${n}-manifest.json`, `${dir}/${id}-${n}-brief.md`];
  if (paths.some((path) => existsSync(path))) throw new LedgerError("prepared review already exists; preserve its inputs rather than overwrite them");
  inputs.chunks.forEach((chunk, i) => writeFileSync(`${dir}/${chunk.file}`, chunks[i]!, { flag: "wx" }));
  writeFileSync(`${dir}/${id}-${n}-manifest.json`, JSON.stringify(inputs, null, 2) + "\n", { flag: "wx" });
  return inputs;
}

/** A verdict cannot silently omit, replace, or lose a prepared input chunk. */
function assertInputCoverage(dir: string, id: string, n: number, body: string): void {
  // THE MANIFEST IS JSON OFF DISK, SO THE CAST WAS THE LIE AND NOT THE CHECK. `version: 1` is a
  // LITERAL type, so the rule was right that `inputs.version !== 1` can never be true OF A
  // ReviewInputs — and wrong about this line, because nothing had made the parsed value one. Parsing
  // as `unknown` and validating at the boundary is what makes the rest of this function's type
  // honest, and it is the check itself that does the narrowing rather than a cast that assumes the
  // answer (MOD.38; the same premise the campaign ruled on for no-unnecessary-boolean-literal-compare).
  const raw: unknown = JSON.parse(readFileSync(`${dir}/${id}-${n}-manifest.json`, "utf8"));
  if (typeof raw !== "object" || raw === null) throw new LedgerError("invalid review input manifest");
  const manifest = raw as Partial<ReviewInputs>;
  if (manifest.version !== 1 || !Array.isArray(manifest.chunks) || !manifest.chunks.length) throw new LedgerError("invalid review input manifest");
  const inputs = manifest as ReviewInputs;
  const chunks = inputs.chunks.map((chunk, i) => {
    if (chunk.file !== `${id}-${n}-diff-${i + 1}.patch`) throw new LedgerError("review chunk identity mismatch");
    const bytes = readFileSync(`${dir}/${chunk.file}`);
    const text = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(bytes);
    if (bytes.length !== chunk.bytes || chunk.bytes <= 0 || chunk.bytes > MAX_DIFF_BYTES || sha256(text) !== chunk.sha256) {
      throw new LedgerError(`review chunk missing, oversized or changed: ${chunk.file}`);
    }
    return text;
  });
  const diff = chunks.join("");
  if (!diff.startsWith("diff --git ") || Buffer.byteLength(diff) !== inputs.diffBytes || sha256(diff) !== inputs.diffSha256) throw new LedgerError("incomplete review input coverage");
  if (!body.split(/\r?\n/).includes(coverageLine(inputs))) throw new LedgerError("verdict must attest coverage of the full diff and every prepared chunk");
}

async function main(): Promise<number> {
  const argv = Bun.argv.slice(2);
  const command = argv[0];
  if (!command || command === "help") {
    console.log(USAGE);
    return command === "help" ? 0 : 1;
  }
  if (command === "selftest") return selftest();

  const root = repoRoot();
  const rest = argv.slice(1);
  const ledgerPath = flag(rest, "ledger") ?? defaultLedger(root);
  const matrixPath = flag(rest, "matrix") ?? defaultMatrix(root);

  if (command === "prepare") {
    const id = rest[0];
    const diff = flag(rest, "diff");
    if (!id || !diff) {
      throw new LedgerError("prepare requires <ID> --diff <range>");
    }
    const existing = await collectReviews(id, ledgerPath, matrixPath);
    if (existing.length >= MAX_REVIEWS_PER_TARGET) {
      throw new LedgerError(
        `${id}: review cap ${MAX_REVIEWS_PER_TARGET} already reached — cannot prepare another session`,
      );
    }
    const n = existing.length + 1;
    const dir = artifactDir(root, "reviews");
    mkdirSync(dir, { recursive: true });
    const diffOut = Bun.spawnSync(["git", "diff", "--no-ext-diff", "--no-textconv", "--no-color", "--binary", diff], { cwd: root, stdout: "pipe", stderr: "pipe" });
    if (diffOut.exitCode !== 0) throw new LedgerError(`git diff failed: ${diffOut.stderr.toString().trim()}`);
    // Reject non-UTF-8 rather than silently replacing bytes in the review input.
    const diffText = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(diffOut.stdout);
    if (diffText.trim() === "") {
      throw new LedgerError(`empty diff for range "${diff}" — refuse vacuous review`);
    }
    const inputs = prepareInputs(dir, id, n, diffText);
    const briefPath = `${dir}/${id}-${n}-brief.md`;
    const brief = `# Review brief for ${id} (session ${n}/${MAX_REVIEWS_PER_TARGET})

You are an adversarial reviewer subagent. Your ENTIRE review input is this brief and ALL indexed diff chunks below.
Do NOT assume implementer intent. Default-deny: find real defects.

## Rules
- Refute with file:line when possible
- Verdict must be exactly one of: CLEAN or FINDINGS
- If FINDINGS: list each as \`- file:line — claim\`
- No praise padding. No "looks good overall" without CLEAN.

## Complete diff inputs (\`${diff}\`)

ONE reviewer reads every chunk sequentially and returns ONE verdict. Do not delegate subreviews.
Read each file completely, using bounded line ranges if needed; a truncated tool response is not coverage.
For a long/minified line, read bounded UTF-8 byte ranges rather than accepting a truncated line.
Chunks concatenate byte-for-byte to the full diff, with no inserted headers or omitted bytes.
Files that fit stay whole; oversized files continue across chunks at UTF-8 codepoint boundaries.
A continuation may start inside a hunk or line, without a diff header: retain the preceding file/hunk context.
Manifest: ${dir}/${id}-${n}-manifest.json
Full diff: ${inputs.diffBytes} UTF-8 bytes; SHA256 ${inputs.diffSha256}
${inputs.chunks.map((chunk) => `- ${dir}/${chunk.file} (${chunk.bytes} bytes; SHA256 ${chunk.sha256})`).join("\n")}

The scope is every changed file, not a selection. No implementer rationale or campaign notes.

## Output format (raw text)
VERDICT: CLEAN
or
VERDICT: FINDINGS
- path:line — …

After reading ALL chunks, include this exact coverage attestation after your verdict/findings:
${coverageLine(inputs)}
If any input cannot be read completely, report incomplete coverage; do not attest or give CLEAN.

When done, the orchestrator will write your output to an artifact and run:
  bun .dev/campaigns/review.ts record ${id} --verdict … --artifact …
`;
    writeFileSync(briefPath, brief, { flag: "wx" });
    console.log(`brief: ${briefPath}`);
    console.log(``);
    console.log(`═══ ORCHESTRATOR: SPAWN SUBAGENT (not headless CLI) ═══`);
    console.log(`Agent({`);
    console.log(`  description: "review ${id} #${n}",`);
    console.log(`  prompt: readFile("${briefPath}") + " Return VERDICT line first.",`);
    console.log(`  // fresh agent — do not pass implementer rationale`);
    console.log(`})`);
    console.log(`Then save the subagent's final text to an artifact and:`);
    console.log(
      `  bun .dev/campaigns/review.ts record ${id} --verdict clean|findings --artifact <path>`,
    );
    console.log(`══════════════════════════════════════════════════════`);
    return 0;
  }

  if (command === "record") {
    const id = rest[0];
    const verdict = flag(rest, "verdict");
    const artifact = flag(rest, "artifact");
    if (!id || !verdict || !artifact) {
      throw new LedgerError("record requires <ID> --verdict clean|findings --artifact <path>");
    }
    if (verdict !== "clean" && verdict !== "findings") {
      throw new LedgerError("--verdict must be clean or findings");
    }
    if (!existsSync(artifact)) {
      throw new LedgerError(`artifact not found: ${artifact}`);
    }
    // Fail-closed: unparseable / empty artifact
    const body = readFileSync(artifact, "utf8");
    if (body.trim().length < 8) {
      throw new LedgerError("artifact too thin to be a real review output");
    }
    const verdicts = body.split(/\r?\n/).filter((line) => line.startsWith("VERDICT:"));
    if (verdicts.length !== 1 || verdicts[0] !== `VERDICT: ${verdict.toUpperCase()}`) {
      throw new LedgerError("artifact must contain exactly one matching VERDICT line");
    }
    const existing = await collectReviews(id, ledgerPath, matrixPath);
    if (existing.length >= MAX_REVIEWS_PER_TARGET) {
      throw new LedgerError(`${id}: already at max ${MAX_REVIEWS_PER_TARGET} reviews`);
    }
    const n = existing.length + 1;
    assertInputCoverage(artifactDir(root, "reviews"), id, n, body);
    const note = formatReviewNote({
      n,
      verdict,
      artifact,
      at: isoNow(),
    });
    // Prefer ledger item if present, else matrix row
    let wrote = false;
    if (existsSync(ledgerPath)) {
      const lines = await readLines(ledgerPath);
      try {
        notesForId(lines, id, /^\[\[items\]\]\s*$/);
        // throws if missing when we append
        await mutate(ledgerPath, (cur) => appendNoteToId(cur, id, /^\[\[items\]\]\s*$/, note));
        wrote = true;
        console.log(`${id}: review ${n}/${MAX_REVIEWS_PER_TARGET} recorded on ledger (${verdict})`);
      } catch {
        /* not a ledger id */
      }
    }
    if (existsSync(matrixPath)) {
      try {
        await mutate(matrixPath, (cur) => appendNoteToId(cur, id, /^\[\[rows\]\]\s*$/, note));
        wrote = true;
        console.log(`${id}: review ${n}/${MAX_REVIEWS_PER_TARGET} recorded on matrix (${verdict})`);
      } catch {
        /* not a matrix id */
      }
    }
    if (!wrote) throw new LedgerError(`id "${id}" not found in ledger or matrix`);
    const after = await collectReviews(id, ledgerPath, matrixPath);
    const sat = reviewCleanSatisfied(after);
    console.log(sat.ok ? `review-clean: SATISFIED (${sat.detail})` : `review-clean: not yet — ${sat.detail}`);
    return 0;
  }

  if (command === "status") {
    const id = rest[0];
    if (!id) throw new LedgerError("status requires <ID>");
    const reviews = await collectReviews(id, ledgerPath, matrixPath);
    const sat = reviewCleanSatisfied(reviews);
    console.log(`${id}: ${reviews.length}/${MAX_REVIEWS_PER_TARGET} review sessions`);
    for (const r of reviews) {
      console.log(`  #${r.n} ${r.verdict} ${r.artifact}`);
    }
    console.log(sat.ok ? `review-clean: YES — ${sat.detail}` : `review-clean: NO — ${sat.detail}`);
    return sat.ok ? 0 : 1;
  }

  console.log(USAGE);
  return 1;
}

function selftest(): number {
  let fails = 0;
  const check = (l: string, ok: boolean) => {
    if (!ok) {
      console.error(`FAIL ${l}`);
      fails++;
    }
  };
  check("cap is 2", MAX_REVIEWS_PER_TARGET === 2);
  check("min clean is 1", MIN_CLEAN_REVIEWS === 1);
  check(
    "0 clean fails",
    !reviewCleanSatisfied([]).ok,
  );
  check(
    "1 clean ok (operator ruling: one review per item)",
    reviewCleanSatisfied([
      { n: 1, verdict: "clean", artifact: "a", at: "t" },
    ]).ok,
  );
  check(
    "latest findings blocks",
    !reviewCleanSatisfied([
      { n: 1, verdict: "clean", artifact: "a", at: "t" },
      { n: 2, verdict: "findings", artifact: "b", at: "t" },
    ]).ok,
  );
  check(
    "clean after findings satisfies (fix-forward path — grailseeker logic fix)",
    reviewCleanSatisfied([
      { n: 1, verdict: "findings", artifact: "a", at: "t" },
      { n: 2, verdict: "clean", artifact: "b", at: "t" },
    ]).ok,
  );
  check(
    "over-cap reviews fail",
    !reviewCleanSatisfied([
      { n: 1, verdict: "clean", artifact: "a", at: "t" },
      { n: 2, verdict: "clean", artifact: "b", at: "t" },
      { n: 3, verdict: "clean", artifact: "c", at: "t" },
    ]).ok,
  );
  // review-done (operator ruling 2026-08-24, clarified): drift guard — verdict-agnostic
  check("review-done: 0 reviews fails", !reviewDoneSatisfied([]).ok);
  check(
    "review-done: 1 findings ok (verdict-agnostic)",
    reviewDoneSatisfied([{ n: 1, verdict: "findings", artifact: "a", at: "t" }]).ok,
  );
  check(
    "review-done: 2 findings ok (no re-review needed)",
    reviewDoneSatisfied([
      { n: 1, verdict: "findings", artifact: "a", at: "t" },
      { n: 2, verdict: "findings", artifact: "b", at: "t" },
    ]).ok,
  );
  console.log(fails === 0 ? "review selftest ok" : `review selftest ${fails} fail(s)`);
  return fails === 0 ? 0 : 1;
}

try {
  process.exit(await main());
} catch (e) {
  console.error(e instanceof Error ? e.message : e);
  process.exit(1);
}
