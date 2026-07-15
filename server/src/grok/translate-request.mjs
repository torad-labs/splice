// Anthropic Messages → xAI Responses request translation (grok head). PURE:
// returns {req, meta}, never mutates the body. Mirrors codex/translate-request,
// adapted for xAI:
//   - reasoning.effort clamps to grok's ceiling `high` (no xhigh/max) and can
//     never disable (grok-4.5 always reasons); no reasoning.summary field
//     (grok auto-exposes summaries via the stream).
//   - prompt_cache_key is keyed on Claude Code's x-claude-code-session-id (a
//     stable per-session id — better than a content hash), passed via opts.
//   - replay ships OFF by default (it suppresses the fresh-reasoning wall);
//     opt in with CLAUDE_GROK_REPLAY_REASONING=1.
import { stripModelSuffixes } from '../models/grok-models.mjs';
import { decodeReasoningEnvelope } from '../reasoning/replay.mjs';

const ALLOWED_EFFORT = new Set(['low', 'medium', 'high']);

/** Normalize any effort label to grok's low|medium|high (its ceiling is high). */
export function normalizeEffort(raw) {
  if (raw == null) return null;
  const s = String(raw).trim().toLowerCase();
  if (!s) return null;
  if (['high', 'xhigh', 'max', 'ultra', 'ultracode', 'extra_high', 'extra-high', 'extrahigh', 'heavy', 'extended'].includes(s)) return 'high';
  if (['medium', 'standard', 'normal'].includes(s)) return 'medium';
  if (['low', 'minimal', 'none', 'off', 'fast', 'light'].includes(s)) return 'low';
  if (ALLOWED_EFFORT.has(s)) return s;
  return null;
}

export function effortFromBudget(budget) {
  if (typeof budget !== 'number' || !Number.isFinite(budget)) return null;
  if (budget >= 10_000) return 'high';
  if (budget >= 2_000) return 'medium';
  return 'low';
}

/** Anthropic image block → Responses input_image content part. */
function imagePartFromBlock(block) {
  const src = block?.source;
  if (!src) return null;
  if (src.type === 'base64' && src.data) {
    return { type: 'input_image', image_url: `data:${src.media_type || 'image/png'};base64,${src.data}` };
  }
  if (src.type === 'url' && src.url) return { type: 'input_image', image_url: src.url };
  return null;
}

function systemInstructions(body) {
  if (!body.system) return undefined;
  return typeof body.system === 'string'
    ? body.system
    : body.system.filter((b) => b.type === 'text').map((b) => b.text).join('');
}

/**
 * Build the upstream xAI Responses request.
 * opts: { compact, config, originalModel, sessionId }.
 */
