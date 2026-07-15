// Codex CLI OAuth (ChatGPT subscription billing): cached auth.json read +
// single-flight 401 refresh.
import { existsSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { fetch as undiciFetch } from 'undici';
import { getConfig } from '../config.mjs';

// Codex CLI's public OAuth issuer + client id, verified in the local codex
// binary. Reusing the public client id is what lets claudex mint a
// ChatGPT-subscription token itself (see auth/codex-login.mjs). Without refresh,
// an expired access token meant every session 401'd until a manual re-login.
export function codexIssuer() {
  return (process.env.CODEX_OAUTH_ISSUER || 'https://auth.openai.com').replace(/\/$/, '');
}
export function codexTokenUrl() {
  return process.env.CODEX_OAUTH_TOKEN_URL || `${codexIssuer()}/oauth/token`;
}
export function codexAuthorizeUrl() {
  return process.env.CODEX_OAUTH_AUTHORIZE_URL || `${codexIssuer()}/oauth/authorize`;
}
export function codexClientId() {
  return process.env.CODEX_OAUTH_CLIENT_ID || 'app_EMoamEEZ73f0CkXaXp7hrann';
}

let authCache = { token: null, accountId: null, mtimeMs: 0, loadedAt: 0, path: null };

export function invalidateAuthCache() {
  authCache = { token: null, accountId: null, mtimeMs: 0, loadedAt: 0, path: null };
}

/** Resolve auth from Codex CLI OAuth. Returns { mode:'oauth', token, accountId? } | null. */
export function getCodexAuth() {
  const cfg = getConfig();
  const authPath = cfg.codexAuthPath;
  try {
    if (existsSync(authPath)) {
      const st = statSync(authPath);
      const now = Date.now();
      if (
        authCache.token
        && authCache.path === authPath
        && authCache.mtimeMs === st.mtimeMs
        && (now - authCache.loadedAt) < cfg.authCacheMs
      ) {
        return { mode: 'oauth', token: authCache.token, accountId: authCache.accountId };
      }
      const auth = JSON.parse(readFileSync(authPath, 'utf8'));
      if (auth.tokens?.access_token) {
        authCache = {
          token: auth.tokens.access_token,
          accountId: auth.tokens.account_id ?? null,
          mtimeMs: st.mtimeMs,
          loadedAt: now,
          path: authPath,
        };
        return { mode: 'oauth', token: authCache.token, accountId: authCache.accountId };
      }
    }
  } catch { /* fall through */ }
  return null;
}

/** Auth introspection for /mgmt/auth — never exposes token material. */
export function describeCodexAuth() {
  const cfg = getConfig();
  const out = {
    auth_path: cfg.codexAuthPath,
    present: false,
    account_id_masked: null,
    last_refresh: null,
    cached: Boolean(authCache.token),
    cache_age_ms: authCache.loadedAt ? Date.now() - authCache.loadedAt : null,
  };
  try {
    if (!existsSync(cfg.codexAuthPath)) return out;
    const raw = JSON.parse(readFileSync(cfg.codexAuthPath, 'utf8'));
    out.present = Boolean(raw?.tokens?.access_token);
    const acct = String(raw?.tokens?.account_id ?? '');
    out.account_id_masked = acct ? `${acct.slice(0, 4)}…${acct.slice(-4)}` : null;
    out.last_refresh = raw?.last_refresh ?? null;
  } catch { /* leave defaults */ }
  return out;
}

let refreshInflight = null; // single-flight: N concurrent 401s → one refresh

export async function refreshCodexAuth() {
  if (refreshInflight) return refreshInflight;
  refreshInflight = (async () => {
    const authPath = getConfig().codexAuthPath;
    try {
      const raw = JSON.parse(readFileSync(authPath, 'utf8'));
      const refreshToken = raw?.tokens?.refresh_token;
      if (!refreshToken) return null;
      const ac = new AbortController();
      const timer = setTimeout(() => ac.abort(), 30_000);
      let resp;
      try {
        resp = await undiciFetch(codexTokenUrl(), {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({
            grant_type: 'refresh_token',
            refresh_token: refreshToken,
            client_id: codexClientId(),
          }).toString(),
          signal: ac.signal,
        });
      } finally {
        clearTimeout(timer);
      }
      if (!resp.ok) {
        process.stderr.write(`[codex-proxy] token refresh failed ${resp.status}: ${(await resp.text()).slice(0, 200)}\n`);
        return null;
      }
      const tok = await resp.json();
      if (!tok?.access_token) return null;
      raw.tokens = {
        ...raw.tokens,
        access_token: tok.access_token,
        ...(tok.refresh_token ? { refresh_token: tok.refresh_token } : {}),
        ...(tok.id_token ? { id_token: tok.id_token } : {}),
      };
      raw.last_refresh = new Date().toISOString();
      writeFileSync(authPath, JSON.stringify(raw, null, 2), { mode: 0o600 });
      invalidateAuthCache();
      process.stderr.write('[codex-proxy] OAuth token refreshed\n');
      return getCodexAuth();
    } catch (err) {
      process.stderr.write(`[codex-proxy] token refresh error: ${err?.message || err}\n`);
      return null;
    } finally {
      refreshInflight = null;
    }
  })();
  return refreshInflight;
}
