import { expect, test } from 'bun:test';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { defaults, validate } from './manifest';
import { frames, requestBytes } from './fixture';
import { initial, WireObserver } from './observe';
import { run } from './run';
import { readRun, analyze } from './analyze';

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
