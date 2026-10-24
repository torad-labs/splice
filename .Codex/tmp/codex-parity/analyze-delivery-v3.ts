/** Interactive-delivery-v3; independent of frozen v2. Raw text stays in memory, reports contain counts/hashes. */
import { createReadStream, existsSync, readFileSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { createInterface } from "node:readline";
import { join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { auditNative, compareSegments, digest, gatewayEvidence, sourceEvidence, SseReader, type Row, type TimedEvent } from "./analyze-delivery-v3-evidence.ts";
export { auditNative, compareSegments, decodeSse, gatewayEvidence, sourceEvidence } from "./analyze-delivery-v3-evidence.ts";

type Context = { model: string; sessionId?: string; userTexts: string[]; knownResponseIds?: Set<string> };
const unwrap = (b: Row): Row => b.type === "response.create" && b.response ? { ...b.response, generate: b.generate ?? b.response.generate } : b;
const textOf = (content: unknown): string => typeof content === "string" ? content : Array.isArray(content) ? content.filter((p: Row) => ["text", "input_text"].includes(p.type)).map((p: Row) => p.text ?? "").join("") : "";
export function classifyRequest(raw: Row, route: string | null, context: Context) {
  const b = unwrap(raw), format = b.text?.format, properties = format?.schema?.properties;
  const titleEvidence = {
    jsonSchema: format?.type === "json_schema", structuredOutput: format?.name === "structured_output",
    onlyStringTitle: !!properties && typeof properties === "object" && Object.keys(properties).length === 1 && properties.title?.type === "string",
    namingDeveloper: Array.isArray(b.input) && b.input.some((i: Row) => i.role === "developer" && textOf(i.content).startsWith("You are naming a coding session")),
  };
  const explicitSession = b.session_id ?? b.sessionId ?? b.client_metadata?.session_id ?? b.metadata?.session_id;
  const matchingSession = !!context.sessionId && explicitSession === context.sessionId;
  const sessionConflict = typeof explicitSession === "string" && !!context.sessionId && explicitSession !== context.sessionId;
  const matchingUser = Array.isArray(b.input) && b.input.some((i: Row) => i.role === "user" && context.userTexts.includes(textOf(i.content)) && textOf(i.content).length > 0);
  const knownChain = typeof b.previous_response_id === "string" && !!context.knownResponseIds?.has(b.previous_response_id);
  const matchingModel = b.model === context.model;
  const evidence = { ...titleEvidence, matchingModel, matchingSession, matchingUser, knownChain, sessionConflict };
  let phase: "preparation" | "title" | "search" | "inventory" | "task" | "unknown" = "unknown";
  let reason = "unknown route or unattributed request";
  if (route === "/alpha/search") { phase = "search"; reason = "recognized search route"; }
  else if (route && /^\/(?:v1\/)?models$/.test(route)) { phase = "inventory"; reason = "recognized inventory route"; }
  else if (route?.endsWith("/responses")) {
    if (b.generate === false) { phase = "preparation"; reason = "explicit generate=false"; }
    else if (Object.values(titleEvidence).every(Boolean)) { phase = "title"; reason = "strict-session-title-auxiliary-v1"; }
    else if (format && format.type !== "text") reason = "unrecognized structured output";
    else if (!sessionConflict && matchingModel && (matchingSession || matchingUser || knownChain)) { phase = "task"; reason = "matched native session, exact user task or known response chain"; }
    else reason = "model/session/task chain attribution absent or conflicting";
  }
  return { phase, reason, evidence };
}

type Dispatch = { requestAt: number; itemDoneAt: number; terminalAt: number | null; callId?: string; nextTaskRequestAt?: number };
type Callback = { at: number; id: string; name: string; outerCallId?: string };
export function associateCallbacks(tasks: Dispatch[], callbacks: Callback[], unknownRequests: number[]) {
  const used = new Set<number>();
  return tasks.map((task, i) => {
    const next = task.nextTaskRequestAt ?? tasks.slice(i + 1).find(t => t.requestAt > task.requestAt)?.requestAt ?? Infinity;
    const identity = task.callId ? callbacks.findIndex((c, n) => !used.has(n) && c.outerCallId === task.callId) : -1;
    const index = identity >= 0 ? identity : callbacks.findIndex((c, n) => !used.has(n) && c.at >= task.requestAt && c.at <= next);
    const tool = index >= 0 ? callbacks[index] : undefined;
    if (index >= 0) used.add(index);
    const ambiguous = identity < 0 && unknownRequests.some(t => t > task.requestAt && t < (tool?.at ?? next));
    return { requestAt: task.requestAt, itemDoneAt: task.itemDoneAt, terminalAt: task.terminalAt,
      callbackAt: tool?.at ?? null, firstToolAfterItemMs: tool ? tool.at - task.itemDoneAt : null,
      beforeTerminal: tool && task.terminalAt !== null ? tool.at < task.terminalAt : null,
      method: identity >= 0 ? "call identity" : "temporal task window", ambiguous,
      uncertainty: identity >= 0 ? null : "No journal outer-call/client-call binding; timestamps do not prove callback identity or UI paint." };
  });
}

type Exchange = { connection: number; requestAt: number; route: string | null; transport: string | null; body: Row; events: TimedEvent[]; incomplete: boolean };
async function fileSha(path: string): Promise<string> {
  const h = createHash("sha256");
  for await (const chunk of createReadStream(path)) h.update(chunk);
  return h.digest("hex");
}
async function capture(path: string) {
  const exchanges: Exchange[] = [], current = new Map<number, Exchange>(), streams = new Map<number, SseReader>();
  const diagnostics = { malformedRows: 0, invalidRows: 0, unparsedRequests: 0, orphanEvents: 0, malformedEvents: 0, truncatedFrames: 0, invalidUtf8: 0, overlappingRequests: 0 };
  if (!existsSync(path)) return { exchanges, diagnostics, sha256: null };
  const finishStream = (connection: number) => {
    const stream = streams.get(connection); if (!stream) return;
    const end = stream.finish(); diagnostics.malformedEvents += end.malformed; diagnostics.truncatedFrames += end.truncated; diagnostics.invalidUtf8 += end.invalidUtf8;
    streams.delete(connection);
  };
  for await (const line of createInterface({ input: createReadStream(path), crlfDelay: Infinity })) {
    if (!line.trim()) continue;
    let row: Row; try { row = JSON.parse(line); } catch { diagnostics.malformedRows++; continue; }
    if (!row || !Number.isFinite(row.at_ms) || !Number.isFinite(row.connection)) { diagnostics.invalidRows++; continue; }
    if (row.kind === "request") {
      const old = current.get(row.connection);
      if (old && !old.events.some(e => ["response.completed", "response.failed", "response.incomplete", "message_stop"].includes(e.data.type))) { old.incomplete = true; diagnostics.overlappingRequests++; }
      finishStream(row.connection);
      const route = typeof row.path === "string" ? row.path.replace(/\?.*$/, "") : null;
      let body: Row;
      try { body = row.method === "GET" && !row.text ? {} : JSON.parse(row.text); if (!body || typeof body !== "object" || Array.isArray(body)) throw Error("request"); }
      catch { diagnostics.unparsedRequests++; current.delete(row.connection); continue; }
      const exchange: Exchange = { connection: row.connection, requestAt: row.at_ms, route,
        transport: row.transport ?? null, body: unwrap(body), events: [], incomplete: false };
      exchanges.push(exchange); current.set(row.connection, exchange); streams.set(row.connection, new SseReader());
    } else if (["response_frame", "response_chunk"].includes(row.kind)) {
      const exchange = current.get(row.connection); if (!exchange) { diagnostics.orphanEvents++; continue; }
      if (row.kind === "response_frame") {
        try { const data = JSON.parse(row.text); if (!data || typeof data !== "object") throw Error("event"); exchange.events.push({ at: row.at_ms, data }); }
        catch { diagnostics.malformedEvents++; }
      } else exchange.events.push(...streams.get(row.connection)!.push(typeof row.raw_base64 === "string" ? Buffer.from(row.raw_base64, "base64") : Buffer.from(row.text ?? ""), row.at_ms));
    }
  }
  for (const connection of streams.keys()) finishStream(connection);
  for (const exchange of exchanges) if (!exchange.events.some(e => ["response.completed", "response.failed", "response.incomplete", "message_stop"].includes(e.data.type))) exchange.incomplete = true;
  // Hash the exact captured bytes rather than JSON reserialization.
  return { exchanges, diagnostics, sha256: await fileSha(path) };
}

const sanitizedNative = (native: ReturnType<typeof auditNative>) => {
  const { segments, userTexts, ...counts } = native;
  return { ...counts, segments: segments.map(s => ({ kind: s.kind, chars: s.text.length, sha256: digest(s.text) })), userTaskHashes: userTexts.map(digest) };
};
export async function inspectTrial(dir: string, arm: "splice" | "codex", model: string, expectedReasoning: Row = {}) {
  const required = ["events.jsonl", "interactive-session.json", `requests/${arm === "splice" ? "upstream" : "native"}.jsonl`, ...(arm === "splice" ? ["requests/client.jsonl"] : [])];
  const missingArtifacts = required.filter(p => !existsSync(join(dir, p)));
  let meta: Row = {}, metadataMalformed = false, nativeInvalidUtf8 = false;
  if (existsSync(join(dir, "interactive-session.json"))) try { meta = JSON.parse(readFileSync(join(dir, "interactive-session.json"), "utf8")); if (!meta || typeof meta !== "object" || Array.isArray(meta)) throw Error("metadata"); } catch { metadataMalformed = true; meta = {}; }
  let text = "";
  if (existsSync(join(dir, "events.jsonl"))) {
    try { text = new TextDecoder("utf-8", { fatal: true }).decode(readFileSync(join(dir, "events.jsonl"))); } catch { nativeInvalidUtf8 = true; }
  }
  const native = auditNative(text, arm === "splice" ? "claude" : "codex", meta.completion?.sessionId ?? meta.sessionId ?? meta.session_id ?? meta.selectedSessionId);
  if (existsSync(join(dir, "events.jsonl"))) native.sourceSha256 = await fileSha(join(dir, "events.jsonl"));
  const selected = Array.isArray(meta.completion?.sourceFiles) ? meta.completion.sourceFiles : [];
  let originalSourceSha: string | null = null;
  let sourceInsideTrial = false;
  if (selected.length === 1 && typeof selected[0] === "string") {
    const original = resolve(dir, selected[0]), local = relative(resolve(dir), original);
    sourceInsideTrial = local !== ".." && !local.startsWith("../");
    if (sourceInsideTrial && existsSync(original)) originalSourceSha = await fileSha(original);
  }
  const sourceByteCopyVerified = originalSourceSha !== null && originalSourceSha === native.sourceSha256;
  const input = await capture(join(dir, `requests/${arm === "splice" ? "upstream" : "native"}.jsonl`));
  const downstream = arm === "splice" ? await capture(join(dir, "requests/client.jsonl")) : null;
  const context: Context = { model, sessionId: native.sessionIds.length === 1 ? native.sessionIds[0] : undefined, userTexts: native.userTexts, knownResponseIds: new Set() };
  const classified = input.exchanges.map(exchange => {
    const classification = classifyRequest(exchange.body, exchange.route, context);
    const attributedPreparation = classification.phase === "preparation" && classification.evidence.matchingModel && !classification.evidence.sessionConflict && (classification.evidence.matchingUser || classification.evidence.matchingSession || classification.evidence.knownChain);
    if (classification.phase === "task" || attributedPreparation) for (const e of exchange.events) if (typeof e.data.response?.id === "string") context.knownResponseIds!.add(e.data.response.id);
    return { exchange, classification };
  });
  const taskExchanges = classified.filter(x => x.classification.phase === "task").map(x => x.exchange);
  const unknownAt = classified.filter(x => x.classification.phase === "unknown").map(x => x.exchange.requestAt);
  const generated = classified.map(({ exchange: x, classification }) => {
    const terminal = x.events.find(e => ["response.completed", "response.failed", "response.incomplete"].includes(e.data.type));
    const source = sourceEvidence(x.events);
    return { requestAt: x.requestAt, route: x.route, transport: x.transport, ...classification,
      model: x.body.model ?? null, requestedReasoning: { effort: x.body.reasoning?.effort ?? null, summary: x.body.reasoning?.summary ?? null, context: x.body.reasoning?.context ?? null },
      inputReasoningItems: Array.isArray(x.body.input) ? x.body.input.filter((i: Row) => i.type === "reasoning").length : 0,
      terminalAt: terminal?.at ?? null, terminalType: terminal?.data.type ?? null, usage: terminal?.data.response?.usage ?? null,
      summaryParts: source.readableParts, summaryCharacters: source.segments.filter(s => s.kind === "reasoning").reduce((n, s) => n + s.text.length, 0),
      sourceConsistencyErrors: source.inconsistencies, unclosedReadableParts: source.unclosedReadableParts,
      unmodeledReadableEvents: source.unmodeledReadableEvents, rawReasoningCharacters: source.rawReasoningCharacters,
      sourceItems: source.items,
      incomplete: x.incomplete };
  });
  const sourceParts = taskExchanges.flatMap(x => sourceEvidence(x.events).segments);
  const wire = gatewayEvidence(downstream?.exchanges.flatMap(x => x.events) ?? []);
  const dispatch = associateCallbacks(taskExchanges.flatMap((x, index) => {
    const terminal = x.events.find(e => ["response.completed", "response.failed", "response.incomplete"].includes(e.data.type));
    return x.events.filter(e => e.data.type === "response.output_item.done" && e.data.item?.type === "custom_tool_call")
      .map(e => ({ requestAt: x.requestAt, itemDoneAt: e.at, terminalAt: terminal?.at ?? null, callId: e.data.item.call_id,
        nextTaskRequestAt: taskExchanges[index + 1]?.requestAt ?? Infinity }));
  }), wire.tools, unknownAt);
  const taskRows = generated.filter(x => x.phase === "task");
  const taskSettings = taskRows.map(x => ({ model: x.model, reasoning: x.requestedReasoning }));
  const uniqueSettings = [...new Set(taskSettings.map(x => JSON.stringify(x)))];
  const declaredSettings = Object.fromEntries(Object.entries(expectedReasoning).filter(([k, v]) => ["effort", "summary", "context"].includes(k) && v !== undefined && v !== null));
  const matchesDeclaredSettings = taskRows.every(x => Object.entries(declaredSettings).every(([k, v]) => x.requestedReasoning[k as keyof typeof x.requestedReasoning] === v));
  const sourceToNative = compareSegments(sourceParts, native.segments);
  const sourceToGateway = downstream ? compareSegments(sourceParts, wire.segments) : null;
  const diagnosticTotal = Object.values(input.diagnostics).reduce((a, b) => a + b, 0) + (downstream ? Object.values(downstream.diagnostics).reduce((a, b) => a + b, 0) : 0);
  const phaseCounts = Object.fromEntries(["task", "title", "search", "inventory", "preparation", "unknown"].map(p => [p, generated.filter(x => x.phase === p).length]));
  const exitCode = typeof meta.exit === "number" ? meta.exit : null;
  const checks = {
    artifactsPresent: !missingArtifacts.length, metadataParsed: !metadataMalformed, nativeUtf8Valid: !nativeInvalidUtf8,
    interactiveModeDeclared: meta.invocation_mode === "interactive-pty-v1", nativeComplete: native.completed,
    nativeSourceCopyVerified: sourceByteCopyVerified,
    supervisorCompleted: meta.completed === true && meta.timedOut === false,
    realClientExitedSuccessfully: exitCode === 0,
    noUnknownRequests: unknownAt.length === 0, taskAttributed: taskExchanges.length > 0,
    capturesWellFormed: diagnosticTotal === 0, sourceSnapshotsConsistent: taskRows.every(x => x.sourceConsistencyErrors === 0),
    sourceReadableCoverageComplete: taskRows.every(x => x.unclosedReadableParts === 0 && x.unmodeledReadableEvents === 0),
    taskResponsesComplete: taskRows.every(x => !x.incomplete && x.terminalType === "response.completed"),
    taskSettingsConsistent: uniqueSettings.length === 1,
    taskSettingsMatchExplicitDeclaration: matchesDeclaredSettings,
    noOpaqueNative: native.opaqueBlocks === 0, noEmptyNativeThinking: native.emptyThinkingBlocks === 0,
    sourceMatchesNative: sourceToNative.equal,
    gatewayComplete: !downstream || (wire.messages > 0 && wire.messages === wire.completed && wire.openBlocks === 0 && wire.malformed === 0),
    noOpaqueGateway: !downstream || wire.opaqueBlocks === 0, noDuplicateGatewayBlocks: !downstream || wire.duplicateBlocks === 0,
    noEmptyGatewayThinking: !downstream || wire.emptyThinkingBlocks === 0,
    sourceMatchesGateway: !sourceToGateway || sourceToGateway.equal,
    callbackAssociationsUnambiguous: dispatch.every(d => !d.ambiguous),
  };
  return { formatVersion: 3, qualified: Object.values(checks).every(Boolean), checks, missingArtifacts,
    qualificationScope: "Artifact completeness, explicit attribution, exact readable text/order and clean native/client completion; not dispatch latency, UI paint or task correctness.",
    invocationMode: meta.invocation_mode ?? null, exitCode, supervisorCompleted: meta.completed ?? null, supervisorTimedOut: meta.timedOut ?? null,
    arm, native: sanitizedNative(native),
    nativeSource: { selectedFileCount: selected.length, sourceInsideTrial, originalSourceSha, sourceByteCopyVerified },
    captureHashes: { model: input.sha256, gateway: downstream?.sha256 ?? null, events: existsSync(join(dir, "events.jsonl")) ? digest(readFileSync(join(dir, "events.jsonl"))) : null },
    captureDiagnostics: { model: input.diagnostics, gateway: downstream?.diagnostics ?? null },
    totals: { allRequests: input.exchanges.length + input.diagnostics.unparsedRequests, unknownUnparsedRequests: input.diagnostics.unparsedRequests,
      allResponsesGenerations: classified.filter(x => x.exchange.route?.endsWith("/responses") && x.exchange.body.generate !== false).length, phases: phaseCounts,
      taskSummaryDenominator: taskRows.length, taskWithReadableSummary: taskRows.filter(x => x.summaryCharacters > 0).length },
    generated, taskSettings, settingsAgreement: { internallyConsistent: uniqueSettings.length === 1, declaredSettings, matchesDeclaredSettings, absentDeclarationIsNotAgreementEvidence: Object.keys(declaredSettings).length === 0 },
    gateway: { opaqueBlocks: wire.opaqueBlocks, emptyThinkingBlocks: wire.emptyThinkingBlocks, duplicateBlocks: wire.duplicateBlocks, messages: wire.messages, completeMessages: wire.completed },
    sourceToNative, sourceToGateway, dispatch };
}

export async function main(argv: string[]): Promise<number> {
  if (!argv[0] || !argv[1]) { console.error("Usage: bun analyze-delivery-v3.ts RUN_DIR NEW_OUTPUT.json"); return 2; }
  const run = resolve(argv[0]), out = resolve(argv[1]);
  if (existsSync(out)) throw new Error("exclusive output already exists");
  const manifest = JSON.parse(readFileSync(join(run, "manifest.json"), "utf8"));
  const results: Row[] = JSON.parse(readFileSync(join(run, "results.json"), "utf8"));
  const trials: Row[] = [];
  for (const result of results) {
    if (!["codex", "splice"].includes(result.arm)) { trials.push({ task: result.task, arm: result.arm, qualified: false, error: "unknown arm; modes not pooled" }); continue; }
    trials.push({ task: result.task, repetition: result.repetition, verified: result.verified,
      ...await inspectTrial(join(run, result.task, `${result.repetition}-${result.arm}`), result.arm, manifest.config.model, {
        effort: manifest.config.reasoning?.effort ?? manifest.config.effort,
        summary: manifest.config.reasoning?.summary ?? manifest.config.summary,
        context: manifest.config.reasoning?.context ?? manifest.config.context,
      }) });
  }
  const report = { format_version: 3, classifier: "interactive-delivery-v3 / strict-session-title-auxiliary-v1",
    analyzer_sha256: digest(readFileSync(fileURLToPath(import.meta.url))), classifier_sha256: digest(readFileSync(fileURLToPath(import.meta.url))),
    evidence_sha256: digest(readFileSync(join(import.meta.dir, "analyze-delivery-v3-evidence.ts"))),
    manifest_sha256: digest(readFileSync(join(run, "manifest.json"))), results_sha256: digest(readFileSync(join(run, "results.json"))),
    jar_sha256: manifest.jar_sha256 ?? null, replay_reasoning: manifest.replay_reasoning ?? null,
    boundary_transformations: ["LF+LF between adjacent readable reasoning parts", "adjacent tool callbacks form one boundary"],
    limits: "Native session persistence and gateway wire are separate evidence, never fabricated stream_event rows. Callback association is temporal absent an explicit journal call binding; known titles do not end task windows, unknown interleavings remain ambiguous. Times are capture receipt, not UI paint. Unknowns block qualification. No model inference or verifier changes.",
    trials };
  writeFileSync(out, JSON.stringify(report, null, 2) + "\n", { flag: "wx", mode: 0o600 });
  console.log(JSON.stringify({ out, trials: trials.length, qualified: trials.filter(t => t.qualified).length }));
  return trials.every(t => t.qualified) ? 0 : 1;
}
if (import.meta.main) process.exitCode = await main(process.argv.slice(2));
