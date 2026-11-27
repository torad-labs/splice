import assert from "node:assert/strict";
import { appendFileSync, existsSync, readFileSync, readdirSync, readlinkSync, statSync } from "node:fs";
import { join, resolve } from "node:path";
import { freePort } from "../../../console-own/splice.ts";
import type { Answer } from "../mods/fake-claude.ts";
import { BASIC, CWD_PROBE, LABEL_PROBE, MANAGEMENT, MODEL_PROBE, ModRun, PROVIDER, SECRET_HEADERS, step, until, type ClientReport, type Event } from "../mods/run.ts";

export const usage = "mods --artifact <jar>   prove the mod runner on a scratch Linux daemon";
const sampleTurns = 20;
const idleSessions = 100;
const idleSeconds = 60;

function answer(report: ClientReport, expected = BASIC, baseline?: Answer): Answer {
  assert.ok(report.answers.length > 0);
  for (const current of report.answers) {
    assert.equal(current.text, expected, "changed answer");
    if (baseline) assert.equal(current.frames, baseline.frames, "changed SSE frames (excluding fresh message ID)");
  }
  return report.answers[0]!;
}

function complete(events: Event[], n = 1): boolean {
  return events.filter(e => e.name === "turn.complete").length === n;
}

function ordered(events: Event[], labels: Record<string, string>, count: number): void {
  assert.ok(events.every(e => {
    const actual = e.payload.labels;
    return actual && Object.keys(actual).length === Object.keys(labels).length &&
      Object.entries(labels).every(([key, value]) => actual[key] === value);
  }), "a session or turn event lost its launch labels");
  const turns = new Set(events.flatMap(e => e.payload.turn ? [e.payload.turn] : []));
  assert.equal(turns.size, count);
  for (const turn of turns) {
    const byTurn = events.filter(e => e.payload.turn === turn);
    assert.deepEqual(byTurn.slice(0, 3).map(e => e.name), ["turn.start", "request.send", "response.receive"]);
    assert.equal(byTurn.at(-1)?.name, "turn.complete");
    const frames = byTurn.slice(3, -1);
    assert.ok(frames.length > 0 && frames.every(e => e.name === "stream.event"));
    assert.deepEqual(frames.map(e => e.payload.seq), frames.map((_, i) => i + 1), "missing or reordered stream events");
  }
}

async function recordings(run: ModRun, baseline: Answer): Promise<void> {
  step(1, "Install a recorder; two labeled sessions, then resume the first from a new process");
  await run.install("recorder");
  const a = { SPLICE_LABEL_CAMPAIGN: "c1", SPLICE_LABEL_ROLE: "worker" };
  const b = { SPLICE_LABEL_CAMPAIGN: "c1", SPLICE_LABEL_ROLE: "coordinator" };
  const first = run.client("session-one", ["first", "second", "third"], { env: a, interactive: true, waitForHook: true });
  const startup = await run.recordThrough("session-one", es => es.some(e => e.name === "session.start"));
  assert.deepEqual(startup.map(e => e.name), ["session.start"], "the startup hook must announce the session before any turn");
  await first.release();
  const initial = await first.report;
  assert.ok(initial.hooks, "fake client did not run the startup hook installed by the head recipe");
  answer(initial, BASIC, baseline);
  const second = run.client("session-two", ["first", "second"], { env: b, interactive: true });
  answer(await second.report, BASIC, baseline);
  await first.close();
  const before = await run.recordThrough("session-one", es => es.some(e => e.name === "session.end"));
  assert.equal(before.filter(e => e.name === "session.start").length, 1);
  assert.equal(before[0]?.name, "session.start");
  assert.equal(before[0]?.payload.seenBefore, false);
  assert.equal(before.at(-1)?.name, "session.end");
  ordered(before, { campaign: "c1", role: "worker" }, 3);
  const resumed = run.client("session-one", ["resumed"], { env: b, interactive: true, resume: true, waitForHook: true });
  assert.notEqual(resumed.pid, first.pid);
  const restarted = await run.recordThrough("session-one", es => es.some(e => e.name === "session.start" && e.payload.seenBefore));
  assert.deepEqual(restarted.slice(before.length).map(e => e.name), ["session.start"]);
  console.log("  startup and resume SessionStart hooks both reached the recorder before the fake client sent a turn.");
  await resumed.release();
  const resumedReport = await resumed.report;
  assert.ok(resumedReport.hooks, "fake client did not run the resume hook installed by the head recipe");
  answer(resumedReport, BASIC, baseline);
  const again = await run.recordThrough("session-one", es => complete(es, 4));
  const tail = again.slice(before.length);
  assert.equal(tail[0]?.name, "session.start");
  assert.equal(tail[0]?.payload.seenBefore, true);
  assert.equal(tail.filter(e => e.name === "session.start").length, 1);
  ordered(tail, { campaign: "c1", role: "coordinator" }, 1);
  const other = await run.recordThrough("session-two", es => complete(es, 2));
  assert.equal(other[0]?.name, "session.start");
  assert.equal(other[0]?.payload.seenBefore, false);
  assert.equal(other.filter(e => e.name === "session.start").length, 1);
  ordered(other, { campaign: "c1", role: "coordinator" }, 2);
  console.log("Recorder store, in delivery order (stream frame sequence numbers grouped per turn):");
  const events = run.events().filter(e => [undefined, "session-one", "session-two"].includes(e.payload.session));
  let frames: number[] = [];
  for (const { name, payload: p } of events) {
    if (name === "stream.event") {
      assert.ok(p.seq !== undefined);
      frames.push(p.seq);
    } else {
      if (frames.length) console.log(`  stream.event seq=${frames.join(",")}`);
      frames = [];
      console.log("  " + JSON.stringify({ event: name, session: p.session, turn: p.turn, labels: p.labels, seenBefore: p.seenBefore }));
    }
  }
  const rows = await run.sessions();
  for (const id of ["session-one", "session-two"]) {
    const matching = rows.filter(row => row.session_id === id);
    assert.equal(matching.length, 1, `${id} must have one row`);
    assert.deepEqual(matching[0]?.labels, { campaign: "c1", role: "coordinator" });
    console.log("GET /api/sessions: " + JSON.stringify({ session_id: id, labels: matching[0]?.labels }));
  }
  await Promise.all([second.close(), resumed.close()]);

  console.log("1b. Unserved hooks and declarations are named; the same mod still receives session.start");
  const mod = await run.install("unserved", "unserved", {
    commands: [{ name: "probe", description: "Scratch command declaration." }],
  });
  assert.deepEqual(mod.served, ["session.start"]);
  for (const hook of ["ui.render", "launch.prepare"]) {
    assert.ok(mod.refused?.some(reason => reason.includes(`${hook} is not served yet`)), `missing refusal for ${hook}`);
  }
  assert.ok(mod.declaredNotServed?.some(reason => reason.includes("commands: probe is declared but not served yet")));
  const list = await run.command("mod", "list");
  for (const phrase of ["ui.render is not served yet", "launch.prepare is not served yet", "commands: probe is declared but not served yet"]) {
    assert.ok(list.includes(phrase), `splice mod list omitted ${phrase}`);
  }
  answer(await run.client("unserved-probe", ["ordinary turn"]).report, BASIC, baseline);
  const seen = "observed session.start: unserved-probe";
  await until(seen, () => run.log("unserved").includes(seen) ? true : undefined);
  console.log(`  unserved.log: ${seen}`);
  assert.equal((await run.mod("unserved")).lastError, undefined);
  await run.command("mod", "remove", "unserved");
}

