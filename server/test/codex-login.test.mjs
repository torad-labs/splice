#!/usr/bin/env node
// Codex "Sign in with ChatGPT" login: PKCE, the authorize-URL contract (the
// %20-vs-+ encoding that the authorize server is strict about), id_token claim
// extraction, and — the load-bearing one — that the auth.json login writes is
// read back byte-for-byte by the proxy's getCodexAuth.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';

const {
  makePkce,
  buildAuthorizeUrl,
  decodeJwtClaims,
  accountIdFromIdToken,
  authJsonFromTokens,
} = await import('../src/auth/codex-login.mjs');

const b64url = (s) => Buffer.from(s).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const fakeIdToken = (claims) => `${b64url(JSON.stringify({ alg: 'RS256' }))}.${b64url(JSON.stringify(claims))}.sig`;

test('makePkce: challenge is base64url(sha256(verifier)), S256-shaped', () => {
  const { verifier, challenge } = makePkce();
  assert.ok(/^[A-Za-z0-9_-]{43,}$/.test(verifier), 'verifier is base64url, >= 43 chars');
  const expected = createHash('sha256').update(verifier).digest('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  assert.equal(challenge, expected);
});

test('buildAuthorizeUrl: codex param set + order, %20 (never +) scope encoding', () => {
  const url = buildAuthorizeUrl({ challenge: 'CH', state: 'ST', clientId: 'app_test' });
  assert.ok(url.startsWith('https://auth.openai.com/oauth/authorize?'));
  const qs = url.split('?')[1];
  assert.ok(qs.startsWith('response_type=code&client_id=app_test&'), 'order: response_type, client_id first');
  assert.ok(qs.endsWith('&state=ST&originator=codex_cli_rs'), 'order: state then originator last');
  assert.ok(url.includes('code_challenge=CH&code_challenge_method=S256'));
  assert.ok(url.includes('codex_cli_simplified_flow=true') && url.includes('id_token_add_organizations=true'));
  assert.ok(url.includes('redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback'));
  // The load-bearing encoding: spaces in scope MUST be %20, never + (the
  // authorize server reads + literally and rejects the scope).
  assert.ok(url.includes('scope=openid%20profile%20email%20offline_access%20api.connectors.read%20api.connectors.invoke'));
  assert.ok(!/scope=[^&]*\+/.test(url), 'scope must contain no +');
});

test('accountIdFromIdToken / decodeJwtClaims: pull chatgpt_account_id + email', () => {
  const idToken = fakeIdToken({ email: 'me@x.dev', 'https://api.openai.com/auth': { chatgpt_account_id: 'acc-123' } });
  assert.equal(accountIdFromIdToken(idToken), 'acc-123');
  assert.equal(decodeJwtClaims(idToken).email, 'me@x.dev');
  assert.equal(accountIdFromIdToken('not-a-jwt'), null, 'garbage → null, never throws');
  assert.deepEqual(decodeJwtClaims(''), {});
});

test('authJsonFromTokens: the ~/.codex/auth.json shape (with optional api key)', () => {
  const idToken = fakeIdToken({ 'https://api.openai.com/auth': { chatgpt_account_id: 'acc-9' } });
  const data = authJsonFromTokens({ id_token: idToken, access_token: 'AT', refresh_token: 'RT' }, { apiKey: 'sk-x' });
  assert.equal(data.tokens.access_token, 'AT');
  assert.equal(data.tokens.refresh_token, 'RT');
  assert.equal(data.tokens.id_token, idToken);
  assert.equal(data.tokens.account_id, 'acc-9');
  assert.equal(data.OPENAI_API_KEY, 'sk-x');
  assert.ok(data.last_refresh);
  // No api key → field omitted, not null
  assert.ok(!('OPENAI_API_KEY' in authJsonFromTokens({ id_token: idToken, access_token: 'A', refresh_token: 'R' })));
});

test('round-trip: login output is read back by the proxy getCodexAuth path', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'splice-login-'));
  const authPath = join(dir, 'auth.json');
  process.env.CLAUDEX_STATE_DIR = join(dir, 'state');
  process.env.CODEX_AUTH_PATH = authPath;
  const { getConfig } = await import('../src/config.mjs');
  const { getCodexAuth, invalidateAuthCache } = await import('../src/auth/codex-oauth.mjs');

  const idToken = fakeIdToken({ 'https://api.openai.com/auth': { chatgpt_account_id: 'acc-rt' } });
  const data = authJsonFromTokens({ id_token: idToken, access_token: 'AT-rt', refresh_token: 'RT-rt' });
  writeFileSync(authPath, JSON.stringify(data));
  invalidateAuthCache();

  assert.equal(getConfig().codexAuthPath, authPath, 'CODEX_AUTH_PATH flows into config');
  const auth = getCodexAuth();
  assert.ok(auth && auth.mode === 'oauth', 'proxy resolves the login-written auth');
  assert.equal(auth.token, 'AT-rt');
  assert.equal(auth.accountId, 'acc-rt', 'account_id becomes the ChatGPT-Account-ID header source');
});
