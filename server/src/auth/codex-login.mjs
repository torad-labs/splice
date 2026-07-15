#!/usr/bin/env node
// Codex "Sign in with ChatGPT" — OAuth 2.0 Authorization Code + PKCE against
// auth.openai.com using the PUBLIC codex CLI client id. Reusing that client id
// (registered with the http://localhost:1455/auth/callback redirect) is what
// lets `claudex login` mint a ChatGPT-subscription-scoped token itself, with no
// separate `codex` CLI. Writes ~/.codex/auth.json in the exact shape
// codex-oauth.mjs reads (and the real codex CLI reads) — so `codex` and
// `claudex` share ONE credential and the existing refresh path just works.
//
// The param order + %20 encoding below are matched to the codex CLI on purpose:
// URLSearchParams emits `+` for spaces, which the authorize server reads as a
// literal `+`, mangling the scope into `missing_required_parameter`.
import http from 'node:http';
import { createHash, randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { fetch as undiciFetch } from 'undici';
import { getConfig } from '../config.mjs';
import { codexAuthorizeUrl, codexClientId, codexTokenUrl, invalidateAuthCache } from './codex-oauth.mjs';

const REDIRECT_PORT = 1455; // the port the public client id is registered with
const REDIRECT_URI = `http://localhost:${REDIRECT_PORT}/auth/callback`;
const SCOPE = 'openid profile email offline_access api.connectors.read api.connectors.invoke';
const ORIGINATOR = process.env.CODEX_OAUTH_ORIGINATOR || 'codex_cli_rs';

function base64url(buf) {
  return Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function makePkce() {
  const verifier = base64url(randomBytes(32));
  const challenge = base64url(createHash('sha256').update(verifier).digest());
  return { verifier, challenge };
}

/** Authorize URL with the codex-CLI param set, order, and %20 (not +) encoding. */
export function buildAuthorizeUrl({ challenge, state, clientId = codexClientId(), redirectUri = REDIRECT_URI }) {
  const params = [
    ['response_type', 'code'],
    ['client_id', clientId],
    ['redirect_uri', redirectUri],
    ['scope', SCOPE],
    ['code_challenge', challenge],
    ['code_challenge_method', 'S256'],
    ['id_token_add_organizations', 'true'],
    ['codex_cli_simplified_flow', 'true'],
    ['state', state],
    ['originator', ORIGINATOR],
  ];
  return `${codexAuthorizeUrl()}?${params.map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&')}`;
}

export function decodeJwtClaims(jwt) {
  try {
    const payload = String(jwt || '').split('.')[1];
    if (!payload) return {};
    return JSON.parse(Buffer.from(payload.replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8'));
  } catch {
    return {};
  }
}

export function accountIdFromIdToken(idToken) {
  return decodeJwtClaims(idToken)?.['https://api.openai.com/auth']?.chatgpt_account_id ?? null;
}

/** Token strings → the ~/.codex/auth.json object codex-oauth.mjs consumes. */
export function authJsonFromTokens(tokens, { apiKey = null } = {}) {
  const accountId = accountIdFromIdToken(tokens.id_token);
  return {
    ...(apiKey ? { OPENAI_API_KEY: apiKey } : {}),
    tokens: {
      id_token: tokens.id_token,
      access_token: tokens.access_token,
      refresh_token: tokens.refresh_token,
      ...(accountId ? { account_id: accountId } : {}),
    },
    last_refresh: new Date().toISOString(),
  };
}

function writeAuth(authPath, data) {
  const dir = dirname(authPath);
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true, mode: 0o700 });
  writeFileSync(authPath, `${JSON.stringify(data, null, 2)}\n`, { mode: 0o600 });
}

async function exchangeCode({ code, verifier, clientId, redirectUri }) {
  const resp = await undiciFetch(codexTokenUrl(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      code,
      redirect_uri: redirectUri,
      client_id: clientId,
      code_verifier: verifier,
    }).toString(),
  });
  if (!resp.ok) throw new Error(`token exchange failed ${resp.status}: ${(await resp.text()).slice(0, 300)}`);
  const tok = await resp.json();
  if (!tok?.access_token) throw new Error('token response missing access_token');
  return tok;
}

/** Best-effort API-key token-exchange (what the real codex CLI stores too) so
 * the auth.json is a drop-in for `codex`. Never fails the login. */