async function misbehaving(run: ModRun, baseline: Answer, burst: Answer): Promise<void> {
  step(2, "Hang, throw, SIGKILL mid-turn, and flood the log without changing any answer");
  for (const name of ["hang", "throw", "crash", "flood"] as const) await run.install(name, name);
  for (let i = 1; i <= 4; i++) {
    const { pid, served, dropped } = await run.running("crash");
    assert.deepEqual(served, ["request.send", "response.receive", "stream.event", "tool.call", "turn.complete", "turn.start"]);
    const id = `kill-probe-${i}`;
    const client = run.client(id, [`SCENARIO:gate RUN:${i}`], { env: { SPLICE_MODS_OFF: "hang" } });
    const pending = await run.recordThrough(id, es => es.some(e => e.name === "stream.event" && (e.payload.seq ?? 0) >= 2) && !complete(es));
    const message = `crash fixture handling ${id} stream seq=1`;
    await until(message, () => run.log("crash").includes(message) ? true : undefined);
    const handlers = run.log("crash").split("\n").filter(line => line.includes(`crash fixture handling ${id} stream seq=`));
    assert.equal(handlers.length, 1, "the target must still be in its first stream handler");
    assert.equal((await run.mod("crash")).dropped, dropped, "later frames must be queued, not newly dropped");
    const frames = pending.filter(e => e.name === "stream.event").length;
    console.log(`  crash.log: ${message}; ${frames - 1} later frame(s) queued behind that handler while upstream is paused.`);
    console.log(`$ kill -KILL ${pid}  # mod host while turn ${i} is open`);
    run.killOwned(pid, "SIGKILL");
    // Let the old process exit and report its death before letting the provider finish the same turn.
    const killed = await until("killed mod state reflected in GET /api/mods", async () => {
      const row = await run.mod("crash");
      if (i === 4 ? row.state === "failed" : row.state === "restarting" || (row.pid && row.pid !== pid)) return row;
    });
    assert.ok(killed.dropped - dropped >= frames - 1, "killing the crash fixture must discard its queued stream frames");
    console.log(`  GET /api/mods counted ${killed.dropped - dropped} queued event(s) lost on death.`);
    if (i === 3) {
      const listed = await run.command("mod", "list");
      assert.match(listed, /crash .*restarting.*exit code 137.*25 s/);
    }
    await Bun.write(run.gate, String(i));
    answer(await client.report, BASIC, baseline);
    const row = await run.mod("crash");
    console.log(`  same completed SSE answer; crash state=${row.state}, restarts=${row.restartsInWindow}`);
    if (i < 4) {
      const next = await run.running("crash");
      assert.notEqual(next.pid, pid, "did not start a new mod host");
    }
  }
  const busy = run.client("burst-with-hang", ["SCENARIO:burst"], { env: { SPLICE_MODS_OFF: "recorder" } });
  answer(await busy.report, burst.text, burst);
  await until("a handler timeout and dropped stream events", async () => {
    const mod = await run.mod("hang");
    if (mod.dropped > 0 && /tim(e|ed)[ -]?out|exceed|5\s*s|5000/i.test(mod.lastError ?? "")) return mod;
  });
  const out = await run.command("mod", "list");
  const rows = await run.mods();
  const row = (name: string) => rows.find(m => m.name === name)!;
  assert.ok((row("hang").droppedByEvent["stream.event"] ?? 0) > 0);
  assert.equal(row("hang").state, "running");
  assert.match(row("throw").lastError ?? "", /fixture handler threw/);
  assert.equal(row("throw").state, "running");
  assert.ok(row("flood").droppedLogLines > 0);
  assert.equal(row("flood").state, "running");
  assert.equal(row("crash").failed, true);
  assert.equal(row("crash").restartsInWindow, 3);
  assert.match(row("crash").why ?? "", /exited 4 times.*137/);
  for (const phrase of ["dropped events:", "fixture handler threw", "dropped log lines:", "exited 4 times"]) assert.ok(out.includes(phrase));
  console.log("GET /api/mods:");
  for (const { name, state, dropped, droppedByEvent, droppedLogLines, restartsInWindow, failed, why, lastError } of rows) {
    console.log("  " + JSON.stringify({ name, state, dropped, droppedByEvent, droppedLogLines, restartsInWindow, failed, why, lastError }));
  }
  console.log(`Normal and burst answers match their no-mod baselines, including SSE frames (except message ID; ${burst.text.length} text bytes in burst).`);
}