export function buildRequest(body, { compact, config, originalModel, sessionId } = {}) {
  const input = [];

  for (const msg of body.messages ?? []) {
    if (typeof msg.content === 'string') {
      input.push({ role: msg.role, content: msg.content });
      continue;
    }
    for (const block of msg.content ?? []) {
      if (block.type === 'text') {
        input.push({ role: msg.role, content: block.text });
      } else if (block.type === 'image') {
        if (compact) continue;
        const part = imagePartFromBlock(block);
        if (part) input.push({ role: 'user', content: [part] });
        else input.push({ role: 'user', content: '[image omitted by claude-grok proxy: unsupported source]' });
      } else if (block.type === 'document') {
        if (!compact) {
          input.push({ role: 'user', content: `[document omitted by claude-grok proxy: ${block.source?.media_type ?? 'unknown type'}]` });
        }
      } else if (block.type === 'redacted_thinking') {
        // Replay (gated): decode our reasoning envelope back into a reasoning
        // input item, in position, so the prompt-cache prefix stays stable.
        if (compact || !config.replayReasoning) continue;
        const reasoning = decodeReasoningEnvelope(block.data);
        if (reasoning) input.push(reasoning);
      } else if (block.type === 'tool_use') {
        if (compact) continue;
        input.push({
          type: 'function_call',
          call_id: block.id,
          name: block.name,
          arguments: JSON.stringify(block.input ?? {}),
        });
      } else if (block.type === 'tool_result') {
        const content = typeof block.content === 'string'
          ? block.content
          : (Array.isArray(block.content)
            ? block.content.filter((b) => b.type === 'text').map((b) => b.text).join('')
            : '');
        if (compact) {
          if (content) input.push({ role: 'user', content: `[tool_result ${block.tool_use_id}] ${content}` });
          continue;
        }
        input.push({ type: 'function_call_output', call_id: block.tool_use_id, output: content });
        const imageParts = Array.isArray(block.content)
          ? block.content.filter((b) => b?.type === 'image').map(imagePartFromBlock).filter(Boolean)
          : [];
        if (imageParts.length) {
          input.push({
            role: 'user',
            content: [{ type: 'input_text', text: `[images from tool_result ${block.tool_use_id}]` }, ...imageParts],
          });
        }
      }
    }
  }

  const upstreamModel = stripModelSuffixes(body.model);
  const instructions = systemInstructions(body);
  const req = {
    model: upstreamModel,
    input,
    store: false,   // stateless — we manage history + encrypted reasoning ourselves
    stream: true,   // xAI streams; non-stream clients collect the SSE
  };

  // Replay (default OFF): ask xAI to return encrypted reasoning so it can
  // round-trip. Never on compact.
  if (!compact && config.replayReasoning) req.include = ['reasoning.encrypted_content'];

  // Stable prompt-cache routing key from Claude Code's session id — xAI strongly
  // recommends it (a cold-server request pays full input price). Routing hint only.
  if (sessionId) req.prompt_cache_key = `claude-grok:${sessionId}`;

  if (compact) {
    req.instructions = [
      instructions || '',
      '',
      'COMPACT MODE (critical): You are summarizing a coding session for another agent.',
      'Respond with ONLY a detailed plain-text summary. No tools. No function calls.',
      'Structure with headings: Goal, Decisions, Files touched, Current state, Errors, Next steps, Constraints.',
      'Be concrete (paths, commands, numbers). Omit boilerplate.',
    ].filter(Boolean).join('\n');
    req.reasoning = { effort: 'low' };
  } else {
    req.instructions = instructions || 'You are a helpful assistant.';
    let effort = normalizeEffort(body.effort)
      || normalizeEffort(body.reasoning_effort)
      || normalizeEffort(body.output_config?.effort)
      || normalizeEffort(body.metadata?.effort)
      || normalizeEffort(body.reasoning?.effort)
      || null;
    let budgetEffort = null;
    if (body.thinking && typeof body.thinking === 'object' && body.thinking.type !== 'disabled' && body.thinking.type !== 'disabled_thinking') {
      budgetEffort = effortFromBudget(body.thinking.budget_tokens);
    }
    if (!effort) effort = budgetEffort || normalizeEffort(config.effort) || 'high';
    // grok-4.5 reasoning cannot be disabled — floor at low. (No summary field;
    // grok auto-exposes reasoning summaries via the stream.)
    req.reasoning = { effort: ALLOWED_EFFORT.has(effort) ? effort : 'low' };
  }

  if (!compact && body.tools?.length) {
    req.tools = body.tools.map((t) => ({
      type: 'function',
      name: t.name,
      description: t.description ?? '',
      parameters: t.input_schema ?? { type: 'object', properties: {} },
      ...(t.strict === true ? { strict: true } : {}),
    }));
    req.tool_choice = convertToolChoice(body.tool_choice);
    req.parallel_tool_calls = body.tool_choice?.disable_parallel_tool_use !== true;
  }

  const clientMaxTokens = Number.isFinite(body.max_tokens) && body.max_tokens > 0 ? body.max_tokens : null;
  const meta = {
    compact,
    showReasoning: config.showReasoning,
    stream: body.stream === true,
    originalModel: originalModel ?? body.model,
    upstreamModel,
    clientMaxTokens,
    effort: req.reasoning?.effort ?? 'low',
    budgetTokens: body.thinking?.budget_tokens ?? null,
  };
  return { req, meta };
}

function convertToolChoice(choice) {
  if (!choice || choice.type === 'auto') return 'auto';
  if (choice.type === 'none') return 'none';
  if (choice.type === 'any') return 'required';
  if (choice.type === 'tool' && choice.name) return { type: 'function', function: { name: choice.name } };
  return 'auto';
}
