// Anthropic Messages → ChatGPT Responses request translation. PURE: returns
// {req, meta} and never mutates the incoming body (v29 smuggled per-request
// state through body.__claudex* magic props; meta replaces that side channel).
//
// Reasoning replay (default ON — config.replayReasoning): sets
// `include: ['reasoning.encrypted_content']` and decodes redacted_thinking
// blocks in history back into Responses reasoning input items, so the backend
// reuses its reasoning KV and the prompt-cache prefix stays byte-stable across
// the agent loop. This runs ALONGSIDE the text mirror (reasoning/mirror.mjs):
// the mirror carries the reasoning SUMMARY to the model as readable text;
// replay carries the ENCRYPTED reasoning to the backend. prompt_cache_key routes
// every turn of a conversation to the same prompt-cache shard. The old L1
// no-replay invariant was retired 2026-07-14 (the power came from the mirror,
// not from dropping replay); opt back into the pure distillation loop with
// CLAUDEX_REPLAY_REASONING=0.
import { createHash } from 'node:crypto';
import { stripModelSuffixes } from '../models/codex-models.mjs';
import { decodeReasoningEnvelope } from '../reasoning/replay.mjs';

const ALLOWED_EFFORT = new Set(['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max']);
const ALLOWED_SUMMARY = new Set(['auto', 'concise', 'detailed', 'none']);

export function normalizeEffort(raw) {
  if (raw == null) return null;
  const s = String(raw).trim().toLowerCase();
  if (!s) return null;
  // Claude / UI labels → Codex wire values
  if (s === 'ultracode' || s === 'ultra') return 'max';
  if (s === 'extra_high' || s === 'extra-high' || s === 'extrahigh') return 'xhigh';
  if (s === 'standard' || s === 'normal') return 'medium';
  if (s === 'light' || s === 'fast') return 'low';
  if (s === 'heavy' || s === 'extended') return 'high';
  if (ALLOWED_EFFORT.has(s)) return s;
  return null;
}

export function normalizeSummary(raw) {
  if (raw == null) return null;
  const s = String(raw).trim().toLowerCase();
  if (ALLOWED_SUMMARY.has(s)) return s;
  if (s === 'full' || s === 'verbose' || s === 'long') return 'detailed';
  if (s === 'short' || s === 'brief') return 'concise';
  if (s === 'off' || s === 'false' || s === '0') return 'none';
  return null;
}

export function effortFromBudget(budget) {
  if (typeof budget !== 'number' || !Number.isFinite(budget)) return null;
  // Align roughly with Claude alwaysThinking + high MAX_THINKING_TOKENS
  if (budget >= 64000) return 'max';
  if (budget >= 32000) return 'xhigh';
  if (budget >= 10000) return 'high';
  if (budget >= 2000) return 'medium';
  return 'low';
}

/** Anthropic image block → Responses input_image content part (base64 data URL or URL). */
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
 * Stable prompt-cache routing key (Codex-parity). Native Codex sends
 * prompt_cache_key = session/thread id so every turn routes to the same cache
 * shard. Claude Code exposes no thread id, so we key on the FIRST user message —
 * stable across the whole conversation and immune to per-turn system-reminder
 * drift (keying on the system prompt would bust the key every turn). Routing
 * hint only; never affects output. Null when there is nothing stable to key on,
 * so the backend falls back to its default prefix-hash routing.
 */
export function stablePromptCacheKey(messages) {
  const firstUser = (messages ?? []).find((m) => m?.role === 'user');
  if (!firstUser) return null;
  const seed = typeof firstUser.content === 'string'
    ? firstUser.content
    : (Array.isArray(firstUser.content)
      ? firstUser.content.filter((b) => b?.type === 'text').map((b) => b.text).join('\n')
      : '');
  if (!seed) return null;
  return `splice-${createHash('sha256').update(seed).digest('hex').slice(0, 32)}`;
}

/**
 * Build the upstream Responses request.
 * body: the Anthropic request (read-only).
 * opts: { compact, config, originalModel } — compact from classifyCompact,
 * config from getConfig(), originalModel the id Claude Code actually sent
 * (possibly discovery-wrapped; echoed back in responses).
 * Returns { req, meta }.
 */