async function optOut(run: ModRun, baseline: Answer): Promise<void> {
  step(3, "SPLICE_MODS_OFF=1 and a name list suppress all session and turn events for the selected mods");
  await run.install("witness");
  for (const [id, off] of [["off-all", "1"], ["off-list", "recorder, hang"]] as const) {
    answer(await run.client(id, ["opt-out probe"], { env: { SPLICE_MODS_OFF: off } }).report, BASIC, baseline);
  }
  answer(await run.client("off-barrier", ["both recorders again"]).report, BASIC, baseline);
  for (const name of ["recorder", "witness"]) {
    await run.recordThrough("off-barrier", es => es.some(e => e.name === "session.end"), name);
    const records = run.events(name);
    const counts = Object.fromEntries(["off-all", "off-list", "off-barrier"].map(id =>
      [id, records.filter(e => e.payload.session === id).length]));
    assert.equal(counts["off-all"], 0);
    if (name === "recorder") assert.equal(counts["off-list"], 0);
    else {
      const witnessed = records.filter(e => e.payload.session === "off-list");
      assert.ok(witnessed.some(e => e.name === "session.start") && complete(witnessed));
      assert.ok(witnessed.some(e => e.name === "session.end"));
    }
    console.log(`  ${name} event counts: ${JSON.stringify(counts)}`);
  }
}

async function changed(run: ModRun, baseline: Answer): Promise<void> {
  step(4, "Refuse a changed installed file until the operator explicitly accepts it");
  const name = "recorder";
  const path = join(run.home.state, "mods", "installed", name, "hooks", "register.ts");
  console.log(`$ append comment to ${path}`);
  const original = await run.running(name);
  appendFileSync(path, "\n// changed after installation\n");
  const refusal = await run.cli("mod", "enable", name);
  assert.notEqual(refusal.code, 0);
  const unchanged = await run.running(name);
  assert.equal(unchanged.pid, original.pid, "refusal must not load the modified files or stop the accepted code");
  console.log(`  GET /api/mods: refusal left the original accepted process running (pid=${unchanged.pid}).`);
  assert.match(refusal.out, /changed since install/);
  assert.match(refusal.out, /hooks\/register\.ts/);
  assert.match(refusal.out, /--accept/);
  await run.command("mod", "enable", name, "--accept");
  const accepted = await run.running(name);
  assert.notEqual(accepted.pid, original.pid, "--accept must load the accepted revision");
  const id = "accepted-files";
  answer(await run.client(id, ["turn after accepting the changed source"]).report, BASIC, baseline);
  await run.recordThrough(id, es => complete(es));
  console.log(`  GET /api/mods: ${name} is ${accepted.state}, pid=${accepted.pid}; accepted revision recorded a turn.`);
}

