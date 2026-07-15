// Responses-SSE → Anthropic-SSE state machine. Emits ONLY through the sse.mjs
// emitter (L3): clean stops via emitTerminal, failures via emitError — and the
// upstream failure classification is the SAME classifier the HTTP path uses
// (codex/errors.mjs), so a context overflow arriving via response.failed now
// rewrites to "prompt is too long" and Claude Code compacts instead of dying.
import { getConfig } from '../config.mjs';
import { classifyUpstreamFailure } from './errors.mjs';
import { pickModelText, isWeakSummaryText, harvestResponsesOutput } from './translate-response.mjs';
import { mirrorInto, HONESTY_MIN_CHARS } from '../reasoning/mirror.mjs';
import { encodeReasoningEnvelope } from '../reasoning/replay.mjs';
import { recordCompactStat } from './compact.mjs';
import { appendCodexUsage, logTurnCache, makeOutputClamp } from '../usage/hud.mjs';

/** Streaming UTF-8 line reader over an SSE body (multi-byte-safe). */
async function* sseEvents(body, onBytes) {
  const utf8 = new TextDecoder('utf-8', { fatal: false });
  let lineBuffer = '';
  for await (const rawChunk of body) {
    onBytes?.();
    lineBuffer += utf8.decode(rawChunk, { stream: true });
    const lines = lineBuffer.split('\n');
    lineBuffer = lines.pop() ?? '';
    for (const line of lines) {
      if (!line.startsWith('data: ')) continue;
      const payload = line.slice(6).trim();
      if (!payload || payload === '[DONE]') continue;
      try {
        yield JSON.parse(payload);
      } catch { /* skip malformed frame */ }
    }
  }
}

/**
 * Drive one streaming turn. Everything wire-visible goes through `emitter`.
 * Returns a summary object for the caller's debug log.
 */
