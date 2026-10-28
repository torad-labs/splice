import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { createReadStream, openSync, closeSync, writeSync, readFileSync, readlinkSync, readdirSync } from "node:fs";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { join, basename, resolve } from "node:path";
import xterm from "@xterm/headless";
import { nativeCompletion } from "../current-cli/native-events";
import { isTitleRequest } from "../current-cli/scripted-upstream";

const configuration = await Bun.file(process.argv[2]!).json();
const cli: string = configuration.cli.path, host: string = configuration.host.path;
const CLI_SHA: string = configuration.cli.sha256, HOST_SHA: string = configuration.host.sha256;
const nonce: string = configuration.nonce, directory: string = resolve(configuration.directory);
assert.match(nonce, /^[a-f0-9]{32}$/);
await mkdir(directory, { recursive: false, mode: 0o700 });
const home = join(directory, "client-state"), workspace = join(directory, "workspace"), profiles = join(directory, "source-profile");
for (const path of [home, workspace, profiles]) await mkdir(path, { mode: 0o700 });
const started = Date.now(), deadline = started + 150000;
async function identity(path: string) {
  const digest = createHash("sha256"); let bytes = 0;
  for await (const chunk of createReadStream(path)) { bytes += (chunk as Buffer).length; digest.update(chunk as Buffer); }
  return { path, bytes, sha256: digest.digest("hex") };
}
const before = { cli: await identity(cli), host: await identity(host) };
assert.equal(before.cli.sha256, CLI_SHA); assert.equal(before.host.sha256, HOST_SHA);
const prototype = await identity(import.meta.path); await writeFile(join(directory, "prototype-source.json"), JSON.stringify(prototype, null, 2));
const cgroup = readFileSync("/proc/self/cgroup", "utf8").trim().split("\n").find(value => value.startsWith("0::"))!.slice(3);
const limits = { cgroup, memoryMax: readFileSync(`/sys/fs/cgroup${cgroup}/memory.max`, "utf8").trim(), swapMax: readFileSync(`/sys/fs/cgroup${cgroup}/memory.swap.max`, "utf8").trim() };
assert.equal(limits.memoryMax, "4294967296"); assert.equal(limits.swapMax, "0");
await writeFile(join(directory, "limits.json"), JSON.stringify(limits, null, 2) + "\n");

const control = openSync(join(directory, "control-events.jsonl"), "w", 0o600);
function event(kind: string, value: unknown = {}) { writeSync(control, JSON.stringify({ kind, at: Date.now(), value }) + "\n"); }
const rawMi = openSync(join(directory, "gdb-mi.raw"), "w", 0o600), rawPty = openSync(join(directory, "terminal.raw"), "w", 0o600);
let miBytes = 0, ptyBytes = 0;
const fds = new Set(readdirSync("/proc/self/fd"));
const emulator = new xterm.Terminal({ cols: 120, rows: 40, scrollback: 10000, allowProposedApi: true });
const terminal = new Bun.Terminal({ cols: 120, rows: 40, name: "xterm-256color", data(_terminal, bytes) {
  ptyBytes += bytes.length; if (ptyBytes > 16 * 1024 * 1024) throw Error("PTY capture exceeded16MiB");
  writeSync(rawPty, bytes); event("pty_output", { raw_base64: Buffer.from(bytes).toString("base64") }); emulator.write(bytes);
} });
emulator.onData(value => { event("terminal_query_reply", { raw_base64: Buffer.from(value).toString("base64") }); terminal.write(value); });
const ttyIndexes = readdirSync("/proc/self/fd").filter(fd => !fds.has(fd)).flatMap(fd => {
  try { const match = /^tty-index:\s*(\d+)$/m.exec(readFileSync(`/proc/self/fdinfo/${fd}`, "utf8")); return match ? [match[1]!] : []; } catch { return []; }
});
assert.equal(new Set(ttyIndexes).size, 1); const tty = `/dev/pts/${ttyIndexes[0]}`;
event("owned_pty", { tty });