async function stops(run: ModRun, baseline: Answer): Promise<void> {
  step(5, "Prove disable, remove, clean shutdown, and recovery after an unclean daemon death");
  for (const [verb, mod] of [["disable", "flood"], ["remove", "hang"]] as const) {
    const { pid } = await run.running(mod);
    const before = run.ps(`before ${verb} ${mod}`);
    assert.ok(before.some(p => p.mod && p.pid === pid));
    await run.command("mod", verb, mod);
    const after = run.ps(`after ${verb} ${mod}`);
    assert.ok(!after.some(p => p.pid === pid), `${mod} host survived ${verb}`);
    assert.deepEqual(after.filter(p => p.mod).map(p => p.pid), before.filter(p => p.mod && p.pid !== pid).map(p => p.pid));
  }
  const before = run.ps("before daemon stop");
  assert.ok(before.some(p => p.mod));
  await run.stop();
  run.ps("after daemon stop", []);
  const daemon = await run.start();
  await run.install("orphan", "orphan");
  const orphan = await run.running("orphan");
  answer(await run.client("daemon-kill", ["leave a mod inside a non-CPU-spinning handler"]).report, BASIC, baseline);
  const marker = join(run.data("orphan"), "blocked");
  await until("the orphan fixture entered its blocking handler", () =>
    existsSync(marker) && readFileSync(marker, "utf8") === String(orphan.pid) ? true : undefined);
  const old = run.ps("before daemon SIGKILL").filter(p => p.mod);
  assert.ok(old.some(p => p.pid === orphan.pid));
  await run.killedDaemon(daemon.pid);
  const survivors = run.ps("after daemon SIGKILL, before restart");
  assert.ok(!survivors.some(p => p.pid === daemon.pid));
  assert.ok(survivors.some(p => p.mod && p.pid === orphan.pid), "the blocked orphan must still be running");
  const next = await run.start();
  assert.notEqual(next.pid, daemon.pid);
  const newOrphan = await run.running("orphan");
  const after = run.ps("after restarting against the same state");
  assert.ok(after.some(p => p.mod && p.pid === newOrphan.pid), "the successor did not start the enabled mod");
  assert.ok(old.every(p => !after.some(now => now.pid === p.pid)), "a host from the killed daemon is still running");
  console.log(`  blocked orphan ${orphan.pid} was reaped; same-state successor started host ${newOrphan.pid}.`);
  await run.command("mod", "remove", "orphan");
  await run.stop();
}

async function handover(run: ModRun, baseline: Answer): Promise<boolean> {
  step(6, "Hand over two streaming turns under the real supervisor, one with an orphan holding stderr");
  await run.start();
  const probe = await run.daemon.api("/api/mods/handover", { method: "POST", body: "[]" });
  const body = await probe.text();
  if (probe.status === 404 || probe.status === 405) {
    console.log(`SKIPPED: POST /api/mods/handover returned ${probe.status}; the mod event forwarding route is absent.`);
    return false;
  }
  assert.ok(probe.ok || probe.status === 400, `POST /api/mods/handover probe: ${probe.status} ${body}`);
  for (const mod of await run.mods()) if (mod.name !== "recorder" && mod.state !== "disabled") {
    await run.command("mod", "disable", mod.name);
  }
  await run.stop();
  const { supervisor } = await run.start(true);
  await handoverTurn(run, baseline, supervisor, "handover-probe", "5");
  await run.install("stderrorphan", "stderr-orphan");
  await handoverTurn(run, baseline, supervisor, "handover-orphan", "6");
  return true;
}

async function orphanHoldingStderr(run: ModRun): Promise<number> {
  const { pid: host } = await run.running("stderrorphan");
  const pid = await until("background sleep retaining stderr", () => {
    const match = /background sleep pid=(\d+)/.exec(run.log("stderrorphan"));
    if (match) return Number(match[1]);
  });
  const tree = run.processes();
  const child = tree.find(p => p.pid === pid);
  assert.ok(child && child.parent !== host, "the sleep must outlive the shell that created it");
  assert.equal(child.group, tree.find(p => p.pid === host)?.group);
  assert.equal(readlinkSync(`/proc/${pid}/fd/2`), readlinkSync(`/proc/${host}/fd/2`));
  console.log(`  Reparented sleep ${pid} still holds host ${host}'s stderr pipe.`);
  return pid;
}

