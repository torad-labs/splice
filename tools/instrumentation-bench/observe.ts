import { createHash } from 'node:crypto';
import { type Manifest, loopback, hash } from './manifest';
import { sleep } from './fixture';
import { wireFingerprintScheme, type WireFingerprintScheme } from './wire-fingerprint';
export interface Sample {
  sequence: number; repetition: number; phase: 'warmup' | 'steady'; scheduled_at_ms: number;
  started_at_ms: number | null; first_content_at_ms: number | null; terminal_at_ms: number | null;
  offered: boolean; accepted: boolean | null; outcome: 'completed' | 'failed' | 'refused' | 'cancelled' | 'pending' | 'generator_dropped';
  bytes: number | null; events: number | null; tokens: number | null; missingness: Record<string, string>;
  terminal_correct: boolean; wire_sha256: string | null; wire_fingerprint_sha256: string | null;
  wire_fingerprint_scheme: WireFingerprintScheme | null; wire_fingerprint_valid: boolean; wire_message_id: string | null;
  content_sha256: string | null; generator_delay_ms: number | null;
  error_class: string | null; status_code: number | null; queue_ms: number | null;
}
export function initial(sequence: number, repetition: number, phase: Sample['phase'], scheduled: number): Sample {
  return { sequence, repetition, phase, scheduled_at_ms: scheduled, started_at_ms: null, first_content_at_ms: null, terminal_at_ms: null,
    offered: true, accepted: null, outcome: 'pending', bytes: null, events: null, tokens: null,
    missingness: { queue_ms: 'no server admission adapter', tokens: 'no terminal usage observed' }, terminal_correct: false,
    wire_sha256: null, wire_fingerprint_sha256: null, wire_fingerprint_scheme: null, wire_fingerprint_valid: false, wire_message_id: null,
    content_sha256: null, generator_delay_ms: null, error_class: null, status_code: null, queue_ms: null };
}
export class WireObserver {
  bytes = 0; events = 0; first: number | null = null; terminal: number | null = null; tokens: number | null = null;
  malformed = false; errored = false; terminalCount = 0; started = false; stopReason = false; contentEvents = 0;
  private pending = ''; private wire = createHash('sha256'); private content = createHash('sha256');
  private canonicalWire = createHash('sha256');
  private readonly fingerprintScheme: WireFingerprintScheme;
  private fingerprintValid = true; private messageStartCount = 0; private messageId: string | null = null;
  private jsonFingerprintAdded = false;
  // Keep an initial BOM visible: stripping it would make distinct raw streams share a canonical hash.
  private decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
  constructor(private m: Manifest, private now: () => number) { this.fingerprintScheme = wireFingerprintScheme(m); }
  event(value: any) {
    this.events++;
    if (value?.type === 'error' || value?.type === 'response.failed' || value?.type === 'response.incomplete') this.errored = true;
    if (value?.type === 'message_start' || value?.type === 'response.created') this.started = true;
    const d = value?.delta;
    const content = value?.type === 'content_block_delta' ? d?.text ?? d?.thinking ?? d?.partial_json
      : ['response.output_text.delta', 'response.reasoning_summary_text.delta', 'response.function_call_arguments.delta'].includes(value?.type) ? d : null;
    const toolStart = value?.type === 'content_block_start' && value?.content_block?.type === 'tool_use';
    if ((typeof content === 'string' && content.length > 0) || toolStart) {
      if (this.terminalCount) this.malformed = true;
      this.first ??= this.now(); this.contentEvents++;
      if (typeof content === 'string') this.content.update(content);
    }
    if (value?.type === 'message_delta' && ['end_turn', 'tool_use', 'max_tokens', 'stop_sequence'].includes(d?.stop_reason)) this.stopReason = true;
    const usage = value?.usage ?? value?.response?.usage;
    if (Number.isSafeInteger(usage?.output_tokens) && usage.output_tokens >= 0) this.tokens = usage.output_tokens;
    if (value?.type === 'message_stop' || value?.type === 'response.completed') {
      this.terminalCount++; this.terminal = this.now();
      if (value.type === 'response.completed') this.stopReason = value.response?.status === 'completed';
    }
  }
  private frame(frame: string, separator: string) {
    const lines = frame.split(/\r?\n/);
    const eventLine = lines.find(line => line.startsWith('event:'));
    const eventName = eventLine?.slice(6).trimStart() ?? null;
    const dataLines = lines.filter(line => line.startsWith('data:'));
    const data = dataLines.map(line => line.slice(5).trimStart()).join('\n');
    let value: any = null;
    if (data && data !== '[DONE]') { value = JSON.parse(data); this.event(value); }
    if (this.fingerprintScheme !== 'anthropic-sse-message-id-v1') return;
    let canonicalFrame = frame;
    if (value?.type === 'message_start') {
      this.messageStartCount++;
      const id = value?.message?.id;
      const token = typeof id === 'string' ? JSON.stringify(id) : '';
      const dataLine = dataLines.length === 1 ? dataLines[0] : '';
      const dataStart = dataLine ? frame.indexOf(dataLine) + dataLine.indexOf(data) : -1;
      // This exact compact shape is emitted by MessageStart.ensureStart; round-tripping rejects duplicate or escaped keys.
      const idPrefix = '{"type":"message_start","message":{"id":';
      const messagePrefix = idPrefix + token + ',';
      const tokenAt = dataStart + idPrefix.length;
      const idIsInMessageField = dataLines.length === 1 && JSON.stringify(value) === data && dataStart >= 0 &&
        data.startsWith(messagePrefix) && frame.slice(tokenAt, tokenAt + token.length) === token;
      if (eventName !== 'message_start' || this.messageStartCount !== 1 || typeof id !== 'string' ||
          !/^msg_[0-9]{13}_[1-9][0-9]*$/.test(id) || !idIsInMessageField) {
        this.fingerprintValid = false;
      } else {
        this.messageId = id;
        canonicalFrame = frame.slice(0, tokenAt) + JSON.stringify('msg_0000000000000_1') + frame.slice(tokenAt + token.length);
      }
    } else if (eventName === 'message_start') {
      this.fingerprintValid = false;
    }
    this.canonicalWire.update(canonicalFrame + separator);
  }
  private drainFrames() {
    const separatorPattern = /\r?\n\r?\n/;
    let match: RegExpExecArray | null;
    while ((match = separatorPattern.exec(this.pending)) !== null) {
      const frame = this.pending.slice(0, match.index);
      const separator = match[0];
      this.pending = this.pending.slice(match.index + separator.length);
      this.frame(frame, separator);
    }
  }
  chunk(bytes: Uint8Array, ws = false) {
    this.bytes += bytes.byteLength; this.wire.update(bytes);
    if (this.bytes > this.m.generator_limits.max_wire_bytes) throw new Error('wire_limit');
    const decoded = this.decoder.decode(bytes, { stream: !ws });
    if (ws) { this.event(JSON.parse(decoded)); return; }
    this.pending += decoded;
    if (this.m.streaming) this.drainFrames();
  }
  private fingerprint(sample: Sample, wireSha: string, complete: boolean) {
    sample.wire_sha256 = wireSha;
    sample.wire_fingerprint_scheme = this.fingerprintScheme;
    if (this.fingerprintScheme === 'raw-wire-v1') {
      sample.wire_fingerprint_sha256 = wireSha; sample.wire_fingerprint_valid = true; sample.wire_message_id = null;
      return;
    }
    if (this.pending) {
      if (this.pending.trim()) this.fingerprintValid = false;
      this.canonicalWire.update(this.pending);
    }
    const valid = complete && this.fingerprintValid && this.messageId !== null &&
      (this.fingerprintScheme === 'anthropic-json-message-id-v1' ? this.jsonFingerprintAdded && this.messageStartCount === 0 : this.messageStartCount === 1);
    sample.wire_fingerprint_sha256 = valid ? this.canonicalWire.digest('hex') : null;
    sample.wire_fingerprint_valid = valid; sample.wire_message_id = this.messageId;
  }
  finish(sample: Sample) {
    this.pending += this.decoder.decode();
    if (this.m.streaming) this.drainFrames();
    if (!this.m.streaming) {
      const v = JSON.parse(this.pending); this.events = 1;
      if (this.fingerprintScheme === 'anthropic-json-message-id-v1') {
        const raw = this.pending; const id = v?.id; const token = typeof id === 'string' ? JSON.stringify(id) : '';
        const idPrefix = '{"id":'; const tokenAt = idPrefix.length;
        const idIsExactTopLevelField = typeof id === 'string' && raw === JSON.stringify(v) &&
          raw.startsWith(idPrefix + token + ',') && raw.slice(tokenAt, tokenAt + token.length) === token;
        if (!/^msg_[0-9]{13}_[1-9][0-9]*$/.test(id ?? '') || !idIsExactTopLevelField) this.fingerprintValid = false;
        else {
          this.messageId = id; this.jsonFingerprintAdded = true;
          this.canonicalWire.update(raw.slice(0, tokenAt) + JSON.stringify('msg_0000000000000_1') + raw.slice(tokenAt + token.length));
        }
      }
      const okay = this.m.protocol === 'anthropic' ? v.type === 'message' && ['end_turn', 'tool_use', 'max_tokens', 'stop_sequence'].includes(v.stop_reason) : v.status === 'completed';
      if (okay) { this.started = true; this.stopReason = true; this.terminalCount = 1; this.terminal = this.now(); }
      const blocks = this.m.protocol === 'anthropic' ? v.content : v.output?.flatMap((item: any) => item.content ?? [item]);
      for (const block of blocks ?? []) {
        if (typeof block.text === 'string' && block.text) { this.first ??= this.now(); this.content.update(block.text); this.contentEvents++; }
        if (block.type === 'tool_use' || block.type === 'function_call') { this.first ??= this.now(); this.content.update(typeof block.arguments === "string" ? block.arguments : JSON.stringify(block.input)); this.contentEvents++; }
      }
      if (Number.isSafeInteger(v.usage?.output_tokens)) this.tokens = v.usage.output_tokens;
      this.pending = '';
    }
    if (this.pending.trim()) this.malformed = true;
    sample.bytes = this.bytes; sample.events = this.events; sample.tokens = this.tokens;
    sample.first_content_at_ms = this.first; sample.terminal_at_ms = this.terminal;
    this.fingerprint(sample, this.wire.digest('hex'), true); sample.content_sha256 = this.content.digest('hex');
    if (this.tokens !== null) delete sample.missingness.tokens;
    const expectedContent = this.m.scenario === "tool-heavy" ? (this.m.streaming ? " ".repeat(this.m.fixture.chunks) + "{}" : "{}") : "x".repeat(this.m.fixture.chunks);
    sample.terminal_correct = sample.content_sha256 === hash(expectedContent) && this.started && this.stopReason && this.terminalCount === 1 && !this.errored && !this.malformed && this.contentEvents > 0 && this.tokens === this.m.output_tokens_target;
    sample.outcome = sample.terminal_correct ? 'completed' : 'failed';
    sample.error_class = sample.terminal_correct ? null : 'wire_invalid';
  }
  partial(sample: Sample) {
    sample.bytes = this.bytes; sample.events = this.events; sample.tokens = this.tokens;
    sample.first_content_at_ms = this.first; this.fingerprint(sample, this.wire.digest('hex'), false); sample.content_sha256 = this.content.digest('hex');
  }
}
export async function observe(m: Manifest, target: string, bytes: Uint8Array, sample: Sample, now: () => number): Promise<Sample> {
  loopback(target); sample.started_at_ms = now(); sample.generator_delay_ms = sample.started_at_ms - sample.scheduled_at_ms;
  const observer = new WireObserver(m, now); const controller = new AbortController(); let intentionalCancel = false;
  const timeout = setTimeout(() => controller.abort(), m.generator_limits.timeout_ms);
  const cancel = m.scenario === 'cancel' ? setTimeout(() => { intentionalCancel = true; controller.abort(); }, m.fixture.cancel_after_ms) : null;
  try {
    if (m.transport === 'websocket') {
      await new Promise<void>((resolve, reject) => {
        const socket = new WebSocket(target.replace('http:', 'ws:')); socket.binaryType = 'arraybuffer';
        const abort = () => { socket.close(); reject(new Error('aborted')); };
        controller.signal.addEventListener('abort', abort, { once: true });
        socket.onopen = () => { sample.accepted = true; socket.send(bytes); };
        socket.onmessage = event => { try { observer.chunk(typeof event.data === 'string' ? new TextEncoder().encode(event.data) : new Uint8Array(event.data), true); } catch { socket.close(); reject(new Error('wire_invalid')); } };
        socket.onerror = () => reject(new Error('network_error'));
        socket.onclose = event => { controller.signal.removeEventListener('abort', abort); event.code === 1000 ? resolve() : reject(new Error('websocket_close')); };
      });
    } else {
      const response = await fetch(target, { method: 'POST', headers: { 'content-type': 'application/json', 'anthropic-version': '2023-06-01', 'x-api-key': 'synthetic-fixture-key' }, body: bytes as Uint8Array<ArrayBuffer>, signal: controller.signal, redirect: 'error' });
      sample.status_code = response.status;
      sample.accepted = response.ok;
      if (!response.ok) {
        await response.body?.cancel(); sample.outcome = [429, 503].includes(response.status) ? 'refused' : 'failed';
        sample.terminal_at_ms = now(); sample.terminal_correct = sample.outcome === 'refused'; sample.error_class = `http_${Math.floor(response.status / 100)}xx`;
        return sample;
      }
      if (!response.body) throw new Error('empty_body');
      const reader = response.body.getReader();
      while (true) { const next = await reader.read(); if (next.done) break; observer.chunk(next.value); if (m.fixture.slow_read_ms) await sleep(m.fixture.slow_read_ms); }
    }
    observer.finish(sample);
  } catch (error) {
    observer.partial(sample); sample.terminal_at_ms = now();
    sample.outcome = intentionalCancel ? 'cancelled' : controller.signal.aborted ? 'pending' : 'failed';
    sample.terminal_correct = intentionalCancel && observer.terminalCount === 0;
    sample.error_class = intentionalCancel ? 'intentional_cancel' : controller.signal.aborted ? 'request_timeout' : error instanceof SyntaxError ? 'wire_json_invalid' : 'transport_or_wire_error';
  } finally { controller.abort(); clearTimeout(timeout); if (cancel) clearTimeout(cancel); }
  return sample;
}
