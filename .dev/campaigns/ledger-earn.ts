/**
 * Campaign-ledger earned-row helpers: depends + review-clean mandates.
 */
/**
 * `ItemBlock` COMES FROM THE MODULE THAT PRODUCES IT (MOD.38). This file used to declare its own
 * structural copy — `{ item: { id; status }; start; end }` — and take the api as `any`, which is two
 * versions of one shape that can drift, and the weaker one was the one the helpers were written
 * against: every `no-unsafe-*` finding in this file came from a `block` whose type was `any` while a
 * real type for it existed one import away. The api is now the real signatures, so the compiler
 * checks the wiring at the call site instead of trusting it.
 */
import { LedgerError, mutate, type ItemBlock } from "./ledger-core.ts";
import {
  formatDependsNote,
  formatRequireNote,
  missingSlugs,
  parseDepends,
  parseRequires,
  parseChecks,
  parseReceipts,
  parseReviews,
  teachRemedy,
} from "./earn-core.ts";

function notes(lines: readonly string[], block: ItemBlock): string[] {
  return lines
    .slice(block.start, block.end)
    .filter((l) => l.trimStart().startsWith("#"))
    .map((l) => l.trim());
}

function append(lines: readonly string[], block: ItemBlock, note: string): string[] {
  const next = [...lines];
  next.splice(block.end, 0, note);
  return next;
}

export async function handleLedgerEarn(
  ledgerPath: string,
  command: string,
  rest: readonly string[],
  api: {
    locateItems: (lines: readonly string[]) => ItemBlock[];
    findBlock: (blocks: readonly ItemBlock[], id: string) => ItemBlock;
    flag: (argv: readonly string[], name: string) => string | null;
  },
): Promise<boolean> {
  const { locateItems, findBlock } = api;

  if (command === "depends") {
    const id = rest[0] ?? "";
    const deps = (rest[1] ?? "").split(",").map((s) => s.trim()).filter(Boolean);
    if (!id || deps.length === 0) {
      throw new LedgerError("depends: usage depends <ID> <depId[,depId…]>");
    }
    await mutate(ledgerPath, (current) => {
      const block = findBlock(locateItems(current), id);
      const existing = parseDepends(notes(current, block));
      const merged = [...new Set([...existing, ...deps])];
      const next = [...current];
      const re = /^#\s*depends:/;
      for (let i = block.start; i < block.end; i++) {
        if (re.test((next[i] ?? "").trim())) {
          next[i] = formatDependsNote(merged);
          return next;
        }
      }
      return append(next, block, formatDependsNote(merged));
    });
    console.log(`${id}: depends += ${deps.join(",")}`);
    return true;
  }

  if (command === "require") {
    const id = rest[0] ?? "";
    const status = rest[1] ?? "";
    const slugs = (rest[2] ?? "").split(",").map((s) => s.trim()).filter(Boolean);
    if ((status !== "done" && status !== "verified") || !id || slugs.length === 0) {
      throw new LedgerError(
        "require: usage require <ID> <done|verified> <slug[,…]>  (done maps to ready-style mandates)",
      );
    }
    // Map done → ready marker for shared parser
    const gate = status === "done" ? "ready" : "verified";
    await mutate(ledgerPath, (current) => {
      const block = findBlock(locateItems(current), id);
      const n = notes(current, block);
      const req = parseRequires(n);
      const bucket = gate === "ready" ? req.ready : req.verified;
      const merged = [...new Set([...bucket, ...slugs])];
      const next = [...current];
      const re = new RegExp(`^#\\s*require:${gate}:`);
      for (let i = block.start; i < block.end; i++) {
        if (re.test((next[i] ?? "").trim())) {
          next[i] = formatRequireNote(gate, merged);
          return next;
        }
      }
      return append(next, block, formatRequireNote(gate, merged));
    });
    console.log(`${id}: require ${status} += ${slugs.join(",")}`);
    return true;
  }

  // GRAILSEEKER DELTA (2026-08-24): exact-set variant of require — the union-only verb
  // can never REMOVE a mandate slug, so an operator ruling that swaps one mandate for
  // another (review-clean → review-done) had no lawful CLI path. Empty slug list removes
  // the require line. The mutation is journal'd by mutate() like every other ledger write.
  if (command === "require-set") {
    const id = rest[0] ?? "";
    const status = rest[1] ?? "";
    const slugs = (rest[2] ?? "").split(",").map((s) => s.trim()).filter(Boolean);
    if ((status !== "done" && status !== "verified") || !id) {
      throw new LedgerError(
        "require-set: usage require-set <ID> <done|verified> <slug[,…]>  (empty slug list removes the require line)",
      );
    }
    const gate = status === "done" ? "ready" : "verified";
    await mutate(ledgerPath, (current) => {
      const block = findBlock(locateItems(current), id);
      const next = [...current];
      const re = new RegExp(`^#\\s*require:${gate}:`);
      for (let i = block.start; i < block.end; i++) {
        if (re.test((next[i] ?? "").trim())) {
          if (slugs.length === 0) next.splice(i, 1);
          else next[i] = formatRequireNote(gate, slugs);
          return next;
        }
      }
      if (slugs.length === 0) return next; // nothing to remove
      return append(next, block, formatRequireNote(gate, slugs));
    });
    console.log(`${id}: require ${gate} = [${slugs.join(", ")}]`);
    return true;
  }

  if (command === "remedy") {
    const id = rest[0] ?? "";
    const { readLines } = await import("./ledger-core.ts");
    const lines = await readLines(ledgerPath);
    const block = findBlock(locateItems(lines), id);
    const n = notes(lines, block);
    const req = parseRequires(n);
    console.log(
      teachRemedy(
        id,
        "ready",
        missingSlugs(req.ready, parseChecks(n), parseReceipts(n), parseReviews(n)),
      ),
    );
    console.log(
      teachRemedy(
        id,
        "verified",
        missingSlugs(req.verified, parseChecks(n), parseReceipts(n), parseReviews(n)),
      ),
    );
    const deps = parseDepends(n);
    if (deps.length) console.log(`depends: ${deps.join(", ")}`);
    return true;
  }

  return false;
}

/** When setting done/verified, enforce require:* diary mandates if present. */
export function assertItemMandates(
  id: string,
  status: string,
  noteLines: readonly string[],
): void {
  if (status !== "done" && status !== "verified") return;
  const req = parseRequires(noteLines);
  const want =
    status === "done" ? req.ready : [...new Set([...req.ready, ...req.verified])];
  if (want.length === 0) return; // no mandates declared — legacy items ok
  const miss = missingSlugs(
    want,
    parseChecks(noteLines),
    parseReceipts(noteLines),
    parseReviews(noteLines),
  );
  if (miss.length > 0) {
    throw new LedgerError(teachRemedy(id, status === "done" ? "ready" : "verified", miss));
  }
}