async function handoverTurn(run: ModRun, baseline: Answer, supervisor: number, session: string, gate: string): Promise<void> {
  const { pid: oldDaemon } = await run.health();
  const old = await run.running("recorder");
  const marker = join(run.data("recorder"), "previous-recorder");
  await Bun.write(marker, String(old.pid));
  const client = run.client(session, [`SCENARIO:gate RUN:${gate}`]);
  const prefix = await run.recordThrough(session, es => es.some(e => e.name === "stream.event") && !complete(es));
  const orphan = session === "handover-orphan" ? await orphanHoldingStderr(run) : undefined;
  const oldProcesses = run.ps("before HUP").filter(p => p.mod || p.pid === orphan);
  const retiredAt = run.bootLog().length;
  console.log(`$ kill -HUP ${supervisor}  # turn already streaming on daemon ${oldDaemon}`);
  const started = performance.now();
  run.killOwned(supervisor, "SIGHUP");
  const successor = await run.health(oldDaemon);
  const next = await run.running("recorder");
  const readyMs = performance.now() - started;
  assert.notEqual(next.pid, old.pid);
  const during = run.ps("after takeover, before releasing the provider's stream");
  for (const prior of oldProcesses) assert.ok(!during.some(p => p.pid === prior.pid), `old mod process ${prior.pid} survived takeover`);
  assert.ok(during.some(p => p.pid === oldDaemon), "the old daemon did not keep its admitted turn");
  const running = (await run.mods()).filter(row => row.state === "running").map(row => row.pid).sort();
  assert.deepEqual(during.filter(p => p.mod).map(p => p.pid).sort(), running, "more than one host per running mod");
  await Bun.write(run.gate, gate);
  answer(await client.report, BASIC, baseline);
  const events = await run.recordThrough(session, es => complete(es));
  const turns = new Set(events.flatMap(e => e.payload.turn ? [e.payload.turn] : []));
  assert.equal(turns.size, 1);
  const seqs = events.flatMap(e => e.name === "stream.event" ? [e.payload.seq] : []);
  assert.ok(events.length > prefix.length && seqs.length > prefix.filter(e => e.name === "stream.event").length);
  assert.equal(new Set(seqs).size, seqs.length, "duplicated stream events");
  const expectedFrames = (JSON.parse(baseline.frames) as unknown[]).length;
  assert.deepEqual(seqs, Array.from({ length: expectedFrames }, (_, i) => i + 1), "missing or reordered stream events");
  assert.equal(events.filter(e => e.name === "turn.start").length, 1);
  assert.equal(events.filter(e => e.name === "turn.complete").length, 1);
  await until("old daemon finished after releasing the held turn", () =>
    run.processes().some(p => p.pid === oldDaemon) ? undefined : true);
  const finishedMs = performance.now() - started;
  const witness = `startup pid=${next.pid}: previous recorder ${old.pid} was gone at registration`;
  assert.ok(run.log("recorder").includes(witness), "successor recorder did not witness its predecessor gone at startup");
  console.log(`  recorder.log: ${witness}`);
  await Bun.write(marker, "");
  const listed = await run.command("mod", "list");
  const dropped = await run.handoverDropped();
  if (dropped > 0) assert.ok(listed.includes(`Events dropped during daemon handover: ${dropped}.`));
  else console.log("  Events dropped during daemon handover: 0 (GET /api/mods; CLI omits zero).");
  const failed = run.bootLog().slice(retiredAt).split("\n").find(line => /[1-9]\d* events were not delivered across the handover/.test(line));
  assert.ok(!failed, failed ?? "");
  console.log(`  turn ${[...turns][0]}: stream.event seq=[${seqs.join(", ")}], no duplicates or gaps, one completion`);
  console.log(`  ${orphan ? "Stderr-orphan handover" : "Handover"}: successor ${successor.pid} ready in ${(readyMs / 1_000).toFixed(2)} s; old daemon gone in ${(finishedMs / 1_000).toFixed(2)} s.`);
  if (orphan) assert.ok(finishedMs < 20_000, "the stderr-orphan handover took at least 20 s");
}

function median(samples: number[]): number {
  const sorted = samples.toSorted((a, b) => a - b);
  const mid = (sorted.length - 1) / 2;
  return (sorted[Math.floor(mid)]! + sorted[Math.ceil(mid)]!) / 2;
}

function latency(samples: number[]): string {
  const sorted = samples.toSorted((a, b) => a - b);
  const p95 = sorted[Math.ceil(sorted.length * 0.95) - 1]!;
  return `median ${median(samples).toFixed(2)} ms, p95 ${p95.toFixed(2)} ms`;
}

function ticks(pid: number): number {
  const stat = readFileSync(`/proc/${pid}/stat`, "utf8");
  const fields = stat.slice(stat.lastIndexOf(")") + 2).split(" ");
  const total = Number(fields[11]) + Number(fields[12]); // utime + stime, fields 14 and 15
  assert.ok(Number.isFinite(total), `unreadable CPU ticks for pid ${pid}`);
  return total;
}

async function recorder(run: ModRun, enabled: boolean): Promise<number | undefined> {
  const row = (await run.mods()).find(mod => mod.name === "recorder");
  if (enabled) {
    if (!row) await run.install("recorder");
    else if (row.state === "disabled") await run.command("mod", "enable", "recorder");
    return (await run.running("recorder")).pid;
  }
  if (row) await run.command("mod", "remove", "recorder");
  await run.noMods();
}