export async function runStreamTurn({ upstreamRes, upstreamAbort, res, emitter, meta, slot, t0 }) {
  const cfg = getConfig();
  const state = {
    blocks: new Map(), // upstream key -> { idx, sawDelta }
    hasToolUse: false,
    emittedText: false,
    incomplete: false,
    thinkingBuf: '',
    textBuf: '',
  };
  let finalResponse = null;
  let upstreamFailure = null;
  let streamInputTokens = 0;
  let streamOutputTokens = 0;
  const clampOutput = makeOutputClamp(meta.clientMaxTokens, meta.compact);

  // Two watchdog backstops so a stream never wedges its gate slot + undici
  // connection forever: (1) IDLE — no bytes for streamIdleMs; (2) TOTAL ELAPSED —
  // an overloaded backend can trickle non-terminal bytes (keepalives / slow
  // partial deltas) that keep idle low yet never send response.completed. Without
  // (2) that for-await never exits, the finally never runs, and the slot + its
  // pooled connection leak: the 64-conn pool fills and new turns stall (the
  // "55 inflight, 2 agents" leak). The abort surfaces as an SSE error downstream,
  // never a clean end_turn (v25).
  // BEFORE the first byte the backend is PREFILLING — a big-context turn
  // (compaction re-reads the whole ~160k transcript uncached) legitimately takes
  // minutes before it streams anything, so the pre-first-byte idle limit is the
  // long firstByteTimeoutMs, NOT streamIdleMs. Reaping prefill on streamIdleMs was
  // the regression that aborted every compaction mid-prefill → retry loop that
  // re-read the whole transcript each attempt (the "compaction ate my quota" bug).
  // streamIdleMs only means "zombie" AFTER streaming has actually started.
  let idleAborted = false;
  let sawFirstByte = false;
  const idleTimer = setInterval(() => {
    if (!slot) return;
    const idle = slot.idleFor();
    const idleLimit = sawFirstByte ? cfg.streamIdleMs : cfg.firstByteTimeoutMs;
    const overIdle = idle >= idleLimit;
    const overCap = Date.now() - t0 >= cfg.upstreamTimeoutMs;
    if (overIdle || overCap) {
      idleAborted = true;
      const why = overCap
        ? `over ${cfg.upstreamTimeoutMs}ms total cap`
        : sawFirstByte ? `idle ${idle}ms` : `no first byte in ${idle}ms (prefill)`;
      process.stderr.write(`[codex-proxy] stream ${why} label=${meta.upstreamModel} — aborting upstream\n`);
      try { upstreamAbort?.abort(); } catch { /* ignore */ }
    }
  }, Math.min(15_000, Math.max(250, Math.floor(cfg.streamIdleMs / 3))));

  try {
    for await (const evt of sseEvents(upstreamRes.body, () => { sawFirstByte = true; slot?.touch(); })) {
      if (evt.type === 'response.completed' || evt.type === 'response.done' || evt.type === 'response.incomplete') {
        finalResponse = evt.response ?? evt;
        if (evt.type === 'response.incomplete' || finalResponse?.status === 'incomplete') state.incomplete = true;
        const u = finalResponse.usage ?? {};
        streamInputTokens = u.input_tokens ?? u.prompt_tokens ?? streamInputTokens;
        streamOutputTokens = u.output_tokens ?? u.completion_tokens ?? streamOutputTokens;
        continue;
      }

      // v25 honesty: upstream failure events used to be silently discarded and
      // the turn finished as a clean empty end_turn (corrupting the transcript).
      if (evt.type === 'response.failed' || evt.type === 'response.error' || evt.type === 'error') {
        const e = evt.response?.error ?? evt.error ?? evt;
        upstreamFailure = {
          code: String(e?.code ?? e?.type ?? 'upstream_failed'),
          message: String(e?.message ?? 'ChatGPT backend reported failure'),
        };
        continue;
      }

      if (evt.type === 'response.output_item.added' && evt.item) {
        const oi = evt.output_index ?? evt.item.index ?? state.blocks.size;
        if (evt.item.type === 'function_call') {
          const idx = emitter.openTool({
            id: evt.item.call_id ?? evt.item.id ?? `toolu_${Date.now()}_${oi}`,
            name: evt.item.name,
          });
          state.blocks.set(oi, { idx, sawDelta: false });
          state.hasToolUse = true;
        }
        // reasoning + message (text) blocks open lazily on their first delta —
        // avoids empty thinking widgets when a reasoning item carries no summary
        continue;
      }

      if (evt.type === 'response.output_item.done') {
        const oi = evt.output_index ?? evt.item?.index;
        if (oi !== undefined) {
          const b = state.blocks.get(oi);
          if (b) emitter.closeBlock(b.idx);
          const rb = state.blocks.get(`reasoning:${oi}`);
          if (rb) emitter.closeBlock(rb.idx);
        }
        // Replay (gated): emit the encrypted reasoning as a redacted_thinking
        // block HERE, in position — right after its summary closes and before the
        // tool_use it preceded — so the round-tripped input preserves cache order.
        if (cfg.replayReasoning && !meta.compact && evt.item?.type === 'reasoning' && evt.item.encrypted_content) {
          emitter.addRedactedThinking(encodeReasoningEnvelope(evt.item));
        }
        continue;
      }

      // New summary part = new paragraph. Parts arrive as part.added → deltas →
      // done; v24 closed the thinking block after part 1 (protocol violation:
      // deltas after content_block_stop), truncating the visible thinking UI.
      if (evt.type === 'response.reasoning_summary_part.added') {
        const key = `reasoning:${evt.output_index ?? 0}`;
        const b = state.blocks.get(key);
        if (b && b.sawDelta) {
          state.thinkingBuf += '\n\n';
          emitter.thinkingDelta(b.idx, '\n\n');
        }
        continue;
      }

      // Reasoning summary → Claude thinking UI
      if (
        (evt.type === 'response.reasoning_summary_text.delta' || evt.type === 'response.reasoning_text.delta')
        && evt.delta
      ) {
        const key = `reasoning:${evt.output_index ?? 0}`;
        let b = state.blocks.get(key);
        if (!b) {
          b = { idx: emitter.openThinking(), sawDelta: false };
          state.blocks.set(key, b);
          // separate multiple reasoning ITEMS in the mirror buffer
          if (state.thinkingBuf && !state.thinkingBuf.endsWith('\n')) state.thinkingBuf += '\n\n';
        }
        b.sawDelta = true;
        state.thinkingBuf += evt.delta;
        emitter.thinkingDelta(b.idx, evt.delta);
        continue;
      }

      if (
        evt.type === 'response.reasoning_summary_text.done'
        || evt.type === 'response.reasoning_summary_part.done'
        || evt.type === 'response.reasoning_text.done'
      ) {
        // These fire PER PART — closing here truncated multi-part summaries
        // (v25). The block closes on output_item.done / the end-of-stream sweep.
        continue;
      }

      if (evt.type === 'response.output_text.delta' && evt.delta) {
        const oi = evt.output_index ?? 0;
        let b = state.blocks.get(oi);
        if (!b) {
          b = { idx: emitter.openText(), sawDelta: false };
          state.blocks.set(oi, b);
        }
        state.emittedText = true;
        state.textBuf += evt.delta;
        emitter.textDelta(b.idx, evt.delta);
        continue;
      }

      if (evt.type === 'response.function_call_arguments.delta' && evt.delta) {
        const b = state.blocks.get(evt.output_index);
        if (!b) continue;
        emitter.inputJsonDelta(b.idx, evt.delta);
        continue;
      }

      if (evt.type === 'response.function_call_arguments.done') {
        const b = state.blocks.get(evt.output_index);
        if (b) emitter.closeBlock(b.idx);
        continue;
      }
    }
  } catch (err) {
    if (!idleAborted) {
      process.stderr.write(`[codex-proxy] stream read error label=${meta.upstreamModel}: ${String(err?.message || err)}\n`);
    }
  } finally {
    clearInterval(idleTimer);
  }

  // Close any blocks that remained open
  emitter.closeAll();

  // Harvest finalResponse when SSE deltas were empty/sparse (common on compact / high load)
  if (finalResponse) {
    const harvested = harvestResponsesOutput(finalResponse);
    if (harvested.text && !state.textBuf) state.textBuf = harvested.text;
    else if (harvested.text && isWeakSummaryText(state.textBuf) && !isWeakSummaryText(harvested.text)) {
      state.textBuf = harvested.text;
    }
    if (harvested.thinking && harvested.thinking.length > (state.thinkingBuf || '').length) {
      state.thinkingBuf = harvested.thinking;
    }
  }
  state.textBuf = state.textBuf || '';

  // ── Honesty gates: never dress a failed/truncated stream as a clean end_turn ──
  const failTurn = (type, message, statOutcome) => {
    if (meta.compact) {
      recordCompactStat({ outcome: statOutcome ?? 'stream_error', error: type, ms: Date.now() - t0 });
    }
    process.stderr.write(`[codex-proxy] stream fatal (${type}) label=${meta.upstreamModel}: ${message}\n`);
    emitter.emitError(type, message);
    appendCodexUsage(streamOutputTokens);
  };

  if (upstreamFailure) {
    // ONE classifier for HTTP and SSE (fixes the P0: overflow via response.failed
    // previously became a raw api_error and never triggered compaction).
    const mapped = classifyUpstreamFailure('sse', `${upstreamFailure.code} ${upstreamFailure.message}`);
    failTurn(mapped.type, `ChatGPT backend: ${mapped.message}`);
    return { outcome: 'upstream_failure' };
  }
  if (idleAborted) {
    failTurn(
      'overloaded_error',
      `claudex: upstream stream stalled (no completion within the ${Math.round(cfg.streamIdleMs / 1000)}s idle or ${Math.round(cfg.upstreamTimeoutMs / 1000)}s total cap) — aborted; retry`,
    );
    return { outcome: 'idle_abort' };
  }
  if (!finalResponse) {
    if (res.destroyed) {
      // client aborted (Esc) — we cancelled upstream ourselves; not a truncation
      emitter.abandon();
      appendCodexUsage(streamOutputTokens);
      return { outcome: 'client_abort' };
    }
    failTurn('overloaded_error', 'claudex: upstream stream ended without response.completed (truncated); retry');
    return { outcome: 'truncated' };
  }

  // Promote model thinking → text when no text (compact needs text blocks). Never invent content.
  if (!state.emittedText && !state.hasToolUse) {
    const picked = pickModelText(state.thinkingBuf, state.textBuf);
    if (picked.text) {
      process.stderr.write(
        `[codex-proxy] promote-to-text compact=${meta.compact} source=${picked.source} chars=${picked.text.length}\n`,
      );
      emitter.addTextBlock(picked.text);
      state.emittedText = true;
      state.textBuf = (state.textBuf || '') + picked.text;
      if (meta.compact) {
        recordCompactStat({ outcome: picked.source, chars: picked.text.length, ms: Date.now() - t0 });
      }
    } else if (meta.compact) {
      // An empty compact is an ERROR, not an empty success — Claude Code would
      // store a blank summary and lose the session thread. Never invent locally.
      failTurn('api_error', 'claudex: compact returned no content from model — retry', 'empty_model');
      return { outcome: 'empty_compact' };
    } else if ((state.thinkingBuf || '').trim().length < HONESTY_MIN_CHARS) {
      // Completed but produced literally nothing (and nothing for the mirror to
      // carry) → honest error instead of a blank end_turn that stalls the loop.
      failTurn('api_error', 'claudex: model returned no content (empty response) — retry');
      return { outcome: 'empty_model' };
    }
  } else if (meta.compact && state.emittedText) {
    recordCompactStat({ outcome: 'model_text', chars: (state.textBuf || '').length, ms: Date.now() - t0 });
  }

  // Reasoning mirror (load-bearing for the operator): ONE mirrorInto for both
  // paths (L2) — the stream path's sink appends an SSE text block. Tools stay on.
  mirrorInto(
    { addTextBlock: (text) => emitter.addTextBlock(text) },
    state.thinkingBuf,
    { showReasoning: meta.showReasoning, compact: meta.compact },
  );

  logTurnCache({ model: meta.upstreamModel, usage: finalResponse?.usage ?? {}, compact: meta.compact });
  emitter.emitTerminal({
    hasToolUse: state.hasToolUse,
    incomplete: state.incomplete,
    usage: { input_tokens: streamInputTokens, output_tokens: clampOutput(streamOutputTokens) },
  });
  appendCodexUsage(streamOutputTokens);
  return {
    outcome: 'ok',
    text_chars: state.textBuf.length,
    thinking_chars: (state.thinkingBuf || '').length,
  };
}

/**
 * Non-stream collector: read the upstream SSE to its terminal object.
 * Returns { finalResp, failure } — translation happens in the entry handler
 * via translateResponse (one translator for both paths, v25).
 */
export async function collectTerminal(upstreamRes, slot) {
  let finalResp = null;
  let failure = null;
  try {
    for await (const evt of sseEvents(upstreamRes.body, () => slot?.touch())) {
      if (evt.type === 'response.completed' || evt.type === 'response.done' || evt.type === 'response.incomplete') {
        finalResp = evt.response ?? evt;
      } else if (evt.type === 'response.failed' || evt.type === 'response.error' || evt.type === 'error') {
        const e = evt.response?.error ?? evt.error ?? evt;
        failure = `${String(e?.code ?? e?.type ?? 'upstream_failed')} ${String(e?.message ?? 'ChatGPT backend reported failure')}`;
      }
    }
  } catch (err) {
    failure = failure || `stream read error: ${err?.message || err}`;
  }
  return { finalResp, failure };
}
