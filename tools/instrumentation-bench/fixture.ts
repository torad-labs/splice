import { hash, type Manifest } from './manifest';
export const sleep = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, Math.max(0, ms)));
export function requestBytes(m: Manifest): Uint8Array {
  const messages = Array.from({ length: m.fixture.history_messages }, (_, i) => ({ role: i % 2 ? 'assistant' : 'user', content: 'x' }));
  const body: any = m.protocol === 'anthropic'
    ? { model: 'fixture-model', max_tokens: m.output_tokens_target, stream: m.streaming, messages }
    : { model: 'fixture-model', stream: m.streaming, input: messages };
  if (m.scenario === 'tool-heavy') body.tools = m.protocol === 'anthropic'
    ? [{ name: 'fixture_tool', description: 'synthetic fixture', input_schema: { type: 'object', properties: {} } }]
    : [{ type: 'function', name: 'fixture_tool', parameters: { type: 'object', properties: {} } }];
  const base = JSON.stringify(body);
  if (Buffer.byteLength(base) > m.input_bytes) throw new Error('input_bytes too small for selected history/tools');
  const firstMessage = messages[0]; if (!firstMessage) throw new Error('fixture_history_empty');
  firstMessage.content += 'x'.repeat(m.input_bytes - Buffer.byteLength(base));
  return new TextEncoder().encode(JSON.stringify(body));
}
export function frames(m: Manifest): any[] {
  const tool = m.scenario === 'tool-heavy';
  if (m.protocol === 'responses') {
    const item = tool ? { type: 'function_call', id: 'fc_fixture', call_id: 'call_fixture', name: 'fixture_tool', arguments: '{}' }
      : { type: 'message', id: 'msg_fixture', role: 'assistant', content: [{ type: 'output_text', text: 'x'.repeat(m.fixture.chunks), annotations: [] }] };
    return [{ type: 'response.created', response: { id: 'resp_fixture', status: 'in_progress', output: [] } },
      { type: 'response.output_item.added', output_index: 0, item: { ...item, content: [], arguments: '' } },
      ...Array.from({ length: m.fixture.chunks }, () => tool
        ? { type: 'response.function_call_arguments.delta', output_index: 0, item_id: 'fc_fixture', delta: ' ' }
        : { type: 'response.output_text.delta', output_index: 0, content_index: 0, item_id: 'msg_fixture', delta: 'x' }),
      ...(tool ? [{ type: 'response.function_call_arguments.delta', output_index: 0, item_id: 'fc_fixture', delta: '{}' }] : []),
      { type: 'response.output_item.done', output_index: 0, item },
      { type: 'response.completed', response: { id: 'resp_fixture', status: 'completed', output: [item], usage: { input_tokens: 10, output_tokens: m.output_tokens_target, input_tokens_details: { cached_tokens: m.cache_state === 'warm' ? 5 : 0 } } } }];
  }
  return [{ type: 'message_start', message: { id: 'msg_fixture', type: 'message', role: 'assistant', model: 'fixture-model', content: [], stop_reason: null, stop_sequence: null, usage: { input_tokens: 10, output_tokens: 0 } } },
    { type: 'content_block_start', index: 0, content_block: tool ? { type: 'tool_use', id: 'tool_fixture', name: 'fixture_tool', input: {} } : { type: 'text', text: '' } },
    ...Array.from({ length: m.fixture.chunks }, () => ({ type: 'content_block_delta', index: 0,
      delta: tool ? { type: 'input_json_delta', partial_json: ' ' } : { type: 'text_delta', text: 'x' } })),
    ...(tool ? [{ type: 'content_block_delta', index: 0, delta: { type: 'input_json_delta', partial_json: '{}' } }] : []),
    { type: 'content_block_stop', index: 0 },
    { type: 'message_delta', delta: { stop_reason: tool ? 'tool_use' : 'end_turn', stop_sequence: null }, usage: { output_tokens: m.output_tokens_target } },
    { type: 'message_stop' }];
}
export function nonstream(m: Manifest): any {
  if (m.protocol === 'responses') return frames(m).at(-1).response;
  return { id: 'msg_fixture', type: 'message', role: 'assistant', model: 'fixture-model',
    content: m.scenario === 'tool-heavy' ? [{ type: 'tool_use', id: 'tool_fixture', name: 'fixture_tool', input: {} }]
      : [{ type: 'text', text: 'x'.repeat(m.fixture.chunks) }], stop_reason: m.scenario === 'tool-heavy' ? 'tool_use' : 'end_turn', stop_sequence: null,
    usage: { input_tokens: 10, output_tokens: m.output_tokens_target } };
}
export function startUpstream(m: Manifest) {
  let attempts = 0, active = 0, peak = 0, bodyMismatch = 0;
  const expected = hash(requestBytes(m));
  const all = frames(m);
  const schedule = async (emit: (value: any) => void, alive: () => boolean) => {
    for (let i = 0; i < all.length && alive(); i++) {
      const wait = i === 2 ? m.fixture.first_wait_ms : i > 2 && i < m.fixture.chunks + 2 ? m.fixture.chunk_wait_ms : 0;
      if (wait) await sleep(wait + (m.scenario === 'bursty' && i % 2 === 0 ? m.fixture.chunk_wait_ms * 3 : 0));
      if (alive()) emit(all[i]);
    }
  };
  const server = Bun.serve<{ closed: boolean }>({ hostname: '127.0.0.1', port: 0,
    async fetch(req, s) {
      if (req.headers.get('upgrade') === 'websocket') return s.upgrade(req, { data: { closed: false } }) ? undefined : new Response(null, { status: 400 });
      if (new URL(req.url).pathname === '/health') return Response.json({ ok: true, fixture: true });
      const body = new Uint8Array(await req.arrayBuffer());
      if (hash(body) !== expected) bodyMismatch++;
      attempts++;
      if (m.scenario === 'refusal' || (m.scenario === 'retry' && attempts % 3 === 1))
        return Response.json({ type: 'error', error: { type: 'rate_limit_error', message: 'scripted refusal' } }, { status: 429, headers: { 'retry-after': '0' } });
      if (!m.streaming) { await sleep(m.fixture.first_wait_ms); return Response.json(nonstream(m)); }
      active++; peak = Math.max(peak, active); let cancelled = false;
      return new Response(new ReadableStream({
        async start(controller) {
          const encoder = new TextEncoder();
          try { await schedule(value => controller.enqueue(encoder.encode(`event: ${value.type}\ndata: ${JSON.stringify(value)}\n\n`)), () => !cancelled); if (!cancelled) controller.close(); }
          catch { if (!cancelled) { cancelled = true; try { controller.error(new Error('fixture_stream_failure')); } catch {} } }
          finally { active--; }
        }, cancel() { cancelled = true; },
      }), { headers: { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' } });
    },
    websocket: {
      async message(ws, body) {
        attempts++; if (hash(typeof body === 'string' ? body : new Uint8Array(body)) !== expected) bodyMismatch++;
        active++; peak = Math.max(peak, active);
        try { await schedule(value => { ws.send(JSON.stringify(value)); }, () => !ws.data.closed); if (!ws.data.closed) ws.close(1000); }
        finally { active--; }
      }, close(ws) { ws.data.closed = true; },
    },
  });
  return { url: `http://127.0.0.1:${server.port}`, stop: () => server.stop(true), stats: () => ({ attempts, active, peak, request_body_mismatches: bodyMismatch }) };
}