async function turnTimings(run: ModRun, baseline: Answer, daemon: number): Promise<void> {
  const warmups = 3;
  const order = [false, true, false, true, true, false, true, false];
  const prompts = Array.from({ length: sampleTurns / 4 + warmups }, () => "identical timing turn");
  const points = { noMod: [] as Answer[], withMod: [] as Answer[] };
  console.log("  Interleaved batches (A=no mod, B=recorder): A B A B B A B A; 3 warmups per batch.");
  for (const [i, withMod] of order.entries()) {
    await recorder(run, withMod);
    const id = `timing-${withMod ? "recorder" : "none"}-${i + 1}`;
    const report = await run.client(id, prompts).report;
    answer(report, BASIC, baseline);
    if (withMod) {
      const events = await run.recordThrough(id, es => complete(es, prompts.length));
      assert.ok(events.some(e => e.name === "stream.event"));
    }
    (withMod ? points.withMod : points.noMod).push(...report.answers.slice(warmups));
  }
  assert.equal((await run.health()).pid, daemon);
  for (const [label, samples] of [["no mod", points.noMod], ["all-event recorder", points.withMod]] as const) {
    assert.equal(samples.length, sampleTurns);
    console.log(`  ${label}, daemon ${daemon}, N=${samples.length}:`);
    console.log(`    first byte: ${latency(samples.map(a => a.firstMs))}`);
    console.log(`    total: ${latency(samples.map(a => a.totalMs))}`);
  }
}

function sessionLedger(run: ModRun): Map<string, number> {
  return new Map(files(join(run.home.state, "mods", "sessions")).map(path => [path, statSync(path).mtimeMs]));
}

async function sampleCpu(pids: number[], hz: number): Promise<number[]> {
  const started = performance.now();
  const before = pids.map(ticks);
  await Bun.sleep(idleSeconds * 1_000);
  const after = pids.map(ticks);
  const seconds = (performance.now() - started) / 1_000;
  assert.ok(seconds >= idleSeconds, "CPU window must last at least 60 s");
  return after.map((end, i) => {
    const delta = end - before[i]!;
    assert.ok(delta >= 0);
    const percent = 100 * delta / hz / seconds;
    console.log(`    ${i === 0 ? "daemon" : "recorder Bun host"}: ${delta} ticks in ${seconds.toFixed(2)}s; ${percent.toFixed(3)}% of one CPU`);
    return percent;
  });
}

async function idleTimings(run: ModRun, baseline: Answer, daemon: number): Promise<number> {
  const clients: ReturnType<ModRun["client"]>[] = [];
  const idleCount = async () => (await run.sessions()).filter(row =>
    row.session_id?.startsWith("idle-") && row.availability !== "gone").length;
  const hz = Number(Bun.spawnSync(["getconf", "CLK_TCK"]).stdout.toString());
  assert.ok(hz > 0);
  const medians: { count: number; noMod: number; loaded: number; host: number }[] = [];
  try {
    for (const count of [10, idleSessions]) {
      await recorder(run, false);
      const beforeLaunch = sessionLedger(run);
      console.log(`$ launch to ${count} registered clients with no mods installed  # one turn each, then idle`);
      while (clients.length < count) {
        const start = clients.length;
        const batch = Array.from({ length: Math.min(8, count - start) }, (_, j) =>
          run.client(`idle-${start + j}`, ["one turn before idling"], { interactive: true, quiet: true }));
        clients.push(...batch);
        for (const report of await Promise.all(batch.map(c => c.report))) answer(report, BASIC, baseline);
      }
      const samples = { noMod: [] as number[], loaded: [] as number[], host: [] as number[] };
      const order = [false, true, true, false, false, true];
      for (const [i, withMod] of order.entries()) {
        const mod = await recorder(run, withMod);
        if (!withMod) assert.equal((await run.mods()).length, 0, "the baseline must have no installed mods");
        assert.equal(await idleCount(), count, "GET /api/sessions idle sample size");
        const ledger = i === 0 ? beforeLaunch : sessionLedger(run);
        const pids = mod ? [daemon, mod] : [daemon];
        console.log(`  N=${count}, sample ${i + 1}/6, ${mod ? "recorder loaded" : "no mod installed"}: /proc/{${pids.join(",")}}/stat, ${idleSeconds}s (CLK_TCK=${hz}).`);
        const [cpu, host] = await sampleCpu(pids, hz);
        assert.equal(await idleCount(), count, "idle sessions must stay registered for the whole sample");
        if (withMod) {
          samples.loaded.push(cpu!);
          samples.host.push(host!);
        } else {
          samples.noMod.push(cpu!);
          assert.deepEqual(sessionLedger(run), ledger, "installed-mod-free turns and idle time must not write mod session ledger files");
          console.log("    mods/sessions: no files written.");
        }
      }
      assert.equal(samples.noMod.length, 3);
      assert.equal(samples.loaded.length, 3);
      medians.push({ count, noMod: median(samples.noMod), loaded: median(samples.loaded), host: median(samples.host) });
    }
    assert.equal((await run.health()).pid, daemon);
  } finally {
    await Promise.all(clients.map(c => c.close()));
  }
  console.log("  CPU medians (% of one CPU; 3 samples per condition, 60 s each):");
  console.log("  idle sessions | daemon, no mod | daemon, loaded | mod host | loaded minus no mod (pp)");
  for (const { count, noMod, loaded, host } of medians) {
    console.log(`  ${count} | ${noMod.toFixed(3)} | ${loaded.toFixed(3)} | ${host.toFixed(3)} | ${(loaded - noMod).toFixed(3)}`);
  }
  const [small, large] = medians;
  return (large!.loaded - large!.noMod) - (small!.loaded - small!.noMod);
}

