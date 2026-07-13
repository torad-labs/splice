// The claudithos A/B transforms — the mythos experiment arms (pure function).
//
// A (amnesia): the Anthropic API signature-validates thinking blocks on
// tool-loop continuations, so thinking can't simply be deleted from a native
// tool exchange. Textualizing the tool blocks removes the constraint entirely:
// no tool_use/tool_result pairs in history → no thinking requirement → fresh
// full thinking pass every boundary. Message boundaries and role alternation
// are preserved (blocks are converted in place, never merged across messages).
// Images and cache_control breakpoints survive the conversion.
//
// B (mirror): past thinking persists as plain text so the model re-reads its
// own distilled notes. This is the ONLY place B happens — the response stream
// is never rewritten, so the tool-use loop stays intact (v1 injected into tool
// turns and fragmented it: parallel tool calls dropped, turns stopped early).
export function transformMessages(messages, opts = {}) {
  const mirrorThinking = opts.mirrorThinking === true;
  return (messages ?? []).map((msg) => {
    if (typeof msg.content === 'string') return msg;
    if (!Array.isArray(msg.content)) return msg;
    const blocks = [];
    for (const b of msg.content) {
      if (b?.type === 'thinking' || b?.type === 'redacted_thinking') {
        if (mirrorThinking && b.type === 'thinking') {
          const t = String(b.thinking || '').trim();
          if (t) blocks.push({ type: 'text', text: `[reasoning summary]\n${t}` });
        }
        continue;
      }
      if (b?.type === 'tool_use') {
        const nb = { type: 'text', text: `[called ${b.name} (${b.id})]\n${JSON.stringify(b.input ?? {})}` };
        if (b.cache_control) nb.cache_control = b.cache_control;
        blocks.push(nb);
        continue;
      }
      if (b?.type === 'tool_result') {
        const parts = typeof b.content === 'string'
          ? [{ type: 'text', text: b.content }]
          : (Array.isArray(b.content) ? b.content : []);
        const txt = parts.filter((p) => p?.type === 'text').map((p) => p.text).join('\n');
        const nb = {
          type: 'text',
          text: `[${b.tool_use_id} result${b.is_error ? ' (error)' : ''}]\n${txt}`,
        };
        if (b.cache_control) nb.cache_control = b.cache_control;
        blocks.push(nb);
        for (const p of parts) if (p?.type === 'image') blocks.push(p); // screenshots survive
        continue;
      }
      blocks.push(b); // text, image, document pass through untouched
    }
    if (blocks.length === 0) blocks.push({ type: 'text', text: '[empty]' }); // API rejects empty content
    return { ...msg, content: blocks };
  });
}
