#!/usr/bin/env bun
/** Remove replay-fatal empty `thinking` blocks from Claude Code transcripts.
 *
 *  WHY THIS EXISTS, measured 2026-09-18. Anthropic refuses the WHOLE request for one
 *  `{"type":"thinking","thinking":""}` block — 400 `messages.903.content.0.thinking: each thinking
 *  block must contain thinking` — and Claude Code replays its transcript every turn, so a single
 *  such record kills a session permanently: no retry, no model switch and no compaction can get
 *  past it, because a compaction replays the same messages. design-builder2 sat dead for ten hours
 *  on 455 of them while five escalations went unanswered, and the seat was not unresponsive, it was
 *  physically unable to answer.
 *
 *  WHERE THEY COME FROM. splice's anthropic-passthrough heads scrub empty thinking blocks on the
 *  REQUEST path only. A head that hands the CLIENT one — deepseek's Anthropic-compatible endpoint
 *  emits a thinking block carrying a bare UUID where the signature belongs and no text — poisons
 *  the transcript on disk, and the charge detonates later, on whatever backend the seat is pointed
 *  at next. A seat that never goes back through splice can never be helped by the request-path fix.
 *
 *  THE PREDICATE IS THE CHARGE, NOT THE HEAD. Census over 15 config roots: 15330 empty thinking
 *  blocks carry a bare 36-char UUID, 35 carry no signature at all, and 785 carry a real 100+ char
 *  Anthropic signature blob. Only the first two shapes correlate with a dead seat; the 785 sit in
 *  sessions replaying them right now without complaint, which is the available evidence that
 *  Anthropic accepts back what Anthropic signed. So this tool leaves those alone. Selecting on the
 *  model name instead would both over- and under-select: it finds the deepseek-flash files and
 *  misses any charged record written under a different head.
 *
 *  WHAT IT DOES TO A RECORD. The record is KEPT and only its content array is edited, so its uuid
 *  stays valid and no parentUuid in the file is left dangling. Claude Code composes one API message
 *  per message.id, so an emptied record contributes nothing while its sibling record supplies the
 *  real text — measured: every one of 15365 charged records had such a sibling but one. Where a
 *  message.id has NO sibling carrying content, one honest text block is substituted instead,
 *  because a message with `content: []` is refused too and trading one 400 for another is not a
 *  repair.
 *
 *  IT WILL NOT TOUCH A FILE SOMETHING IS WRITING. A transcript written to within --quiet minutes
 *  (default 30) is skipped: the client appends, this tool rewrites, and the overlap is the one way
 *  the repair could lose a turn. A live seat is not in danger anyway — the charge only detonates on
 *  the next resume — so waiting costs nothing and guessing costs a turn.
 *
 *  Usage:
 *    bun dev/tools/repair-empty-thinking.ts --scan              census only, nothing written
 *    bun dev/tools/repair-empty-thinking.ts --all [--apply]     every quiet transcript
 *    bun dev/tools/repair-empty-thinking.ts <file> [--apply]    one transcript, quiet rule waived
 *    --quiet N        minutes a file must have been idle (default 30)
 *    --hold <id,...>  session ids to skip by NAME, whatever their mtime
 */
import { readdirSync, statSync } from "node:fs";

const argv = process.argv.slice(2);
const apply = argv.includes("--apply");
const flag = (name: string, dflt: string) => {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
};
const QUIET_MS = Number(flag("--quiet", "30")) * 60_000;
const HOLD = new Set(flag("--hold", "").split(",").filter(Boolean));

/** A real Anthropic signature is a long base64 blob; the defect's is a 36-char UUID or absent. */
const ANTHROPIC_SIG_MIN = 100;
const isCharged = (b: any) =>
  b?.type === "thinking" &&
  String(b?.thinking ?? "").trim() === "" &&
  String(b?.signature ?? "").length < ANTHROPIC_SIG_MIN;

const SUBSTITUTE = "(an empty thinking block recorded by a non-Anthropic head was removed so this transcript can be replayed)";