async function timings(run: ModRun, baseline: Answer): Promise<void> {
  step(7, "Interleaved turn latency and same-daemon CPU with 10 and 100 idle sessions");
  const budget = 1; // one percentage point is 0.6 CPU seconds per 60-second sample
  console.log(`  Flat-cost criterion: 90 more idle sessions must not add more than ${budget} percentage point of incremental mod-related daemon CPU.`);
  for (const row of await run.mods()) await run.command("mod", "remove", row.name);
  const { pid } = await run.health();
  await turnTimings(run, baseline, pid);
  const growth = await idleTimings(run, baseline, pid);
  console.log(`  Growth in incremental daemon CPU from N=10 to N=100: ${growth.toFixed(3)} percentage points.`);
  assert.ok(growth <= budget, "the samples did not show flat incremental mod cost within the stated margin");
}

function files(dir: string): string[] {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const path = join(dir, entry.name);
    return entry.isDirectory() ? files(path) : entry.isFile() ? [path] : [];
  });
}

async function secrets(run: ModRun): Promise<void> {
  step(8, "Search what mods received for synthetic credentials, without reading real credentials");
  const dir = run.home.state;
  const state = (file: string) => readFileSync(join(dir, file), "utf8").trim();
  const mcp = state("mcp-hub/access-key");
  const turn = /^Authorization: Bearer (\S+)$/i.exec(state("turn-auth-header"))?.[1];
  assert.ok(turn, "scratch turn bearer was not published");
  const values = {
    "provider key": PROVIDER,
    "Authorization bearer": SECRET_HEADERS.authorization.slice("Bearer ".length),
    "x-api-key": SECRET_HEADERS["x-api-key"],
    cookie: SECRET_HEADERS.cookie.slice("login=".length),
    "management key": MANAGEMENT,
    "MCP access key": mcp,
    "launch turn key": turn,
    "xAI-shaped key in SPLICE_LABEL_PRIVATE": LABEL_PROBE,
    "Google-shaped key in cwd": CWD_PROBE,
    "xAI-shaped key in served model name": MODEL_PROBE,
  };
  assert.equal(state("mgmt-key"), MANAGEMENT);
  assert.equal(state("terminal-key"), values["Authorization bearer"]);
  assert.equal(new Set(Object.values(values)).size, Object.keys(values).length);
  assert.match(mcp, /^[0-9a-f]{64}$/, "the scratch MCP access key was not published");
  assert.match(turn, /^[0-9a-f]{64}$/);
  const mcpProbe = await fetch(run.daemon.url + "/mcp/mod-e2e-not-installed", {
    headers: { authorization: `Bearer ${mcp}` },
  });
  assert.equal(mcpProbe.status, 404, "the scratch MCP bearer must pass auth before this missing-session response");
  await mcpProbe.text();
  console.log("  GET /mcp/mod-e2e-not-installed: passed access-key auth, no such session (404).");
  console.log("  Injecting synthetic keys in a launch label, its cwd, and the model selected through the recipe; withholding the values.");
  const client = run.client("secret-probe", [`synthetic body marker ${mcp}`], {
    env: { SPLICE_LABEL_AUDIT: "yes" },
    privateEnv: { SPLICE_LABEL_PRIVATE: `label containing ${LABEL_PROBE}` },
    cwd: join(run.home.work, `work-${CWD_PROBE}`),
    command: "claude-globex",
    headers: SECRET_HEADERS, interactive: true,
  });
  try {
    const report = await client.report;
    assert.ok(report.model?.includes(MODEL_PROBE), "the recipe did not select the credential-shaped model");
    answer(report);
    const events = await run.recordThrough("secret-probe", es => complete(es));
    const start = events.find(e => e.name === "session.start");
    assert.ok(start?.payload.cwd, "the recorder must receive the session.start cwd field");
    assert.equal(start.payload.labels?.["audit"], "yes", "a safe launch label must survive");
    const turn = events.find(e => e.name === "turn.start");
    assert.ok(turn?.payload.model, "the recorder must receive the requested-model field");
    const dump = await until("recorder's $.sessions.list sample", () => {
      const rows = run.dump("recorder");
      return rows.some(e => e.key === "audit/sessions" && e.value.includes("secret-probe")) ? rows : undefined;
    });
    const assets = files(join(run.logs, "mods")).concat(files(join(dir, "mods/data")));
    assert.ok(assets.some(path => path.endsWith("/recorder.log")));
    assert.ok(assets.some(path => path.endsWith("/recorder/store.sqlite")));
    const surfaces: { source: string; data: Buffer }[] = [
      { source: "store dump and $.sessions.list()", data: Buffer.from(JSON.stringify(dump)) },
    ];
    for (const path of assets) surfaces.push({ source: path, data: readFileSync(path) });
    for (const mod of (await run.mods()).filter(row => row.state === "running")) {
      assert.ok(mod.pid);
      for (const file of ["environ", "cmdline"]) {
        const source: string = `/proc/${mod.pid}/${file}`;
        const data: Buffer = readFileSync(source);
        assert.ok(data.length > 0);
        surfaces.push({ source, data });
      }
    }
    assert.ok(surfaces.some(s => s.source.endsWith("/environ")));
    console.log(`  Searched recorder event dump, $.sessions.list(), all mod logs and stores, and live mod /proc environ/cmdline (${surfaces.length} surfaces).`);
    const leaked: string[] = [];
    for (const [name, value] of Object.entries(values)) {
      const hits = surfaces.filter(s => s.data.includes(value));
      if (hits.length) {
        leaked.push(name);
        console.log(`  ${name}: FOUND in ${hits.map(h => h.source.replace(run.home.root + "/", "")).join(", ")}`);
      } else {
        console.log(`  ${name}: none found`);
      }
    }
    assert.equal(leaked.length, 0, `raw synthetic credentials reached the mod: ${leaked.join(", ")}`);
  } finally {
    await client.close();
  }
}