async function obtainApiKey({ idToken, clientId }) {
  try {
    const resp = await undiciFetch(codexTokenUrl(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'urn:ietf:params:oauth:grant-type:token-exchange',
        client_id: clientId,
        requested_token: 'openai-api-key',
        subject_token: idToken,
        subject_token_type: 'urn:ietf:params:oauth:token-type:id_token',
      }).toString(),
    });
    return resp.ok ? (await resp.json())?.access_token ?? null : null;
  } catch {
    return null;
  }
}

function tryOpenBrowser(url) {
  const cmd = process.platform === 'darwin' ? 'open' : process.platform === 'win32' ? 'cmd' : 'xdg-open';
  const args = process.platform === 'win32' ? ['/c', 'start', '', url] : [url];
  try {
    spawn(cmd, args, { stdio: 'ignore', detached: true }).unref();
    return true;
  } catch {
    return false;
  }
}

/**
 * Run the interactive browser login and persist ~/.codex/auth.json.
 * Returns { authPath, accountId, email }.
 */
export async function loginCodex({ authPath = getConfig().codexAuthPath, openBrowser = true, timeoutMs = 300_000 } = {}) {
  const clientId = codexClientId();
  const { verifier, challenge } = makePkce();
  const state = base64url(randomBytes(32));
  const authorizeUrl = buildAuthorizeUrl({ challenge, state, clientId });

  const server = http.createServer();
  const codePromise = new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      try { server.close(); } catch { /* ignore */ }
      reject(new Error('login timed out after 5 min'));
    }, timeoutMs);
    const finish = (res, ok, msg) => {
      res.writeHead(ok ? 200 : 400, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(`<!doctype html><meta charset=utf-8><body style="font-family:system-ui;max-width:32rem;margin:4rem auto;padding:0 1rem">`
        + `<h2>${ok ? 'Signed in to ChatGPT ✓' : 'Sign-in failed'}</h2><p>${msg}</p>`
        + `<p style="color:#888">You can close this tab and return to the terminal.</p></body>`);
      clearTimeout(timer);
      try { server.close(); } catch { /* ignore */ }
    };
    server.on('request', (req, res) => {
      let u;
      try { u = new URL(req.url, REDIRECT_URI); } catch { res.writeHead(400); res.end('bad request'); return; }
      if (u.pathname !== '/auth/callback') { res.writeHead(404); res.end('not found'); return; }
      const err = u.searchParams.get('error');
      const code = u.searchParams.get('code');
      const returnedState = u.searchParams.get('state');
      if (err) { finish(res, false, `${err}: ${u.searchParams.get('error_description') || ''}`); reject(new Error(`OAuth error: ${err}`)); return; }
      if (returnedState !== state) { finish(res, false, 'state mismatch (possible CSRF)'); reject(new Error('state mismatch')); return; }
      if (!code) { finish(res, false, 'no authorization code returned'); reject(new Error('no code in callback')); return; }
      finish(res, true, 'claudex is now authenticated with your ChatGPT account.');
      resolve(code);
    });
    server.on('error', (e) => {
      clearTimeout(timer);
      reject(e.code === 'EADDRINUSE'
        ? new Error(`port ${REDIRECT_PORT} is in use — another login flow may be running`)
        : e);
    });
    server.listen(REDIRECT_PORT, '127.0.0.1');
  });

  process.stderr.write(`\nclaudex login — Sign in with ChatGPT\n\n  ${authorizeUrl}\n\n`);
  if (openBrowser && tryOpenBrowser(authorizeUrl)) process.stderr.write('Opened your browser. Complete sign-in there, then return here.\n');
  else process.stderr.write('Open the URL above in a browser to continue.\n');
  process.stderr.write('Waiting for the callback…\n');

  const code = await codePromise;
  const tokens = await exchangeCode({ code, verifier, clientId, redirectUri: REDIRECT_URI });
  const apiKey = await obtainApiKey({ idToken: tokens.id_token, clientId });
  const data = authJsonFromTokens(tokens, { apiKey });
  writeAuth(authPath, data);
  invalidateAuthCache();
  return { authPath, accountId: data.tokens.account_id ?? null, email: decodeJwtClaims(tokens.id_token)?.email ?? null };
}

async function main() {
  try {
    const res = await loginCodex({ openBrowser: !process.argv.includes('--no-browser') });
    process.stderr.write(`\n✓ Signed in${res.email ? ` as ${res.email}` : ''} — credentials written to ${res.authPath}\n  Run \`claudex\` to start.\n`);
    process.exit(0);
  } catch (err) {
    process.stderr.write(`\n✗ Login failed: ${err?.message || err}\n`);
    process.exit(1);
  }
}

if (process.argv[1] && process.argv[1] === fileURLToPath(import.meta.url)) main();
