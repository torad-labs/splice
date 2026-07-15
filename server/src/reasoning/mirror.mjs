// The reasoning mirror — the load-bearing half of the splice distillation loop
// (invariant L2). Reasoning items are never replayed upstream (L1, locked
// non-goal); instead each turn's summary is mirrored into the transcript as
// visible text, so conclusions persist while reasoning is re-derived fresh.
//
// ONE mirrorInto() serves both the stream and non-stream paths (v29 duplicated
// the logic at :1616 and :1702 and the copies drifted). Both entry paths pass a
// sink; a permanent test asserts both call it.

// Named thresholds — the single home for every magic number in this loop (P2).
export const MIRROR_MIN_CHARS = 20;   // mirror only when the summary is substantive
export const PROMOTE_MIN_CHARS = 40;  // thinking may be promoted to text at/above this
export const HONESTY_MIN_CHARS = 20;  // completed-but-empty below this → honest error, not blank end_turn

/** WIRE CONTRACT (external): the mirror block format v25–v29 shipped. The
 * any transcript tooling key on this shape. */
export function mirrorWireText(thinking) {
  return `\n[reasoning summary]\n${String(thinking).trim()}\n`;
}

/**
 * Mirror the turn's thinking into the transcript as a visible text block.
 * sink: { addTextBlock(text) } — stream path appends an SSE text block,
 * non-stream path pushes onto the response content array.
 * Returns true when the mirror was emitted.
 */
export function mirrorInto(sink, thinkingText, { showReasoning, compact }) {
  if (compact) return false;              // compact is a text-only summarizer turn
  if (showReasoning !== 'text') return false;
  const t = String(thinkingText || '').trim();
  if (t.length < MIRROR_MIN_CHARS) return false;
  sink.addTextBlock(mirrorWireText(t));
  return true;
}

/** Join the thinking blocks of an Anthropic content array (non-stream path). */
export function extractThinking(content) {
  return (Array.isArray(content) ? content : [])
    .filter((c) => c?.type === 'thinking')
    .map((c) => String(c.thinking || '').trim())
    .filter(Boolean)
    .join('\n\n');
}