export async function mods(argv: string[]): Promise<number> {
  if (argv.length === 1 && ["--help", "-h"].includes(argv[0]!)) {
    console.log(usage);
    return 0;
  }
  if (process.platform !== "linux" || argv.length !== 2 || argv[0] !== "--artifact" || !argv[1] || !existsSync(argv[1])) {
    console.error("Linux and an existing packaged jar are required. Usage: " + usage);
    return 2;
  }
  const run = new ModRun(await freePort());
  const signals = { SIGHUP: 129, SIGINT: 130, SIGTERM: 143 } as const;
  const interrupted = (signal: keyof typeof signals) => {
    void run.cleanup().then(() => process.exit(signals[signal]), e => { console.error(e); process.exit(1); });
  };
  for (const signal of Object.keys(signals) as (keyof typeof signals)[]) process.on(signal, interrupted);
  let code = 0;
  const results = new Map<number, "PASS" | "FAIL" | "SKIPPED">();
  const check = async (n: number, probe: () => Promise<void | boolean>) => {
    try {
      results.set(n, await probe() === false ? "SKIPPED" : "PASS");
    } catch (error) {
      results.set(n, "FAIL");
      throw error;
    }
  };
  try {
    await run.prepare(resolve(argv[1]));
    await run.start();
    // The packaged JVM can serve MCP without a native hub. Publish its scratch MCP key through the management route
    // rather than assuming the unneeded native holder will mint it during boot.
    const mcp = await run.daemon.api("/api/mcp/access-key/rotate", { method: "POST", body: "{}" });
    assert.equal(mcp.status, 200, mcp.ok ? "" : await mcp.text());
    await mcp.body?.cancel();
    console.log("POST /api/mcp/access-key/rotate: published the scratch MCP access key; no credentials printed.");
    const baseline = answer(await run.client("baseline", ["before installing mods"]).report);
    const burst = answer(await run.client("burst-baseline", ["SCENARIO:burst"]).report, "x".repeat(4_000));
    console.log(`No-mod baseline: "${baseline.text}"; burst: ${burst.text.length} bytes.`);
    await check(1, () => recordings(run, baseline));
    await check(2, () => misbehaving(run, baseline, burst));
    await check(3, () => optOut(run, baseline));
    await check(4, () => changed(run, baseline));
    await check(5, () => stops(run, baseline));
    await check(6, () => handover(run, baseline));
    await check(7, () => timings(run, baseline)).catch(error => { console.error(error); code = 1; });
    await check(8, async () => {
      await recorder(run, true);
      await secrets(run);
    });
  } catch (error) {
    console.error(error);
    let tail = run.bootLog().slice(-5_000);
    for (const probe of [LABEL_PROBE, CWD_PROBE, MODEL_PROBE]) tail = tail.replaceAll(probe, "[synthetic probe]");
    console.error("Scratch daemon log tail:\n" + tail);
    code = 1;
  } finally {
    try { await run.cleanup(); }
    catch (error) { console.error(error); code = 1; }
    for (const signal of Object.keys(signals) as (keyof typeof signals)[]) process.off(signal, interrupted);
  }
  for (let n = 1; n <= 8; n++) console.log(`Step ${n}: ${results.get(n) ?? "NOT RUN"}`);
  if (code !== 0) {
    console.log("FAIL: mods real run.");
    return code;
  }
  if ([...results.values()].includes("SKIPPED")) {
    console.log("INCOMPLETE: mods real run has skipped steps.");
    return 77;
  }
  console.log("PASS: mods real run.");
  return 0;
}
