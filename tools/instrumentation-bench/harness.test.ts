import { expect, test } from 'bun:test';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { defaults, validate } from './manifest';
import { frames, nonstream, requestBytes, startUpstream } from './fixture';
import { requestBodyMatcher } from './request-body';
import { initial, WireObserver } from './observe';
import { run } from './run';
import { readRun, analyze } from './analyze';
import { compareWireFingerprints } from './wire-fingerprint';
import { startDesk } from './desk';

test('explicit Java selection requires an absolute executable and full digest', () => {
  const m = defaults(); m.java = { executable: '/task/jdk/bin/java', sha256: 'a'.repeat(64) };
  expect(validate(m).java).toEqual(m.java);
  m.java.executable = 'java'; expect(() => validate(m)).toThrow('invalid java selection');
  m.java.executable = '/task/jdk/bin/java'; m.java.sha256 = 'short';
  expect(() => validate(m)).toThrow('invalid java selection');
});

test('a wrong Java digest refuses the desk before any executable or artifact launch', async () => {
  const root = await mkdtemp('/tmp/splice-instrumentation-java-');
  try {
    const executable = join(root, 'java'); await writeFile(executable, 'not a JVM\n', { mode: 0o700 });
    const m = defaults(); m.role = 'candidate'; m.target = { kind: 'proxy', artifact_path: join(root, 'absent.jar') };
    await expect(startDesk(m, 'http://127.0.0.1:18000', join(root, 'desk'), null, { executable, sha256: '0'.repeat(64) })).rejects.toThrow('java_sha256_mismatch');
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('honest terminal validation rejects dropped content despite complete terminal usage', () => {
  const m = defaults(); let tick = 0;
  for (const drop of [false, true]) {
    const observer = new WireObserver(m, () => ++tick);
    const fs = frames(m).filter((_f, i) => !drop || i !== 2);
    for (const f of fs) observer.chunk(new TextEncoder().encode(`data: ${JSON.stringify(f)}\n\n`));
    const sample = initial(0, 0, 'steady', 0); observer.finish(sample);
    expect(sample.terminal_correct).toBe(!drop);
  }
});

test('proxy Anthropic wire fingerprints normalize only the generated message ID', () => {
  const inspect = (role: 'baseline' | 'candidate', id: string, change?: (events: any[]) => void) => {
    const m = defaults(); m.role = role; m.target = { kind: 'proxy' }; m.transport = 'sse';
    const events: any[] = structuredClone(frames(m)); events[0].message.id = id; change?.(events);
    const observer = new WireObserver(m, () => 1);
    for (const event of events) observer.chunk(new TextEncoder().encode('event: ' + event.type + '\r\ndata: ' + JSON.stringify(event) + '\r\n\r\n'));
    const sample = initial(0, 0, 'steady', 0); observer.finish(sample);
    return { manifest: m, sample };
  };
  const baseline = inspect('baseline', 'msg_1794079955675_1');
  const candidate = inspect('candidate', 'msg_1794080363569_1');
  expect(baseline.sample.terminal_correct).toBe(true);
  expect(candidate.sample.terminal_correct).toBe(true);
  expect(baseline.sample.wire_sha256).not.toBe(candidate.sample.wire_sha256);
  expect(baseline.sample.wire_fingerprint_scheme).toBe('anthropic-sse-message-id-v1');
  expect(baseline.sample.wire_fingerprint_valid).toBe(true);
  expect(baseline.sample.wire_fingerprint_sha256).toBe(candidate.sample.wire_fingerprint_sha256);
  expect(compareWireFingerprints(baseline.manifest, [baseline.sample], candidate.manifest, [candidate.sample])).toBe('equal');

  const changedPayload = inspect('candidate', 'msg_1794080363569_2', events => { events[0].message.model = 'different-model'; });
  expect(changedPayload.sample.wire_fingerprint_sha256).not.toBe(baseline.sample.wire_fingerprint_sha256);
  expect(compareWireFingerprints(baseline.manifest, [baseline.sample], changedPayload.manifest, [changedPayload.sample])).toBe('changed');
  const changedOrder = inspect('candidate', 'msg_1794080363569_3', events => { [events[1], events[2]] = [events[2], events[1]]; });
  expect(changedOrder.sample.wire_fingerprint_sha256).not.toBe(baseline.sample.wire_fingerprint_sha256);
  expect(compareWireFingerprints(baseline.manifest, [baseline.sample], changedOrder.manifest, [changedOrder.sample])).toBe('changed');
});

test('fingerprint evidence rejects invalid or reused IDs and old samples; direct fixture bytes stay exact', () => {
  const inspect = (target: 'proxy' | 'direct', id: string) => {
    const m = defaults(); m.role = 'candidate'; m.target = { kind: target }; m.transport = 'sse';
    const events: any[] = structuredClone(frames(m)); events[0].message.id = id;
    const observer = new WireObserver(m, () => 1);
    for (const event of events) observer.chunk(new TextEncoder().encode(['event: ' + event.type, 'data: ' + JSON.stringify(event), '', ''].join(String.fromCharCode(10))));
    const sample = initial(0, 0, 'steady', 0); observer.finish(sample); return { manifest: m, sample };
  };
  const malformed = inspect('proxy', 'msg_fixture');
  expect(malformed.sample.wire_fingerprint_valid).toBe(false);
  expect(malformed.sample.wire_fingerprint_sha256).toBeNull();
  const bomManifest = defaults(); bomManifest.role = 'candidate'; bomManifest.target = { kind: 'proxy' }; bomManifest.transport = 'sse';
  const bomEvents: any[] = structuredClone(frames(bomManifest)); bomEvents[0].message.id = 'msg_1794080363569_5';
  const bomObserver = new WireObserver(bomManifest, () => 1);
  for (const [index, event] of bomEvents.entries()) {
    const prefix = index === 0 ? String.fromCharCode(0xfeff) : '';
    bomObserver.chunk(new TextEncoder().encode(prefix + 'event: ' + event.type + '\ndata: ' + JSON.stringify(event) + '\n\n'));
  }
  const bomSample = initial(0, 0, 'steady', 0); bomObserver.finish(bomSample);
  expect(bomSample.wire_fingerprint_valid).toBe(false);
  const invalidUtf8Observer = new WireObserver(bomManifest, () => 1);
  expect(() => invalidUtf8Observer.chunk(new Uint8Array([0xff]))).toThrow();

  const duplicateManifest = defaults(); duplicateManifest.role = 'candidate'; duplicateManifest.target = { kind: 'proxy' }; duplicateManifest.transport = 'sse';
  const duplicateEvents: any[] = structuredClone(frames(duplicateManifest)); const duplicateId = 'msg_1794080363569_6';
  duplicateEvents[0].message.id = duplicateId;
  const escapedIdKey = String.fromCharCode(92) + 'u0069d';
  const firstJson = JSON.stringify(duplicateEvents[0]).replace('"id":"' + duplicateId + '","type"',
    '"id":"' + duplicateId + '","' + escapedIdKey + '":"' + duplicateId + '","type"');
  const duplicateObserver = new WireObserver(duplicateManifest, () => 1);
  duplicateObserver.chunk(new TextEncoder().encode('event: message_start\ndata: ' + firstJson + '\n\n'));
  for (const event of duplicateEvents.slice(1)) duplicateObserver.chunk(new TextEncoder().encode('event: ' + event.type + '\ndata: ' + JSON.stringify(event) + '\n\n'));
  const duplicateSample = initial(0, 0, 'steady', 0); duplicateObserver.finish(duplicateSample);
  expect(duplicateSample.wire_fingerprint_valid).toBe(false);
  expect(compareWireFingerprints(malformed.manifest, [malformed.sample], malformed.manifest, [malformed.sample])).toBe('invalid');

  const direct = inspect('direct', 'msg_fixture');
  expect(direct.sample.wire_fingerprint_scheme).toBe('raw-wire-v1');
  expect(direct.sample.wire_fingerprint_sha256).toBe(direct.sample.wire_sha256);
  expect(direct.sample.wire_fingerprint_valid).toBe(true);
  const oldReceiptSample = { ...direct.sample, wire_fingerprint_sha256: undefined, wire_fingerprint_scheme: undefined,
    wire_fingerprint_valid: undefined, wire_message_id: undefined } as any;
  expect(compareWireFingerprints(direct.manifest, [oldReceiptSample], direct.manifest, [direct.sample])).toBe('invalid');

  const first = inspect('proxy', 'msg_1794080363569_4');
  const duplicate = { ...first.sample, sequence: 1 };
  expect(compareWireFingerprints(first.manifest, [first.sample, duplicate], first.manifest, [first.sample])).toBe('invalid');
});

test('proxy Anthropic nonstream fingerprint normalizes only the top-level generated message ID', () => {
  const inspect = (role: 'baseline' | 'candidate' | 'direct', id: string, rawOverride?: string, change?: (body: any) => void) => {
    const m = defaults(); m.role = role === 'direct' ? 'direct' : role; m.target = { kind: role === 'direct' ? 'direct' : 'proxy' };
    m.transport = 'http'; m.streaming = false;
    const body: any = structuredClone(nonstream(m)); body.id = id; change?.(body);
    const raw = rawOverride ?? JSON.stringify(body);
    const observer = new WireObserver(m, () => 1); observer.chunk(new TextEncoder().encode(raw));
    const sample = initial(0, 0, 'steady', 0); observer.finish(sample);
    return { manifest: m, sample, raw };
  };
  const baseline = inspect('baseline', 'msg_1794079955675_1');
  const candidate = inspect('candidate', 'msg_1794080363569_1');
  expect(baseline.sample.terminal_correct).toBe(true);
  expect(candidate.sample.terminal_correct).toBe(true);
  expect(baseline.sample.wire_sha256).not.toBe(candidate.sample.wire_sha256);
  expect(baseline.sample.wire_fingerprint_scheme).toBe('anthropic-json-message-id-v1');
  expect(baseline.sample.wire_fingerprint_valid).toBe(true);
  expect(baseline.sample.wire_fingerprint_sha256).toBe(candidate.sample.wire_fingerprint_sha256);
  expect(compareWireFingerprints(baseline.manifest, [baseline.sample], candidate.manifest, [candidate.sample])).toBe('equal');

  const changedPayload = inspect('candidate', 'msg_1794080363569_2', undefined, body => { body.model = 'different-model'; });
  expect(changedPayload.sample.wire_fingerprint_sha256).not.toBe(baseline.sample.wire_fingerprint_sha256);
  expect(compareWireFingerprints(baseline.manifest, [baseline.sample], changedPayload.manifest, [changedPayload.sample])).toBe('changed');

  const malformed = inspect('candidate', 'msg_fixture');
  expect(malformed.sample.wire_fingerprint_valid).toBe(false);
  expect(malformed.sample.wire_fingerprint_sha256).toBeNull();
  const duplicate = inspect('candidate', 'msg_1794080363569_3', baseline.raw.replace('"id":"msg_1794079955675_1",', '"id":"msg_1794080363569_3","\\u0069d":"msg_1794080363569_3",'));
  expect(duplicate.sample.wire_fingerprint_valid).toBe(false);
  expect(compareWireFingerprints(duplicate.manifest, [duplicate.sample], candidate.manifest, [candidate.sample])).toBe('invalid');

  const first = inspect('candidate', 'msg_1794080363569_4');
  const reusedId = { ...first.sample, sequence: 1 };
  expect(compareWireFingerprints(first.manifest, [first.sample, reusedId], candidate.manifest, [candidate.sample])).toBe('invalid');

  const direct = inspect('direct', 'msg_fixture');
  expect(direct.sample.wire_fingerprint_scheme).toBe('raw-wire-v1');
  expect(direct.sample.wire_fingerprint_sha256).toBe(direct.sample.wire_sha256);
  expect(direct.sample.wire_fingerprint_valid).toBe(true);
});

test('wire error and missing terminal never become clean completion', () => {
  const m = defaults();
  for (const fs of [frames(m).slice(0, -1), [...frames(m), { type: 'error' }], [...frames(m), { type: 'message_stop' }]]) {
    const observer = new WireObserver(m, () => 1);
    for (const f of fs) observer.chunk(new TextEncoder().encode(`data: ${JSON.stringify(f)}\n\n`));
    const sample = initial(0, 0, 'steady', 0); observer.finish(sample);
    expect(sample.outcome).toBe('failed');
  }
});

test('request size is exact and fixtures cannot silently relabel unsupported collection', () => {
  const m = defaults(); expect(requestBytes(m).length).toBe(m.input_bytes);
  m.collection.exports = true; expect(() => validate(m)).toThrow();
  m.collection.exports = false; m.load.value = 1000; expect(() => validate(m)).toThrow();
});

test('upstream accepts reordered objects but rejects changed fields, values, array order and duplicate members', async () => {
  const m = defaults(); m.role = 'candidate'; m.target = { kind: 'proxy' }; m.fixture.first_wait_ms = 0; m.fixture.chunk_wait_ms = 0;
  const original = requestBytes(m), expected = JSON.parse(new TextDecoder().decode(original));
  const reordered = { messages: expected.messages.map(({ role, content }: any) => ({ content, role })), stream: expected.stream, max_tokens: expected.max_tokens, model: expected.model };
  const text = JSON.stringify(reordered), upstream = startUpstream(m);
  const changed = (edit: (value: any) => void) => { const value = structuredClone(reordered); edit(value); return JSON.stringify(value); };
  const invalid = [
    changed(value => { value.model = 'other'; }), changed(value => { value.max_tokens++; }),
    changed(value => { value.messages.reverse(); }), changed(value => { value.messages[0].content += 'x'; }),
    changed(value => { delete value.max_tokens; }), changed(value => { value.extra = true; }),
    changed(value => { value.messages[0].extra = null; }), changed(value => { value.stream = false; }),
    text.replace('"max_tokens":4', '"max_tokens":4.0000000000000001'),
    text.replace('{', '{"model":"fixture-model",'), text.replace('{', '{"mo\\u0064el":"fixture-model",'),
    text.replace('"messages":[{', '"messages":[{"role":"user",'),
    text.replace('"messages":[{', '"messages":[{"ro\\u006ce":"user",'),
    'null', '[]', '{', new Uint8Array([0xff]),
  ];
  try {
    for (const body of [text, ...invalid]) {
      const response = await fetch(`${upstream.url}/v1/messages`, { method: 'POST', body });
      expect(response.status).toBe(body === text ? 200 : 400); await response.arrayBuffer();
    }
    expect(upstream.stats().attempts).toBe(1);
    expect(upstream.stats().request_body_mismatches).toBe(invalid.length);
    expect(requestBytes(m)).toEqual(original);
  } finally { await upstream.stop(); }
});

test('only proxy Anthropic upstreams require streaming and the exact eager custom-tool default', async () => {
  const tool = { name: 'fixture_tool', description: 'synthetic fixture', input_schema: { type: 'object', properties: {} } };
  const eagerTool = { ...tool, eager_input_streaming: true }, disabledTool = { ...tool, eager_input_streaming: false };
  // Explicit wire expectations, independent of the fixture's transformation logic.
  const cases = [
    { role: 'direct', target: 'direct', streaming: false, transport: 'http', upstream: undefined,
      accepted: { stream: false, tools: [tool] }, rejected: [{ stream: true }, { tools: [eagerTool] }, { tools: [disabledTool] }], contentType: 'application/json' },
    { role: 'direct', target: 'direct', streaming: true, transport: 'sse', upstream: undefined,
      accepted: { stream: true, tools: [tool] }, rejected: [{ stream: false }, { tools: [eagerTool] }, { tools: [disabledTool] }], contentType: 'text/event-stream' },
    { role: 'candidate', target: 'proxy', streaming: false, transport: 'http', upstream: { protocol: 'anthropic', transport: 'sse' },
      accepted: { stream: true, tools: [tool] }, rejected: [{ stream: false }, { tools: [eagerTool] }, { tools: [disabledTool] }], contentType: 'text/event-stream' },
    { role: 'candidate', target: 'proxy', streaming: true, transport: 'sse', upstream: { protocol: 'anthropic', transport: 'sse' },
      accepted: { stream: true, tools: [eagerTool] }, rejected: [{ stream: false }, { tools: [tool] }, { tools: [disabledTool] }], contentType: 'text/event-stream' },
  ] as const;
  for (const row of cases) {
    const m = defaults(); m.role = row.role; m.target = { kind: row.target }; m.upstream = row.upstream;
    m.streaming = row.streaming; m.transport = row.transport; m.scenario = 'tool-heavy';
    m.fixture.first_wait_ms = 0; m.fixture.chunk_wait_ms = 0;
    const original = requestBytes(m), expected = { ...JSON.parse(new TextDecoder().decode(original)), ...row.accepted };
    const upstream = startUpstream(m);
    try {
      const response = await fetch(`${upstream.url}/v1/messages`, { method: 'POST', body: JSON.stringify(expected) });
      expect(response.status).toBe(200);
      expect(response.headers.get('content-type')).toContain(row.contentType);
      const content = await response.text();
      if (row.contentType === 'text/event-stream') expect(content).toContain('event: message_stop');
      else expect(JSON.parse(content).stop_reason).toBe('tool_use');
      for (const override of row.rejected) {
        const body = { ...expected, ...override };
        const rejected = await fetch(`${upstream.url}/v1/messages`, { method: 'POST', body: JSON.stringify(body) });
        expect(rejected.status).toBe(400); await rejected.arrayBuffer();
      }
      expect(upstream.stats().attempts).toBe(1);
      expect(upstream.stats().request_body_mismatches).toBe(row.rejected.length);
      expect(requestBytes(m)).toEqual(original);
    } finally { await upstream.stop(); }
  }
});

test('strict JSON matching bounds nesting and breadth without interpreting punctuation inside strings', () => {
  const matches = requestBodyMatcher('{"message":"escaped \\\" \\\\ , } ] : ","nested":{"a":1},"other":{"a":2}}');
  expect(matches('{"other":{"a":2},"nested":{"a":1},"message":"escaped \\\" \\\\ , } ] : "}')).toBe(true);
  expect(matches('{"other":{"a":2},"nested":{"a":1,"a":1},"message":"escaped \\\" \\\\ , } ] : "}')).toBe(false);
  for (const [open, empty, close] of [['[', '[]', ']'], ['{"child":', '{}', '}']] as const) {
    const allowed = open.repeat(63) + empty + close.repeat(63);
    const refused = open.repeat(64) + empty + close.repeat(64);
    expect(requestBodyMatcher(allowed)(allowed)).toBe(true);
    expect(() => requestBodyMatcher(refused)).toThrow('request_structure_limit');
  }
  const allowedNodes = JSON.stringify(Array(65535).fill(0));
  expect(requestBodyMatcher(allowedNodes)(allowedNodes)).toBe(true);
  expect(() => requestBodyMatcher(JSON.stringify(Array(65536).fill(0)))).toThrow('request_structure_limit');
  // Structural refusal precedes final grammar validation, including unfinished input.
  expect(() => requestBodyMatcher('['.repeat(65))).toThrow('request_structure_limit');
  expect(() => requestBodyMatcher('[' + Array(65536).fill('0').join(','))).toThrow('request_structure_limit');
  for (const malformed of ['{"nested":{"a":1,},"message":"x"}', '[true false]', '{"a" 1}', '{"a":"unterminated', '{"a":"\\q"}']) expect(() => requestBodyMatcher(malformed)).toThrow();
  expect(() => requestBodyMatcher('1e400')).toThrow('request_number_invalid');
  expect(requestBodyMatcher('{"tokens":4}')('{"tokens":4.0e0}')).toBe(true);
  expect(requestBodyMatcher('{"tokens":4}')('{"tokens":4.0000000000000001}')).toBe(false);
});

test('open arrivals keep every offered slot and explicitly drop excess work', async () => {
  const root = await mkdtemp('/tmp/splice-instrumentation-test-');
  try {
    const m = defaults(); m.run_id = 'overload'; m.warmup_ms = 0; m.duration_ms = 100; m.repetitions = 1;
    m.load = { kind: 'arrival_rate', value: 1000 }; m.generator_limits.max_inflight = 1; m.fixture.first_wait_ms = 70;
    const result: any = await run(m, join(root, 'run'));
    expect(result.counts.offered).toBe(100); expect(result.counts.generator_dropped).toBeGreaterThan(0);
    expect(result.status).toBe('partial'); expect(result.generator.saturated).toBe(true);
    const read = await readRun(join(root, 'run')); expect(read.rows.length).toBe(100);
    expect((await analyze(join(root, 'run'), join(root, 'run'))).status).toBe('invalid');
    await Bun.write(join(root, 'run', 'samples.jsonl'), '');
    expect(readRun(join(root, 'run'))).rejects.toThrow('receipt_digest_mismatch');
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('direct SSE, nonstream, tool-only, WS, refusal and cancellation have honest outcomes', async () => {
  const root = await mkdtemp('/tmp/splice-instrumentation-protocol-');
  try {
    for (const name of ['sse', 'http', 'tool-heavy', 'websocket', 'refusal', 'cancel'] as const) {
      const m = defaults(); m.run_id = name; m.warmup_ms = 0; m.duration_ms = 100; m.repetitions = 1;
      if (name === 'http') { m.streaming = false; m.transport = 'http'; }
      if (name === 'websocket') { m.protocol = 'responses'; m.transport = 'websocket'; }
      if (['tool-heavy', 'refusal', 'cancel'].includes(name)) m.scenario = name as any;
      if (name === 'cancel') m.fixture.first_wait_ms = 50;
      const result: any = await run(m, join(root, name));
      expect(result.status).toBe('complete');
      expect(result.counts.failed).toBe(0);
      expect(result.counts[name === 'refusal' ? 'refused' : name === 'cancel' ? 'cancelled' : 'completed']).toBeGreaterThan(0);
    }
  } finally { await rm(root, { recursive: true, force: true }); }
});
