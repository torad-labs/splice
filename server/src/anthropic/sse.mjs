// The SOLE Anthropic wire emitter (invariant L3 — structural).
//
// Every SSE frame the proxy sends to Claude Code goes through this module.
// A clean stop is reachable ONLY via emitTerminal(); failures ONLY via
// emitError() — no other module may reference message_stop (an ast-grep wall
// enforces it), so a fake clean end_turn on a failed stream is unwritable.
import { makeSafeWriter, endResponse } from '../http/server.mjs';
import { buildUsagePayload } from '../usage/hud.mjs';

export function formatSSE(event, data) {
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
}

/**
 * Streaming emitter over an http response. Owns wire block indexes and the
 * message lifecycle; the codex stream machine drives it by upstream keys.
 */
export function createSseEmitter(res, { model, onFirstByte, onWriteError }) {
  const write = makeSafeWriter(res, { onFirstByte, onWriteError });
  const state = {
    started: false,
    ended: false,
    msgId: `msg_${Date.now()}`,
    nextBlockIdx: 0,
    open: new Set(),
  };

  const ensureStart = () => {
    if (state.started) return;
    state.started = true;
    write(formatSSE('message_start', {
      type: 'message_start',
      message: {
        id: state.msgId,
        type: 'message',
        role: 'assistant',
        content: [],
        model,
        stop_reason: null,
        stop_sequence: null,
        usage: buildUsagePayload(model),
      },
    }));
    write(formatSSE('ping', { type: 'ping' }));
  };

  const openBlock = (contentBlock) => {
    ensureStart();
    const idx = state.nextBlockIdx++;
    state.open.add(idx);
    write(formatSSE('content_block_start', { type: 'content_block_start', index: idx, content_block: contentBlock }));
    return idx;
  };

  const delta = (idx, deltaObj) => {
    write(formatSSE('content_block_delta', { type: 'content_block_delta', index: idx, delta: deltaObj }));
  };

  const closeBlock = (idx) => {
    if (!state.open.has(idx)) return;
    state.open.delete(idx);
    write(formatSSE('content_block_stop', { type: 'content_block_stop', index: idx }));
  };

  return {
    get started() { return state.started; },
    get ended() { return state.ended; },
    ensureStart,
    openText: () => openBlock({ type: 'text', text: '' }),
    openThinking: () => openBlock({ type: 'thinking', thinking: '' }),
    openTool: ({ id, name }) => openBlock({ type: 'tool_use', id, name: name ?? '', input: {} }),
    textDelta: (idx, text) => delta(idx, { type: 'text_delta', text }),
    thinkingDelta: (idx, thinking) => delta(idx, { type: 'thinking_delta', thinking }),
    inputJsonDelta: (idx, partial) => delta(idx, { type: 'input_json_delta', partial_json: partial }),
    closeBlock,
    closeAll: () => { for (const idx of [...state.open]) closeBlock(idx); },

    /** Complete text block in one shot (promote-to-text, mirror). */
    addTextBlock(text) {
      if (!text) return;
      const idx = openBlock({ type: 'text', text: '' });
      delta(idx, { type: 'text_delta', text });
      closeBlock(idx);
    },

    /** Emit an encrypted-reasoning replay block (redacted_thinking) in one shot —
     * the data rides in content_block_start, no delta. Round-trips into the next
     * request to hold the prompt cache (Codex-parity replay, stream path). */
    addRedactedThinking(data) {
      if (!data) return;
      const idx = openBlock({ type: 'redacted_thinking', data });
      closeBlock(idx);
    },

    /**
     * The ONLY clean ending. Derives stop_reason internally so no caller ever
     * holds an end_turn literal: tool_use > max_tokens (incomplete) > end_turn.
     */
    emitTerminal({ hasToolUse = false, incomplete = false, usage = {} } = {}) {
      if (state.ended) return;
      state.ended = true;
      ensureStart();
      write(formatSSE('message_delta', {
        type: 'message_delta',
        delta: {
          stop_reason: hasToolUse ? 'tool_use' : (incomplete ? 'max_tokens' : 'end_turn'),
          stop_sequence: null,
        },
        usage: buildUsagePayload(model, usage),
      }));
      write(formatSSE('message_stop', { type: 'message_stop' }));
      endResponse(res); // drains corks — message_stop must precede the terminal chunk
    },

    /** The ONLY failure ending — an SSE error event lets Claude Code retry honestly. */
    emitError(type, message) {
      if (state.ended) return;
      state.ended = true;
      if (!res.destroyed && !res.writableEnded) {
        write(formatSSE('error', { type: 'error', error: { type, message } }));
        endResponse(res);
      }
    },

    /** Client vanished mid-stream — nothing to emit, just seal the emitter. */
    abandon() {
      state.ended = true;
    },
  };
}