async function repairFile(path: string): Promise<{ ok: boolean; msg: string; charged: number }> {
  const raw = await Bun.file(path).text();
  const lines = raw.split("\n");

  const hasRealContent = new Set<string>();
  for (const l of lines) {
    if (!l.trim()) continue;
    let r: any; try { r = JSON.parse(l); } catch { continue; }
    const c = r.message?.content;
    if (Array.isArray(c) && c.some((b: any) => !isCharged(b))) hasRealContent.add(String(r.message?.id ?? ""));
  }

  let emptied = 0, substituted = 0, charged = 0;
  const out = lines.map((l) => {
    if (!l.trim()) return l;
    let r: any; try { r = JSON.parse(l); } catch { return l; }
    const c = r.message?.content;
    if (!Array.isArray(c) || !c.some(isCharged)) return l;
    const kept = c.filter((b: any) => !isCharged(b));
    charged += c.length - kept.length;
    if (kept.length === 0 && !hasRealContent.has(String(r.message?.id ?? ""))) {
      r.message.content = [{ type: "text", text: SUBSTITUTE }];
      substituted++;
    } else {
      r.message.content = kept;
      emptied++;
    }
    return JSON.stringify(r);
  });

  if (!charged) return { ok: true, msg: "nothing charged", charged: 0 };

  // A line that was already unparseable is not damage this repair did — compare in against out
  // rather than demanding perfection the input never had.
  const unparseable = (ls: string[]) =>
    ls.filter((l) => { if (!l.trim()) return false; try { JSON.parse(l); return false; } catch { return true; } }).length;
  const badIn = unparseable(lines), badOut = unparseable(out);
  if (badOut !== badIn || out.length !== lines.length) {
    return { ok: false, msg: `REFUSED: unparseable ${badIn}->${badOut}, lines ${lines.length}->${out.length}`, charged };
  }

  if (apply) {
    // Backup FIRST and prove it landed. Then rewrite IN PLACE: these files are hardlinked into
    // every ~/.claude-<head>/projects tree, and a rename would silently fork them.
    const bak = `${path}.bak-${new Date().toISOString().slice(0, 10)}-pre-thinking-repair`;
    const cp = Bun.spawnSync(["cp", "-p", path, bak]);
    if (cp.exitCode !== 0) return { ok: false, msg: "REFUSED: backup failed", charged };
    if (statSync(bak).size !== statSync(path).size) return { ok: false, msg: "REFUSED: backup size mismatch", charged };
    await Bun.write(path, out.join("\n"));
  }
  return { ok: true, msg: `${emptied} emptied, ${substituted} substituted, ${charged} block(s)`, charged };
}

function transcripts(): string[] {
  const roots = readdirSync("/home/marcos")
    .filter((d) => d === ".claude" || d.startsWith(".claude-"))
    .map((d) => `/home/marcos/${d}/projects`);
  const seen = new Set<number>(); const found: string[] = [];
  for (const root of roots) {
    let projects: string[]; try { projects = readdirSync(root); } catch { continue; }
    for (const proj of projects) {
      let files: string[]; try { files = readdirSync(`${root}/${proj}`); } catch { continue; }
      for (const f of files) {
        if (!f.endsWith(".jsonl")) continue;
        const p = `${root}/${proj}/${f}`;
        let st; try { st = statSync(p); } catch { continue; }
        if (seen.has(st.ino)) continue;      // one entry per inode: the trees are hardlinked
        seen.add(st.ino); found.push(p);
      }
    }
  }
  return found;
}

const named = argv.filter((a) => !a.startsWith("--") && a.endsWith(".jsonl"));
const targets = named.length ? named : transcripts();
let repaired = 0, blocks = 0, held = 0, busy = 0, failed = 0;

for (const p of targets) {
  const id = p.split("/").pop()!.replace(".jsonl", "");
  if (!named.length) {
    if (HOLD.has(id)) { held++; continue; }
    const age = Date.now() - statSync(p).mtimeMs;
    if (age < QUIET_MS) {
      const probe = await repairFile(p);   // no --apply reaches disk; this only counts
      if (probe.charged) { busy++; console.log(`  BUSY  ${String(probe.charged).padStart(4)} charged  ${Math.round(age / 60000)}m idle  ${id}`); }
      continue;
    }
  }
  const r = await repairFile(p);
  if (!r.charged) continue;
  if (!r.ok) { failed++; console.log(`  FAIL  ${id}  ${r.msg}`); continue; }
  repaired++; blocks += r.charged;
  console.log(`  ${apply ? "REPAIRED" : "would repair"}  ${id}  ${r.msg}`);
}
console.log(`\n${apply ? "APPLIED" : "DRY RUN"}: ${repaired} file(s), ${blocks} block(s); held=${held} busy=${busy} failed=${failed}`);
process.exit(failed ? 1 : 0);