export function buildRequest(body, { compact, config, originalModel }) {
  const input = [];

  // Convert Anthropic messages to Responses API input items
  for (const msg of body.messages ?? []) {
    if (typeof msg.content === 'string') {
      input.push({ role: msg.role, content: msg.content });
      continue;
    }
    for (const block of msg.content ?? []) {
      if (block.type === 'text') {
        input.push({ role: msg.role, content: block.text });
      } else if (block.type === 'image') {
        // v25: images were silently dropped (model gaslit about pasted screenshots).
        if (compact) continue; // compact is a text-only summarizer
        const part = imagePartFromBlock(block);
        if (part) input.push({ role: 'user', content: [part] });
        else input.push({ role: 'user', content: '[image omitted by claudex proxy: unsupported source]' });
      } else if (block.type === 'document') {
        // Honest marker — no reliable document ingestion on this backend path.
        if (!compact) {
          input.push({ role: 'user', content: `[document omitted by claudex proxy: ${block.source?.media_type ?? 'unknown type'}]` });
        }
      } else if (block.type === 'redacted_thinking') {
        // Replay (gated): decode our reasoning envelope back into a Responses
        // reasoning input item, in position (before the tool_use it preceded) so
        // the prompt-cache prefix stays byte-stable. Never on compact; replay-off
        // drops it exactly as the pure amnesia loop did.
        if (compact || !config.replayReasoning) continue;
        const reasoning = decodeReasoningEnvelope(block.data);
        if (reasoning) input.push(reasoning);
      } else if (block.type === 'tool_use') {
        // Compact must stay text-only; skip tool_use items
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
          // Fold tool results into plain user text so the summarizer still sees them
          if (content) input.push({ role: 'user', content: `[tool_result ${block.tool_use_id}] ${content}` });
          continue;
        }
        input.push({
          type: 'function_call_output',
          call_id: block.tool_use_id,
          output: content,
        });
        // v25: images inside tool_result (Read on a PNG, browser screenshots) used to
        // vanish. function_call_output.output is string-only on this backend, so ride
        // them in as a user message right after the output.
        const imageParts = Array.isArray(block.content)
          ? block.content.filter((b) => b?.type === 'image').map(imagePartFromBlock).filter(Boolean)
          : [];
        if (imageParts.length) {
          input.push({
            role: 'user',
            content: [
              { type: 'input_text', text: `[images from tool_result ${block.tool_use_id}]` },
              ...imageParts,
            ],
          });
        }
      }
    }
  }

  // Full fidelity on normal turns: never shrink input, never swap the model.
  // Compaction MUST run on the session's SAME model — empty compactModel (the default)
  // falls through to body.model, which Claude Code sets to the session model. The
  // compaction prefix reuses the session's warm prompt cache ONLY when BOTH the model
  // AND the reasoning effort match the session; a mismatch on EITHER invalidates it and
  // re-reads the whole ~160k transcript cold. Model match is here; effort match is in
  // the reasoning block below. A non-empty compactModel pins a compactor at the cost of
  // that cache — leave it empty.
  const upstreamModel = (compact && config.compactModel)
    ? stripModelSuffixes(config.compactModel)
    : stripModelSuffixes(body.model);
  const instructions = systemInstructions(body);
  const req = {
    model: upstreamModel,
    input,
    store: false, // required by ChatGPT backend
    stream: true, // ChatGPT backend requires stream=true; non-stream clients collect SSE
  };

  // Reasoning replay (Codex-parity, default on): ask the backend to return its
  // encrypted reasoning so it round-trips into the next turn's input (decoded
  // above) and the reasoning KV / prompt-cache prefix survives the agent loop.
  // Never on compact (a text-only summarizer turn). store:false is the required
  // pairing — encrypted_content is the only way to preserve reasoning statelessly.
  if (!compact && config.replayReasoning) req.include = ['reasoning.encrypted_content'];

  // Stable prompt-cache routing key — every turn of one conversation hits the
  // same shard so the cache does not go cold each turn. Routing hint only.
  const cacheKey = stablePromptCacheKey(body.messages);
  if (cacheKey) req.prompt_cache_key = cacheKey;

  if (compact) {
    // Force a real TEXT handoff summary — Claude Code only keeps text blocks.
    // Tools are NOT attached below (stripped upstream): a tooled compaction can
    // answer with tool_use and empty the text channel (the v29 worst case).
    req.instructions = [
      instructions || '',
      '',
      'COMPACT MODE (critical): You are summarizing a coding session for another agent.',
      'Respond with ONLY a detailed plain-text summary. No tools. No function calls.',
      'Do not put the summary only in reasoning — the final message text MUST contain the full summary.',
      'Structure with headings: Goal, Decisions, Files touched, Current state, Errors, Next steps, Constraints.',
      'Be concrete (paths, commands, numbers). Omit boilerplate.',
    ].filter(Boolean).join('\n');
  } else {
    req.instructions = instructions || 'You are a helpful assistant.';
  }

  // Tools → Responses API format (never on compact — tools empty the text channel)
  if (!compact && body.tools?.length) {
    req.tools = body.tools.map((t) => ({
      type: 'function',
      name: t.name,
      description: t.description ?? '',
      parameters: t.input_schema ?? { type: 'object', properties: {} },
    }));
  }

  // Reasoning for the ChatGPT/Codex Responses API. Stable wire shape:
  //   reasoning: { effort: low|medium|high|xhigh|max, summary: auto|concise|detailed|none }
  // Priority (v27): explicit body effort field > Claude /effort picker
  // (thinking.budget_tokens — the harness signal) > config/env fallback > high.
  // summary defaults detailed (reliably fills summary_text; effort alone can
  // leave summary empty). Spark rejects reasoning.summary — omitted there.
  let effort = normalizeEffort(body.effort)
    || normalizeEffort(body.reasoning_effort)
    || normalizeEffort(body.output_config?.effort)
    || normalizeEffort(body.metadata?.effort)
    || normalizeEffort(body.reasoning?.effort)
    || null;

  let summary = normalizeSummary(body.reasoning?.summary)
    || normalizeSummary(body.reasoning_summary)
    || normalizeSummary(body.output_config?.reasoning_summary)
    || null;

  let disabled = false;
  let budgetEffort = null;
  if (body.thinking && typeof body.thinking === 'object') {
    if (body.thinking.type === 'disabled' || body.thinking.type === 'disabled_thinking') {
      disabled = true;
    } else {
      budgetEffort = effortFromBudget(body.thinking.budget_tokens);
    }
  }

  const showReasoning = config.showReasoning;

  // Compaction MUST run at the session's SAME model AND SAME reasoning effort. A
  // different reasoning effort INVALIDATES the prompt cache (established from traces),
  // so the old hardcoded `low` made every compaction re-read the whole ~160k transcript
  // COLD — that is the "compaction ate my subscription" bug. Inherit the session effort
  // here (NO compact special-case); the session-model match is handled at upstreamModel.
  if (!disabled) {
    if (!effort) {
      // Harness-authoritative (v27): the /effort picker (budget) WINS over the
      // config/env fallback; config applies only when there is no thinking budget.
      effort = budgetEffort || normalizeEffort(config.effort) || 'high';
    }
    if (!summary) {
      summary = normalizeSummary(config.summary) || 'detailed';
    }
    // Visibility guarantee, NOT an effort override (v27): ensure at least SOME
    // reasoning so the mirror/thinking UI has content — never raise a
    // deliberate low/medium/high pick.
    if (showReasoning !== 'off') {
      if (!effort || effort === 'minimal' || effort === 'none') effort = 'low';
      if (!summary || summary === 'none' || summary === 'auto' || summary === 'concise') summary = 'detailed';
    }
    // gpt-5.3-codex-spark rejects reasoning.summary (openai/codex#31846)
    if (/spark/i.test(upstreamModel) || summary === 'none') {
      req.reasoning = { effort };
    } else {
      req.reasoning = { effort, summary };
    }
  }

  // ChatGPT backend rejects token limit params — never include max_output_tokens etc.
  const clientMaxTokens = Number.isFinite(body.max_tokens) && body.max_tokens > 0 ? body.max_tokens : null;

  const meta = {
    compact,
    showReasoning,
    stream: body.stream === true,
    originalModel: originalModel ?? body.model,
    upstreamModel,
    clientMaxTokens,
    effort: req.reasoning?.effort ?? 'disabled',
    summary: req.reasoning?.summary ?? 'none',
    budgetTokens: body.thinking?.budget_tokens ?? null,
  };
  return { req, meta };
}
