// Claude OAuth credential read for claudithos (mtime-aware cache; plain
// `claude` sessions keep ~/.claude/.credentials.json refreshed).
import { existsSync, readFileSync, statSync } from 'node:fs';
import { getConfig } from '../config.mjs';

let credCache = { token: null, mtimeMs: 0, path: null };

export function invalidateCreds() {
  credCache = { token: null, mtimeMs: 0, path: null };
}

export function getOauthToken() {
  const credPath = getConfig().claudeCredentialsPath;
  try {
    if (!existsSync(credPath)) return null;
    const st = statSync(credPath);
    if (credCache.token && credCache.path === credPath && credCache.mtimeMs === st.mtimeMs) {
      return credCache.token;
    }
    const j = JSON.parse(readFileSync(credPath, 'utf8'));
    const token = j?.claudeAiOauth?.accessToken ?? null;
    if (token) credCache = { token, mtimeMs: st.mtimeMs, path: credPath };
    return token;
  } catch { return null; }
}

export function credCached() {
  return Boolean(credCache.token);
}

/** Auth introspection for /mgmt/auth — never exposes token material. */
export function describeClaudeAuth() {
  const credPath = getConfig().claudeCredentialsPath;
  const out = { cred_path: credPath, present: false, expires_at: null, cached: credCached() };
  try {
    if (!existsSync(credPath)) return out;
    const j = JSON.parse(readFileSync(credPath, 'utf8'));
    out.present = Boolean(j?.claudeAiOauth?.accessToken);
    out.expires_at = j?.claudeAiOauth?.expiresAt ?? null;
  } catch { /* leave defaults */ }
  return out;
}
