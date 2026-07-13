// ONE upstream-failure classifier for BOTH transports (fixes Eli P0 #2).
//
// v29 had two: anthropicErrorFromUpstream() on the HTTP non-ok path (with the
// "prompt is too long" overflow rewrite) and a separate inline classifier on
// SSE response.failed (without it) — so a live overflow arriving via SSE became
// a raw api_error and Claude Code hard-errored instead of compacting.
//
// Order matters and is a v29 bugfix in itself: v29 tested /auth|unauthorized|token/
// BEFORE the context branch, so any overflow wording containing "tokens"
// ("too many tokens") classified as authentication_error and the rewrite never
// ran even on HTTP. Overflow is now first, and the auth regex no longer matches
// the bare word "token".

const OVERFLOW_RE = /prompt is too long|context.{0,40}window|maximum context|too many tokens|token limit|context_length|exceeds? (?:the )?context/i;
const RATE_RE = /rate.?limit|quota|\b429\b/i;
const AUTH_RE = /\bauth\w*\b|unauthorized|token (?:expired|invalid|revoked)|invalid[_ ]token/i;

/**
 * Classify an upstream failure into an Anthropic-shaped {type, message}.
 * kind: 'http' (non-ok response body text + status) | 'sse' (response.failed /
 * response.error / error event — text is "<code> <message>", status null).
 */
export function classifyUpstreamFailure(kind, text, status = null) {
  let msg = String(text ?? '');
  let code = '';
  if (kind === 'http') {
    try {
      const j = JSON.parse(msg);
      msg = String(j?.error?.message ?? j?.message ?? msg);
      code = String(j?.error?.type ?? j?.error?.code ?? '');
    } catch {
      if (/<html|bad gateway|cloudflare/i.test(msg)) {
        return { type: 'api_error', message: `ChatGPT backend ${status} (gateway)` };
      }
    }
  }
  const blob = `${code} ${msg}`;

  // Overflow FIRST — Claude Code auto-compacts on Anthropic's phrasing; OpenAI's
  // wording won't trigger it, so rewrite to carry "prompt is too long".
  if (OVERFLOW_RE.test(blob)) {
    return {
      type: 'invalid_request_error',
      message: /prompt is too long/i.test(msg) ? msg.slice(0, 2000) : `prompt is too long: ${msg}`.slice(0, 2000),
    };
  }
  if (status === 429 || RATE_RE.test(blob)) return { type: 'rate_limit_error', message: msg.slice(0, 2000) };
  if (status === 401 || AUTH_RE.test(blob)) return { type: 'authentication_error', message: msg.slice(0, 2000) };
  if (status != null && status >= 500) return { type: 'api_error', message: msg.slice(0, 2000) };
  if (status != null && status >= 400) return { type: 'invalid_request_error', message: msg.slice(0, 2000) };
  return { type: 'api_error', message: msg.slice(0, 2000) };
}

/** Anthropic error body for the HTTP path. */
export function anthropicErrorBody(status, errText) {
  return { type: 'error', error: classifyUpstreamFailure('http', errText, status) };
}

/** 502 from the ChatGPT gateway is transient — surface as 529 so Claude Code retries. */
export function mapOutStatus(status) {
  return status === 502 ? 529 : status;
}