const NOTICE = `PRE_READY_NOTICE_MUST_NOT_DELIVER_${nonce}`, FORBIDDEN = `MUST_NOT_RUN_AFTER_PRE_READY_CANCEL_${nonce}`;
const resumePrompt = "Continue the fixed local probe after cancellation before dispatch readiness.";
const scripts = [
  'store("stable-store","completed-before-pre-ready-cancellation"); text("baseline-stored");',
  `// @exec: {"yield_time_ms":30000}\nstore("interrupted-store","uncommitted-before-pre-ready-cancellation"); notify(${JSON.stringify(NOTICE)}); await new Promise(r=>setTimeout(r,30000)); text(${JSON.stringify(FORBIDDEN)});`,
  'text({tag:"after-pre-ready-cancellation",stable:load("stable-store"),interrupted:load("interrupted-store")});',
];
const finalText = `CODEMODE_DISPATCH_GATE_${nonce}`, requests: unknown[] = [], frames: unknown[] = [];
let taskRequests = 0, titles = 0;
const server = Bun.serve({ hostname: "127.0.0.1", port: 0, async fetch(request) {
  if (request.method !== "POST" || new URL(request.url).pathname !== "/v1/responses") return new Response("unexpected route", { status: 404 });
  const body = await request.json() as Record<string, unknown>, title = isTitleRequest(body as never);
  const number = title ? ++titles : ++taskRequests; requests.push({ at: Date.now(), title, number, body });
  assert.ok(taskRequests <= 4 && titles <= 4, "unexpected model request count");
  const id = `${title ? "title" : "response"}-${number}`, items: Record<string, unknown>[] = [];
  if (!title && number <= scripts.length) items.push({ type: "custom_tool_call", id: `item-probe-pre-ready-${number - 1}`, call_id: `probe-pre-ready-${number - 1}`, name: "exec", input: scripts[number - 1] });
  else items.push({ type: "message", id: `item-final-${number}`, role: "assistant", status: "completed", channel: "final", content: [{ type: "output_text", text: title ? JSON.stringify({ title: "Dispatch gate fixture" }) : finalText, annotations: [] }] });
  const out: string[] = [], add = (type: string, value: unknown) => { const frame = { type, ...value as object }; frames.push({ at: Date.now(), frame }); out.push(`event: ${type}\ndata: ${JSON.stringify(frame)}\n\n`); };
  add("response.created", { response: { id, status: "in_progress", output: [] } });
  for (const [index, item] of items.entries()) {
    if (item.type === "custom_tool_call") {
      add("response.output_item.added", { output_index: index, item: { ...item, input: "" } });
      add("response.custom_tool_call_input.delta", { item_id: item.id, output_index: index, delta: item.input });
      add("response.custom_tool_call_input.done", { item_id: item.id, output_index: index, input: item.input });
    } else {
      const text = (item.content as { text: string }[])[0]!.text;
      add("response.output_item.added", { output_index: index, item: { ...item, content: [] } });
      add("response.content_part.added", { item_id: item.id, output_index: index, content_index: 0, part: { type: "output_text", text: "", annotations: [] } });
      add("response.output_text.delta", { item_id: item.id, output_index: index, content_index: 0, delta: text });
      add("response.output_text.done", { item_id: item.id, output_index: index, content_index: 0, text });
    }
    add("response.output_item.done", { output_index: index, item });
  }
  add("response.completed", { response: { id, status: "completed", output: items, usage: { input_tokens: 1, output_tokens: 1, total_tokens: 2 } } });
  await writeFile(join(directory, "requests.json"), JSON.stringify(requests, null, 2));
  await writeFile(join(directory, "frames.json"), JSON.stringify(frames, null, 2));
  event("loopback_response", { title, number });
  return new Response(out.join(""), { headers: { "content-type": "text/event-stream", "x-request-id": id } });
} });
const catalog = await readFile(configuration.catalog.path); assert.equal(createHash("sha256").update(catalog).digest("hex"), configuration.catalog.sha256);
await writeFile(join(directory, "catalog.json"), catalog);
await writeFile(join(home, "config.toml"), [
  'model = "gpt-6.1-sol"', 'model_provider = "dispatch_gate_fixture"', `model_catalog_json = ${JSON.stringify(join(directory, "catalog.json"))}`,
  'check_for_update_on_startup = false', 'approval_policy = "never"', 'sandbox_mode = "danger-full-access"', '[features]',
  'code_mode = true', 'code_mode_host = true', 'plugins = false', 'remote_plugin = false', 'plugin_sharing = false',
  '[model_providers.dispatch_gate_fixture]', 'name = "Deterministic dispatch gate fixture"', `base_url = "http://127.0.0.1:${server.port}/v1"`,
  'wire_api = "responses"', 'requires_openai_auth = false', 'supports_websockets = false', 'request_max_retries = 0', 'stream_max_retries = 0',
  `[projects.${JSON.stringify(workspace)}]`, 'trust_level = "trusted"', '',
].join("\n"));
const environment: Record<string, string | undefined> = { ...process.env, CODEX_HOME: home, TERM: "xterm-256color", TOKIO_WORKER_THREADS: "4", LLVM_PROFILE_FILE: join(profiles, "%p.profraw") };
for (const name of ["OPENAI_API_KEY", "CODEX_API_KEY", "CODEX_INTERNAL_API_KEY", "OPENAI_BASE_URL", "CHATGPT_BASE_URL"]) delete environment[name];
const gdb = Bun.spawn([configuration.gdb.path, "-nx", "--interpreter=mi2", "--quiet"], { cwd: workspace, env: environment, stdin: "pipe", stdout: "pipe", stderr: Bun.file(join(directory, "gdb-stderr.txt")) });
const debuggerIdentity = await identity(`/proc/${gdb.pid}/exe`); assert.equal(debuggerIdentity.sha256, configuration.gdb.sha256);
event("owned_gdb", { pid: gdb.pid, identity: debuggerIdentity, stat: readFileSync(`/proc/${gdb.pid}/stat`, "utf8") });
let token = 0, inferior: number | undefined, inferiorStartTicks: string | undefined, ended: string | undefined, streamError: unknown;
const pending = new Map<number, { resolve(value: string): void; reject(error: Error): void }>(), stops: string[] = [];
const value = (line: string, key: string) => { const match = new RegExp(`(?:^|,)${key}="((?:\\\\.|[^"\\\\])*)"`).exec(line); return match ? JSON.parse(`"${match[1]}"`) as string : undefined; };
const reader = (async () => {
  let buffered = "";
  for await (const chunk of gdb.stdout) {
    const bytes = Buffer.from(chunk); miBytes += bytes.length; if (miBytes > 16 * 1024 * 1024) throw Error("MI capture exceeded16MiB");
    writeSync(rawMi, bytes); buffered += bytes.toString(); let newline;
    while ((newline = buffered.indexOf("\n")) >= 0) {
      const line = buffered.slice(0, newline).replace(/\r$/, ""); buffered = buffered.slice(newline + 1);
      const result = /^(\d+)\^(done|running|error|exit)(.*)$/.exec(line);
      if (result) { const handler = pending.get(Number(result[1])); pending.delete(Number(result[1])); if (result[2] === "error") handler?.reject(Error(line)); else handler?.resolve(line); }
      if (line.startsWith("=thread-group-started")) { inferior = Number(value(line, "pid")); event("mi_inferior_started", { inferior, line }); }
      if (line.startsWith("*stopped")) { stops.push(line); event("mi_stop", { line }); }
      if (line.startsWith("=thread-group-exited")) { ended = value(line, "exit-code") ?? "unknown"; event("mi_inferior_exited", { line }); }
    }
  }
})().catch(error => { streamError = error; });
async function command(input: string): Promise<string> {
  if (streamError) throw streamError;
  const current = ++token; event("mi_command", { token: current, input });
  const response = new Promise<string>((resolve, reject) => pending.set(current, { resolve, reject }));
  gdb.stdin.write(`${current}${input}\n`); await gdb.stdin.flush();
  return await Promise.race([response, Bun.sleep(20000).then(() => { throw Error(`MI command timeout: ${input}`); })]);
}
async function until<T>(read: () => T | undefined, label: string): Promise<T> {
  while (Date.now() < deadline) { if (streamError) throw streamError; const found = read(); if (found !== undefined) return found; if (gdb.exitCode !== null) throw Error(`GDB exited before ${label}`); await Bun.sleep(25); }
  throw Error(`deadline before ${label}`);
}
async function stopFor(id: string) { return until(() => { const at = stops.findIndex(line => value(line, "bkptno") === id); return at < 0 ? undefined : stops.splice(at, 1)[0]; }, `breakpoint${id}`); }
async function address(symbol: string) {
  const line = await command(`-data-evaluate-expression ${JSON.stringify(`&'${symbol}'`)}`), found = value(line.slice(line.indexOf(",") + 1), "value");
  assert.ok(found && /^0x[0-9a-f]+/.test(found), line); return BigInt(/^0x[0-9a-f]+/.exec(found)![0]);
}
function breakpoint(line: string) { const match = /bkpt=\{number="(\d+)"/.exec(line); assert.ok(match, line); return match[1]!; }
function descendants(pid: number) {
  const queue = [pid], found = new Set<number>();
  while (queue.length && found.size < 128) { const current = queue.pop()!; try { for (const task of readdirSync(`/proc/${current}/task`)) {
    let children; try { children = readFileSync(`/proc/${current}/task/${task}/children`, "utf8"); } catch { continue; }
    for (const raw of children.trim().split(/\s+/)) { const next = Number(raw); if (next > 0 && !found.has(next)) { found.add(next); queue.push(next); } }
  } } catch {} } return [...found];
}
function interruption() {
  const directory = join(home, "sessions");
  let files: string[];
  try { files = readdirSync(directory, { recursive: true, withFileTypes: true }).filter(entry => entry.isFile() && entry.name.endsWith(".jsonl") && !entry.parentPath.split("/").includes("subagents")).map(entry => join(entry.parentPath, entry.name)); } catch { return; }
  if (files.length !== 1) return;
  const sourceFile = files[0]!, bytes = readFileSync(sourceFile); assert.ok(bytes.length <= 16 * 1024 * 1024);
  const text = bytes.toString(); if (!text.endsWith("\n")) return;
  const rows = text.trimEnd().split("\n").map(line => JSON.parse(line));
  const session = rows.find(row => row.type === "session_meta" && typeof row.payload?.id === "string");
  const called = rows.findIndex(row => row.type === "response_item" && row.payload?.type === "custom_tool_call" && row.payload.name === "exec" && row.payload.call_id === "probe-pre-ready-1");
  if (!session || called < 0) return;
  const notified = rows.some((row, index) => index > called && row.type === "response_item" && row.payload?.type === "custom_tool_call_output" && row.payload.call_id === "probe-pre-ready-1" && row.payload.output === NOTICE);
  const aborted = rows.some((row, index) => index > called && row.type === "event_msg" && row.payload?.type === "turn_aborted");
  return { sourceFile, sessionId: session.payload.id, called, notified, aborted };
}
const argv = [cli, "--no-daemon", "--no-alt-screen", "-C", workspace, "--", "Run the fixed local dispatch-gate probe in this disposable workspace."];
const recipe = { schema: 1, inputSha256: configuration.inputSha256, control: configuration.control, before, nonce, sourceRevision: "360319d8dee62d87e2d59fc0c21805d79519cfed", argv, environment: { CODEX_HOME: home, TOKIO_WORKER_THREADS: "4", LLVM_PROFILE_FILE: environment.LLVM_PROFILE_FILE },
  modelBackend: "deterministic-loopback-no-inference", scripts, notice: NOTICE, forbidden: FORBIDDEN, resumePrompt, startup: "owned env same-PID exec wrapper with ptrace exec catchpoint", applicationBreakpoints: "hardware execution only; no source/value/packet mutation or skipped calls" };
await writeFile(join(directory, "recipe.json"), JSON.stringify(recipe, null, 2));
let receipt: Record<string, unknown> = { schema: 1, qualified: false, scope: "fixture-only source scheduling prototype; no candidate or behavior coverage credit" };
try {
  for (const input of ["-gdb-set pagination off", "-gdb-set confirm off", "-gdb-set auto-load off", "-gdb-set debuginfod enabled off", "-gdb-set non-stop on", "-gdb-set mi-async on", "-gdb-set disable-randomization off", "-gdb-set auto-solib-add off", "-gdb-set detach-on-fork on"]) await command(input);
  await command(`-inferior-tty-set ${JSON.stringify(tty)}`);
  await command('-file-exec-and-symbols "/usr/bin/env"');
  await command(`-exec-arguments ${argv.map(item => JSON.stringify(item)).join(" ")}`);
  await command('-interpreter-exec console "catch exec"');
  await command("-exec-run");
  const execStop = await until(() => { const at = stops.findIndex(line => value(line, "reason") === "exec"); return at < 0 ? undefined : stops.splice(at, 1)[0]; }, "actual CLI exec stop");
  assert.ok(inferior); assert.equal((await identity(`/proc/${inferior}/exe`)).sha256, CLI_SHA);
  const stat = readFileSync(`/proc/${inferior}/stat`, "utf8"), startTicks = stat.slice(stat.lastIndexOf(")") + 2).split(" ")[19]; assert.match(startTicks!, /^\d+$/);
  inferiorStartTicks = startTicks;
  event("attested_inferior", { pid: inferior, startTicks, sha256: CLI_SHA, execStop });
  await command("-break-disable 1"); await command("-gdb-set language c");
  const ready = await address("_RNvMNtNtNtCsak7ms9wL4Bg_10codex_core5tools9code_mode8delegateNtB2_22CodeModeDispatchBroker28mark_cell_ready_for_dispatch");
  const wait = await address("_RNCNCNvNtNtNtCsak7ms9wL4Bg_10codex_core5tools9code_mode8delegate34wait_until_cell_ready_for_dispatch00Bb_");
  const remove = await address("_RNvNtNtNtCsak7ms9wL4Bg_10codex_core5tools9code_mode8delegate20remove_dispatch_gate");
  const readyId = breakpoint(await command(`-break-insert -h -i 1 *0x${ready.toString(16)}`));
  const waitId = breakpoint(await command(`-break-insert -h *0x${wait.toString(16)}`));
  const removeId = breakpoint(await command(`-break-insert -h *0x${remove.toString(16)}`));
  event("gate_breakpoints", { ready: ready.toString(16), wait: wait.toString(16), remove: remove.toString(16), readyId, waitId, removeId });
  await command('-interpreter-exec console "maintenance info breakpoints"'); await command("-exec-continue --all");
  const readyStop = await stopFor(readyId), readyThread = value(readyStop, "thread-id"); assert.ok(readyThread);
  await command(`-break-disable ${readyId}`);
  const waitStop = await stopFor(waitId), waitThread = value(waitStop, "thread-id"); assert.ok(waitThread); assert.notEqual(waitThread, readyThread);
  await command(`-break-disable ${waitId}`);
  const threadNames = readdirSync(`/proc/${inferior}/task`).map(id => ({ id, name: readFileSync(`/proc/${inferior}/task/${id}/comm`, "utf8").trim() }));
  event("gate_wait_false_observed", { readyStop, waitStop, threadNames });
  const hosts = [];
  for (const pid of descendants(inferior)) { try { if (!basename(readlinkSync(`/proc/${pid}/exe`)).startsWith("codex-code-mode-host")) continue; const value = await identity(`/proc/${pid}/exe`); assert.equal(value.sha256, HOST_SHA); hosts.push({ pid, ...value }); } catch(error) { if (error instanceof assert.AssertionError) throw error; } }
  assert.ok(hosts.length, "the actual native host child must be attested");
  await command(`-exec-continue --thread ${waitThread}`);
  assert.ok((await command(`-thread-info ${readyThread}`)).includes('state="stopped"'));
  event("pty_interrupt_before_ready", { raw_base64: Buffer.from("\x03").toString("base64"), readyThread }); terminal.write("\x03");
  const removeStop = await stopFor(removeId); assert.ok((await command(`-thread-info ${readyThread}`)).includes('state="stopped"'));
  event("removed_before_ready_released", { removeStop, readyThread });
  await command(`-break-disable ${removeId}`);
  const removeThread = value(removeStop, "thread-id"); assert.ok(removeThread);
  await command(`-exec-continue --thread ${removeThread}`); await command(`-exec-continue --thread ${readyThread}`);
  const interrupted = await until(() => { const value = interruption(); return value?.aborted ? value : undefined; }, "actual native aborted turn");
  assert.equal(interrupted.notified, false, "pre-ready notification must not have been delivered");
  event("pty_resume_after_abort", { raw_base64: Buffer.from(resumePrompt).toString("base64") }); terminal.write(resumePrompt); await Bun.sleep(250);
  event("pty_submit_resume", { raw_base64: Buffer.from("\r").toString("base64") }); terminal.write("\r");
  const completion = await until(() => nativeCompletion(home) ?? undefined, "authentic resumed completion"); assert.equal(completion.finalText, finalText);
  assert.equal(taskRequests, 4); assert.equal(interruption()?.notified, false);
  const last = requests.filter(value => !(value as { title: boolean }).title).at(-1) as { body: { input: Record<string, unknown>[] } };
  const results = last.body.input.filter(item => item.type === "custom_tool_call_output" && item.call_id === "probe-pre-ready-2"); assert.equal(results.length, 1);
  const output = results[0]!.output as { type: string; text: string }[];
  assert.ok(Array.isArray(output)); assert.equal(output.length, 2);
  assert.deepEqual(output[1], { type: "input_text", text: '{"tag":"after-pre-ready-cancellation","stable":"completed-before-pre-ready-cancellation"}' });
  assert.ok(!last.body.input.some(item => item.type === "custom_tool_call_output" && JSON.stringify(item.output).includes(FORBIDDEN)));
  await writeFile(join(directory, "native-events.jsonl"), await readFile(completion.sourceFile));
  await command('-interpreter-exec console "maintenance info breakpoints"');
  event("pty_exit_after_completion", { raw_base64: Buffer.from("\x04").toString("base64") }); terminal.write("\x04");
  await Bun.sleep(300);
  const screen = () => { const buffer = emulator.buffer.active; return Array.from({ length: buffer.length }, (_, i) => buffer.getLine(i)?.translateToString(true) ?? "").join("\n"); };
  if (screen().includes("Press Ctrl-D again to exit")) { event("pty_confirm_exit", { raw_base64: Buffer.from("\x04").toString("base64") }); terminal.write("\x04"); }
  await until(() => ended, "inferior exit"); assert.equal(ended, "0");
  await command("-gdb-exit"); assert.equal(await gdb.exited, 0); await reader;
  const after = { cli: await identity(cli), host: await identity(host) }; assert.deepEqual(after, before);
  const profileNames = readdirSync(profiles); assert.deepEqual(profileNames, [`${inferior}.profraw`]);
  receipt = { ...receipt, prototypePassed: true, pid: inferior, startTicks, hosts, before, after, profile: await identity(join(profiles, profileNames[0]!)), completion, interrupted, taskRequests,
    readyStop, waitStop, removeStop, note: "Application breakpoints are hardware and startup is ptraceexec; GDB's internal shlib-event loader breakpoint is disclosed in raw MI. No source/value/returned-packet mutation or skipped calls. Native frame capture is the actual owned CLI rollout event stream." };
} catch (error) {
  receipt = { ...receipt, prototypePassed: false, error: error instanceof Error ? error.stack : String(error), pid: inferior, taskRequests, stopped: stops };
  throw error;
} finally {
  server.stop(true);
  const owned = new Map<number, string>();
  for (const pid of [...descendants(gdb.pid), ...(inferior ? [inferior, ...descendants(inferior)] : [])]) {
    try { const stat = readFileSync(`/proc/${pid}/stat`, "utf8"); owned.set(pid, stat.slice(stat.lastIndexOf(")") + 2).split(" ")[19]!); } catch {}
  }
  if (gdb.exitCode === null) { try { gdb.stdin.write(`${++token}-gdb-exit\n`); await gdb.stdin.flush(); } catch {} await Promise.race([gdb.exited, Bun.sleep(1000)]); if (gdb.exitCode === null) { gdb.kill("SIGKILL"); await gdb.exited; } }
  for (const [pid, ticks] of [...owned].reverse()) {
    try { const stat = readFileSync(`/proc/${pid}/stat`, "utf8"), current = stat.slice(stat.lastIndexOf(")") + 2).split(" ")[19]; if (current === ticks && (pid !== inferior || !inferiorStartTicks || current === inferiorStartTicks)) process.kill(pid, "SIGKILL"); } catch {}
  }
  await reader;
  const observed = interruption(); if (observed) await writeFile(join(directory, "native-events.jsonl"), await readFile(observed.sourceFile));
  receipt.resourceLimits = { ...limits, memoryPeak: readFileSync(`/sys/fs/cgroup${cgroup}/memory.peak`, "utf8").trim() };
  const buffer = emulator.buffer.active; await writeFile(join(directory, "terminal-screen.txt"), Array.from({ length: buffer.length }, (_, i) => buffer.getLine(i)?.translateToString(true) ?? "").join("\n"));
  await writeFile(join(directory, "receipt.json"), JSON.stringify(receipt, null, 2) + "\n");
  terminal.close(); emulator.dispose(); closeSync(rawMi); closeSync(rawPty); closeSync(control);
  console.log(JSON.stringify({ directory, ...receipt }, null, 2));
}
