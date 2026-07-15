// Reasoning replay envelope (codex head) — the cache-warm half of Codex parity.
//
// The backend's opaque encrypted reasoning rides through Claude Code's transcript
// as a `redacted_thinking` block, then decodes back into a Responses `reasoning`
// input item so the prompt-cache prefix stays byte-stable across the agent loop.
// This runs ALONGSIDE the mirror (reasoning/mirror.mjs): the mirror carries the
// reasoning SUMMARY to the model as readable text; replay carries the ENCRYPTED
// reasoning to the backend for KV reuse. Different channels, both on by default.
//
// Distinct tag so this proxy never cross-decodes another proxy's envelope.
// encode() is called by BOTH response paths (stream + non-stream); decode() by
// the request translator. Replay is config-gated (config.replayReasoning) and
// never runs on compact turns — the gating lives at the call sites.

export const REASONING_ENVELOPE_TAG = 'splice-reasoning';
export const REASONING_ENVELOPE_VERSION = 1;

/** Responses `reasoning` output item → base64 envelope for a redacted_thinking block. */
export function encodeReasoningEnvelope(item) {
  return Buffer.from(JSON.stringify({
    tag: REASONING_ENVELOPE_TAG,
    v: REASONING_ENVELOPE_VERSION,
    item: {
      id: item.id,
      encrypted_content: item.encrypted_content,
      ...(Array.isArray(item.summary) && item.summary.length ? { summary: item.summary } : {}),
    },
  }), 'utf8').toString('base64');
}

/** redacted_thinking block `data` → Responses `reasoning` input item, or null when
 * the payload is not one of our envelopes (foreign/garbled data passes through
 * as a dropped block, exactly as under pure amnesia). */
export function decodeReasoningEnvelope(data) {
  let parsed;
  try {
    parsed = JSON.parse(Buffer.from(String(data || ''), 'base64').toString('utf8'));
  } catch {
    return null;
  }
  const item = parsed?.tag === REASONING_ENVELOPE_TAG && parsed.v === REASONING_ENVELOPE_VERSION ? parsed.item : null;
  if (!item?.id || typeof item.encrypted_content !== 'string' || !item.encrypted_content) return null;
  return {
    type: 'reasoning',
    id: item.id,
    encrypted_content: item.encrypted_content,
    summary: Array.isArray(item.summary) ? item.summary : [],
  };
}
