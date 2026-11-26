import assert from "node:assert/strict";
import { appendFileSync, existsSync, readFileSync, readdirSync } from "node:fs";
import { join, resolve } from "node:path";
import { freePort } from "../../../console-own/splice.ts";
import type { Answer } from "../mods/fake-claude.ts";
import { BASIC, MANAGEMENT, ModRun, PROVIDER, SECRET_HEADERS, step, until, type ClientReport, type Event } from "../mods/run.ts";

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
}

async function misbehaving(run: ModRun, baseline: Answer, burst: Answer): Promise<void> {
  step(2, "Hang, throw, SIGKILL mid-turn, and flood the log without changing any answer");
  for (const name of ["hang", "throw", "crash", "flood"] as const) await run.install(name, name);
  for (let i = 1; i <= 4; i++) {
    const { pid } = await run.running("crash");
    const id = `kill-probe-${i}`;
    const client = run.client(id, [`SCENARIO:gate RUN:${i}`], { env: { SPLICE_MODS_OFF: "hang" } });
    await run.recordThrough(id, es => es.some(e => e.name === "stream.event") && !complete(es));
    console.log(`$ kill -KILL ${pid}  # mod host while turn ${i} is open`);
    run.killOwned(pid, "SIGKILL");
    // Let the old process exit and report its death before letting the provider finish the same turn.
    await until("killed mod state reflected in GET /api/mods", async () => {
      const row = await run.mod("crash");
      if (row.state === (i === 4 ? "failed" : "restarting")) return row;
    });
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

async function changedAndStop(run: ModRun, baseline: Answer): Promise<void> {
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

  step(5, "Disable, remove, then shut down the daemon; each affected mod process disappears");
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
  // The stand-in stays up for the next scratch generation, not for the caller.
}

async function handover(run: ModRun, baseline: Answer): Promise<void> {
  step(6, "Handover: an admitted streaming turn finishes on the old daemon, with the tail received once");
  await run.start();
  const probe = await run.daemon.api("/api/mods/handover", { method: "POST", body: "[]" });
  const body = await probe.text();
  if (probe.status === 404 || probe.status === 405) {
    console.log(`SKIPPED: POST /api/mods/handover returned ${probe.status}; the mod event forwarding route is not in this jar. Rerun after the handover slice lands.`);
    return;
  }
  assert.ok(probe.ok || probe.status === 400, `POST /api/mods/handover probe: ${probe.status} ${body}`);
  await run.stop();
  const oldDaemon = await run.start(true);
  const old = await run.running("recorder");
  const session = "handover-probe";
  const client = run.client(session, ["SCENARIO:gate RUN:5"]);
  const prefix = await run.recordThrough(session, es => es.some(e => e.name === "stream.event") && !complete(es));
  const retiredAt = run.bootLog().length;
  console.log(`$ kill -HUP ${oldDaemon.supervisor}  # turn already streaming on daemon ${oldDaemon.pid}`);
  run.killOwned(oldDaemon.supervisor, "SIGHUP");
  const successor = await run.health(oldDaemon.pid);
  const next = await run.running("recorder");
  assert.notEqual(next.pid, old.pid);
  const during = run.ps("after takeover, before releasing the provider's stream");
  assert.ok(!during.some(p => p.pid === old.pid), "the retired recorder survived takeover");
  assert.ok(during.some(p => p.pid === oldDaemon.pid), "the old daemon did not keep its admitted turn");
  const running = (await run.mods()).filter(row => row.state === "running").map(row => row.pid).sort();
  assert.deepEqual(during.filter(p => p.mod).map(p => p.pid).sort(), running, "more than one host per running mod");
  console.log(`  successor daemon pid=${successor.pid}; recorder pid ${old.pid} → ${next.pid}`);
  await Bun.write(run.gate, "5");
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
    run.processes().some(p => p.pid === oldDaemon.pid) ? undefined : true);
  const retired = run.bootLog().slice(retiredAt).split("\n").find(line => /0 (?:mod )?events.*not delivered/.test(line));
  assert.ok(retired, "retired daemon did not report zero undelivered events");
  console.log(`  turn ${[...turns][0]}: ${seqs.length} frame events, zero duplicate or missing sequence numbers, one completion`);
  console.log("  " + retired);
}

function latency(samples: number[]): string {
  const sorted = samples.sort((a, b) => a - b);
  const mid = (sorted.length - 1) / 2;
  const median = (sorted[Math.floor(mid)]! + sorted[Math.ceil(mid)]!) / 2;
  const p95 = sorted[Math.ceil(sorted.length * 0.95) - 1]!;
  return `median ${median.toFixed(2)} ms, p95 ${p95.toFixed(2)} ms`;
}

function ticks(pid: number): number {
  const stat = readFileSync(`/proc/${pid}/stat`, "utf8");
  const fields = stat.slice(stat.lastIndexOf(")") + 2).split(" ");
  const total = Number(fields[11]) + Number(fields[12]); // utime + stime, fields 14 and 15
  assert.ok(Number.isFinite(total), `unreadable CPU ticks for pid ${pid}`);
  return total;
}

async function timings(run: ModRun, baseline: Answer): Promise<void> {
  step(7, "Same-daemon turn time and CPU with 100 live but idle sessions");
  for (const row of await run.mods()) if (row.state !== "disabled") await run.command("mod", "disable", row.name);
  await run.noMods();
  const daemon = (await run.health()).pid;
  const prompts = Array.from({ length: sampleTurns + 3 }, () => "identical timing turn");
  for (const withMod of [false, true]) {
    if (withMod) await run.command("mod", "enable", "recorder");
    const id = withMod ? "timing-with-mod" : "timing-no-mod";
    const report = await run.client(id, prompts).report;
    answer(report, BASIC, baseline);
    const points = report.answers.slice(3);
    if (withMod) {
      const events = await run.recordThrough(id, es => complete(es, prompts.length));
      assert.ok(events.some(e => e.name === "stream.event"));
    }
    assert.equal((await run.health()).pid, daemon);
    console.log(`  ${withMod ? "all-event recorder" : "no mod"}, daemon ${daemon}, N=${points.length} (+3 warmups):`);
    for (const [name, values] of [["first byte", points.map(a => a.firstMs)], ["total", points.map(a => a.totalMs)]] as const) {
      console.log(`    ${name}: ${latency(values)}`);
    }
  }
  const mod = await run.running("recorder");
  const clients: ReturnType<ModRun["client"]>[] = [];
  try {
    console.log(`$ claude-acme --session-id idle-{0..${idleSessions - 1}}  # one turn each, then idle; launches in batches of 8`);
    for (let i = 0; i < idleSessions; i += 8) {
      const batch = Array.from({ length: Math.min(8, idleSessions - i) }, (_, j) =>
        run.client(`idle-${i + j}`, ["one turn before idling"], { interactive: true, quiet: true }));
      clients.push(...batch);
      for (const report of await Promise.all(batch.map(c => c.report))) answer(report, BASIC, baseline);
    }
    await until("all 100 idle-client completions recorded", () =>
      run.events().filter(e => e.name === "turn.complete" && e.payload.session?.startsWith("idle-")).length === idleSessions ? true : undefined);
    const idleCount = async () => (await run.sessions()).filter(row =>
      row.session_id?.startsWith("idle-") && row.availability !== "gone").length;
    assert.equal(await idleCount(), idleSessions, "GET /api/sessions idle sample size");
    const hz = Number(Bun.spawnSync(["getconf", "CLK_TCK"]).stdout.toString());
    assert.ok(hz > 0);
    console.log(`  ${idleSessions} registered sessions idle; sample /proc/{${daemon},${mod.pid}}/stat over ${idleSeconds}s (CLK_TCK=${hz}).`);
    const started = performance.now();
    const before = [ticks(daemon), ticks(mod.pid)];
    await Bun.sleep(idleSeconds * 1_000);
    const after = [ticks(daemon), ticks(mod.pid)];
    const seconds = (performance.now() - started) / 1_000;
    assert.equal(await idleCount(), idleSessions);
    for (const [i, label] of ["daemon", "recorder Bun host"].entries()) {
      const delta = after[i]! - before[i]!;
      assert.ok(delta >= 0);
      console.log(`  ${label}: utime+stime ${before[i]} → ${after[i]} ticks in ${seconds.toFixed(2)} s; ${(100 * delta / hz / seconds).toFixed(3)}% of one CPU`);
    }
  } finally {
    await Promise.allSettled(clients.map(c => c.close()));
  }
}

function files(dir: string): string[] {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const path = join(dir, entry.name);
    return entry.isDirectory() ? files(path) : entry.isFile() ? [path] : [];
  });
}

