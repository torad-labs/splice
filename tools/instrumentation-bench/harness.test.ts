import { expect, test } from 'bun:test';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { defaults, validate } from './manifest';
import { frames, requestBytes, startUpstream } from './fixture';
import { requestBodyMatcher } from './request-body';
import { initial, WireObserver } from './observe';
import { run } from './run';
import { readRun, analyze } from './analyze';
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
  for (const proxy of [false, true]) for (const streaming of [false, true]) {
    const m = defaults(); m.streaming = streaming; m.transport = streaming ? 'sse' : 'http'; m.scenario = 'tool-heavy';
    m.fixture.first_wait_ms = 0; m.fixture.chunk_wait_ms = 0;
    if (proxy) { m.role = 'candidate'; m.target = { kind: 'proxy' }; m.upstream = { protocol: 'anthropic', transport: 'sse' }; }
    const original = requestBytes(m), expected = JSON.parse(new TextDecoder().decode(original));
    if (proxy) expected.stream = true;
    if (proxy && streaming) expected.tools[0].eager_input_streaming = true;
    const upstream = startUpstream(m);
    try {
      const response = await fetch(`${upstream.url}/v1/messages`, { method: 'POST', body: JSON.stringify(expected) });
      expect(response.status).toBe(200);
      expect(response.headers.get('content-type')).toContain(proxy || streaming ? 'text/event-stream' : 'application/json');
      const content = await response.text();
      if (proxy || streaming) expect(content).toContain('event: message_stop');
      else expect(JSON.parse(content).stop_reason).toBe('tool_use');
      const wrongStream = { ...expected, stream: !expected.stream };
      const wrongTool = structuredClone(expected); wrongTool.tools[0].eager_input_streaming = !(proxy && streaming);
      for (const body of [wrongStream, wrongTool]) {
        const rejected = await fetch(`${upstream.url}/v1/messages`, { method: 'POST', body: JSON.stringify(body) });
        expect(rejected.status).toBe(400); await rejected.arrayBuffer();
      }
      expect(requestBytes(m)).toEqual(original);
    } finally { await upstream.stop(); }
  }
});

test('strict JSON matching bounds nesting and breadth without interpreting punctuation inside strings', () => {
  const matches = requestBodyMatcher('{"message":"escaped \\\" \\\\ , } ] : ","nested":{"a":1},"other":{"a":2}}');
  expect(matches('{"other":{"a":2},"nested":{"a":1},"message":"escaped \\\" \\\\ , } ] : "}')).toBe(true);
  expect(matches('{"other":{"a":2},"nested":{"a":1,"a":1},"message":"escaped \\\" \\\\ , } ] : "}')).toBe(false);
  expect(() => requestBodyMatcher('['.repeat(66) + '0' + ']'.repeat(66))).toThrow('request_structure_limit');
  expect(() => requestBodyMatcher(JSON.stringify(Array(65536).fill(0)))).toThrow('request_structure_limit');
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
