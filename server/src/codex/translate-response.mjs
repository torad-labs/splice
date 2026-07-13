// Terminal Responses object → Anthropic message (the non-stream translator,
// also the harvest source for sparse-SSE streams). Invariant L4 rides here:
// promote-to-text only ever promotes MODEL content (reasoning summary) — no
// literal is fabricated beyond error strings/markers elsewhere.
import { PROMOTE_MIN_CHARS } from '../reasoning/mirror.mjs';
import { buildUsagePayload } from '../usage/hud.mjs';

export function isWeakSummaryText(text) {
  const t = String(text || '').trim();
  if (!t) return true;
  if (/no model text returned/i.test(t)) return true;
  return false;
}

/**
 * Prefer real model text, then reasoning summary promoted to text
 * (fidelity-preserving: content came from the model, not a local extract).
 */
export function pickModelText(thinkingBuf = '', textBuf = '') {
  const text = String(textBuf || '').trim();
  const thinking = String(thinkingBuf || '').trim();
  if (text && !isWeakSummaryText(text)) return { text, source: 'model_text' };
  if (thinking.length >= PROMOTE_MIN_CHARS) return { text: thinking, source: 'model_thinking' };
  if (text) return { text, source: 'model_text_weak' };
  return { text: '', source: 'empty' };
}

/** Promote model reasoning summary into text when text is empty (model content only). */
export function ensureTextFromThinking(content) {
  if (!Array.isArray(content)) return content;
  const hasText = content.some((c) => c.type === 'text' && String(c.text || '').trim());
  if (hasText) return content;
  const thinking = content
    .filter((c) => c.type === 'thinking')
    .map((c) => String(c.thinking || '').trim())
    .filter(Boolean)
    .join('\n\n');
  if (thinking) content.push({ type: 'text', text: thinking });
  return content;
}

/** Pull text + thinking from a completed Responses object (when SSE deltas were sparse). */
export function harvestResponsesOutput(resp) {
  let text = '';
  let thinking = '';
  for (const item of resp?.output ?? []) {
    if (item?.type === 'reasoning') {
      const t = (item.summary ?? [])
        .map((s) => (typeof s === 'string' ? s : s?.text ?? ''))
        .filter(Boolean)
        .join('\n\n'); // parts are paragraphs — keep them readable in the mirror
      if (t) thinking += (thinking ? '\n\n' : '') + t;
    } else if (item?.type === 'message') {
      for (const c of item.content ?? []) {
        if (c.type === 'output_text' && c.text) text += c.text;
        if (c.type === 'text' && c.text) text += c.text;
      }
    }
  }
  return { text, thinking };
}

/** Terminal Responses object → Anthropic message shape. */
export function translateResponse(resp, originalModel) {
  const content = [];
  let outputTokens = 0;
  let inputTokens = 0;

  if (resp.usage) {
    inputTokens = resp.usage.input_tokens ?? 0;
    outputTokens = resp.usage.output_tokens ?? 0;
  }

  for (const item of resp.output ?? []) {
    if (item.type === 'reasoning') {
      const text = (item.summary ?? [])
        .map((s) => (typeof s === 'string' ? s : s?.text ?? ''))
        .filter(Boolean)
        .join('\n\n');
      if (text) content.push({ type: 'thinking', thinking: text });
    } else if (item.type === 'message') {
      for (const c of item.content ?? []) {
        if (c.type === 'output_text') {
          content.push({ type: 'text', text: c.text });
        }
      }
    } else if (item.type === 'function_call') {
      let input = {};
      try { input = JSON.parse(item.arguments ?? '{}'); } catch { /* keep empty */ }
      content.push({
        type: 'tool_use',
        id: item.call_id ?? `toolu_${Date.now()}`,
        name: item.name,
        input,
      });
    }
  }

  // Compact needs TEXT; promote model thinking → text only (no fabricated summary).
  ensureTextFromThinking(content);

  const hasToolUse = content.some((c) => c.type === 'tool_use');
  const incomplete = resp.status === 'incomplete';

  return {
    id: `msg_${resp.id ?? Date.now()}`,
    type: 'message',
    role: 'assistant',
    content: content.length > 0 ? content : [{ type: 'text', text: '' }],
    model: originalModel,
    stop_reason: hasToolUse ? 'tool_use' : (incomplete ? 'max_tokens' : 'end_turn'),
    stop_sequence: null,
    usage: buildUsagePayload(originalModel, {
      input_tokens: inputTokens,
      output_tokens: outputTokens,
    }),
  };
}