async function secrets(run: ModRun, baseline: Answer): Promise<void> {
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
  const client = run.client("secret-probe", [`synthetic body marker ${mcp}`], {
    env: { SPLICE_LABEL_AUDIT: "yes" }, headers: SECRET_HEADERS, interactive: true,
  });
  try {
    answer(await client.report, BASIC, baseline);
    await run.recordThrough("secret-probe", es => complete(es));
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
    for (const [name, value] of Object.entries(values)) {
      const hit = surfaces.find(s => s.data.includes(value));
      assert.ok(!hit, `${name}: found in ${hit?.source}`);
      console.log(`  ${name}: none found`);
    }
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
    await recordings(run, baseline);
    await misbehaving(run, baseline, burst);
    await optOut(run, baseline);
    await changedAndStop(run, baseline);
    await handover(run, baseline);
    await timings(run, baseline);
    await secrets(run, baseline);
  } catch (error) {
    console.error(error);
    console.error("Scratch daemon log tail:\n" + run.bootLog().slice(-5_000));
    code = 1;
  } finally {
    try { await run.cleanup(); }
    catch (error) { console.error(error); code = 1; }
    for (const signal of Object.keys(signals) as (keyof typeof signals)[]) process.off(signal, interrupted);
  }
  if (code === 0) console.log("PASS: mods real run.");
  return code;
}
