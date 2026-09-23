// The live stack the console E2E runs against (FEATURES.md 7.5: "Playwright runs against a live
// daemon on loopback"). One isolated daemon booted from the jar the gate just built, one recording
// mock upstream, and one REAL turn through a real head — so every page reads payloads the daemon
// itself wrote, never a fixture authored to match the console's own types (the V4-140 defect class:
// the turns page 400ed on every poll for its whole life while every fixture-fed test was green).
//
// WHAT THE STACK HOLDS, and why each piece is there:
//   - an OAuth head (chatgpt-oauth, a dummy auth file whose JWT expires in 2100, so the provider never
//     refreshes) pointed at the mock, with a second labeled account beside the primary so it builds
//     an account POOL. The mock answers the turn with the x-codex-* quota headers, so the account the
//     turn rode has REAL windows and a plan.
//   - a second OAuth head with ONE login: no pool, so /api/accounts reports it as a single-login row
//     with a null label and null windows, the shape most of the operator's live accounts have.
//   - an api-key head whose key env var is absent: a head that is configured but cannot authenticate,
//     the honest-empty state every page has to render without inventing numbers.
//   - one turn through the OAuth head, driven through the head's own Anthropic endpoint, so the perf
//     writer (PerfStats.record) writes the row /api/perf/turns serves.
//   - a git repository with a CLAUDE.md, and two Claude Code session registrations working in it
//     (~/.claude/sessions/*.json, the files SessionRegistry reads), both on this process's own live
//     pid: the repository becomes a project (/api/projects), the two sessions become rows of
//     /api/sessions, and a SECOND turn, sent by one session and carrying its SendMessage call to the
//     other's address, becomes the message edge /api/sessions/edges reports (MessageEdges reads it
//     off the request on the wire). The same turn is what /api/projects/{id} counts as today's.
//   - a [compaction] table with one rule of each configured kind (global, model, project), so
//     /api/compaction/instructions reports three sources with lengths the spec knows.
// Nothing leaves loopback: the mock is the only upstream, quota polling is off (CLAUDEX_QUOTA_POLL),
// and the environment carries no provider credential, DISPLAY or DBUS address.
//
// A STACK THAT DID NOT START IS A FAILURE, NEVER A SKIP (web-console law 23): a missing jar, a daemon
// that never answers, a turn that fails or a row that never lands each throws with the daemon log's
// tail, and Playwright reports the setup as the failure.
import { spawn, type ChildProcess } from 'node:child_process';
import { copyFileSync, createWriteStream, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { createServer, type Server } from 'node:http';
import { createServer as createNetServer, type AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const DEFAULT_JAR = join(REPO, 'app/build/libs/app-all.jar');
const BOOT_TIMEOUT_MS = 90_000;
const LANDING_TIMEOUT_MS = 20_000;

/** The names the spec asserts on. Exported so a rename here is a rename there. */
export const STACK = {
  oauthHead: 'e2e-codex',
  /** The second account in the OAuth head's pool; two accounts is what makes it a pool
   *  (HeadAccountPools.pooled: accounts.size > 1). */
  poolLabel: 'work',
  /** An OAuth head with one login and no pool: /api/accounts reports it as a single-login row whose
   *  label, flags and windows are all null, the shape the live daemon sends for most accounts. */
  soloHead: 'e2e-codex-solo',
  keyHead: 'e2e-openrouter',
  model: 'e2e-model',
  /** The solo head's own model, so a model id names exactly one head. */
  soloModel: 'e2e-solo-model',
  /** The OAuth head's forced head-wide window, set in the topology and nowhere in /api/models: the
   *  models page's detail must read it from GET /api/topology. */
  headWindow: 300000,
  /** The five-hour percent the mock reports on the turn; the accounts page must print it. */
  fiveHourUsedPercent: 42,
  sevenDayUsedPercent: 7,
  plan: 'plus',
  /** The session that sends the hand-off, and the one it sends it to. Both are registered in the
   *  repository below, so the project counts them as its live sessions. */
  sender: { id: 'e2e5e11d-0000-4000-8000-000000000001', name: 'e2e-sender' },
  peer: { id: 'e2e9ee12-0000-4000-8000-000000000002', name: 'e2e-peer' },
  /** The compaction rules the topology declares, one per configured kind. Their lengths are what
   *  /api/compaction/instructions reports as `chars`. */
  compactGlobal: 'keep the decisions and the open questions',
  compactModel: 'e2e model: keep every file path',
  compactProject: 'e2e repo: keep the ledger rows named',
} as const;

export interface Stack {
  base: string;
  key: string;
  /** The OAuth head's own port, so a journey can drive a turn through it. */
  oauthPort: number;
  /** splice.toml as the daemon booted it, so a journey can read what a console write put there. */
  configFile: string;
  /** The repository's real path, which is the project id /api/projects reports. */
  repo: string;
  stop: () => Promise<void>;
}

/** Which console bundle the daemon serves. `jar` (the default, and the gate's) is the copy the jar
 *  embeds, so the suite judges the artifact that ships. `dist` serves console/dist/index.html
 *  through the daemon's own dev lookup (Main.kt: `<user.dir>/../console/dist/index.html` first),
 *  so a local run can judge a fresh `vite build` without rebuilding the jar. */
function bundleMode(): 'jar' | 'dist' {
  const mode = process.env.CONSOLE_E2E_BUNDLE ?? 'jar';
  if (mode !== 'jar' && mode !== 'dist') throw new Error(`CONSOLE_E2E_BUNDLE must be jar or dist, got '${mode}'`);
  return mode;
}

async function freePort(): Promise<number> {
  const server = createNetServer();
  await new Promise<void>((ok) => server.listen(0, '127.0.0.1', ok));
  const { port } = server.address() as AddressInfo;
  await new Promise<void>((ok) => server.close(() => ok()));
  return port;
}

/** A ChatGPT auth file the Codex provider accepts without refreshing: it reads the expiry from the
 *  access token's own `exp` claim (the heads harness's probeAuthJson, tools/e2e). */
function dummyAuth(account: string): Record<string, unknown> {
  const part = (value: unknown): string => Buffer.from(JSON.stringify(value)).toString('base64url');
  const jwt = `${part({ alg: 'none' })}.${part({ exp: 4102444800 })}.console-e2e`;
  return {
    tokens: { access_token: jwt, refresh_token: 'rt_console_e2e', account_id: account },
    last_refresh: '2026-01-01T00:00:00Z',
  };
}

/** A labeled pool account: the provider's own credential plus the two fields splice stamps on a
 *  pooled file (OAuthAccountValidation: the kind, and a label equal to the file name), at the path
 *  OAuthAccountFiles.poolDir derives from the primary: `<dir>/<kind>/<primary file name>/`. */
function writePoolAccount(primary: string, label: string): void {
  const dir = join(dirname(primary), 'chatgpt-oauth', basename(primary));
  mkdirSync(dir, { recursive: true });
  writeFileSync(
    join(dir, `${label}.json`),
    JSON.stringify({ ...dummyAuth(`acct_${label}`), splice_auth_kind: 'chatgpt-oauth', splice_account_label: label }),
  );
}

/** The Responses-API stream for one plain text answer, with the Codex quota headers the provider
 *  files into the account's two windows (CodexQuotaHeaderFamily). */
function startMockUpstream(port: number, record: string[]): Promise<Server> {
  const server = createServer((req, res) => {
    record.push(`${req.method ?? '?'} ${req.url ?? '?'}`);
    req.resume();
    req.on('end', () => {
      if (req.method !== 'POST' || !(req.url ?? '').endsWith('/responses')) {
        res.writeHead(404, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ error: { message: `console e2e mock: no route for ${req.method} ${req.url}` } }));
        return;
      }
      const events = [
        { type: 'response.output_item.added', output_index: 0, item: { type: 'message' } },
        { type: 'response.output_text.delta', output_index: 0, delta: 'console e2e answer' },
        { type: 'response.completed', response: { usage: { input_tokens: 12, output_tokens: 4 } } },
      ];
      res.writeHead(200, {
        'content-type': 'text/event-stream',
        'x-codex-plan-type': STACK.plan,
        'x-codex-primary-used-percent': String(STACK.fiveHourUsedPercent),
        'x-codex-primary-window-minutes': '300',
        'x-codex-primary-reset-after-seconds': '3600',
        'x-codex-secondary-used-percent': String(STACK.sevenDayUsedPercent),
        'x-codex-secondary-window-minutes': '10080',
        'x-codex-secondary-reset-after-seconds': '86400',
      });
      res.end(events.map((e) => `event: ${e.type}\ndata: ${JSON.stringify(e)}\n\n`).join(''));
    });
  });
  return new Promise((ok, fail) => {
    server.once('error', fail);
    server.listen(port, '127.0.0.1', () => ok(server));
  });
}

function config(ports: { control: number; mock: number; oauth: number; solo: number; key: number }, authFiles: { pooled: string; solo: string }, repo: string): string {
  return [
    '[daemon]',
    `control_port = ${ports.control}`,
    '',
    '[providers.codex]',
    'dialect = "openai-responses"',
    `base_url = "http://127.0.0.1:${ports.mock}"`,
    `auth = { kind = "chatgpt-oauth", file = "${authFiles.pooled}" }`,
    '',
    '[[providers.codex.models]]',
    `id = "${STACK.model}"`,
    'label = "E2E Model"',
    'context_window = 400000',
    '',
    `[heads.${STACK.oauthHead}]`,
    'provider = "codex"',
    `port = ${ports.oauth}`,
    'discovery_prefix = "claude-e2e--"',
    `pinned_model = "${STACK.model}"`,
    `context_window = ${STACK.headWindow}`,
    '',
    '[providers.codexsolo]',
    'dialect = "openai-responses"',
    `base_url = "http://127.0.0.1:${ports.mock}"`,
    `auth = { kind = "chatgpt-oauth", file = "${authFiles.solo}" }`,
    '',
    '[[providers.codexsolo.models]]',
    `id = "${STACK.soloModel}"`,
    'label = "E2E Solo Model"',
    'context_window = 400000',
    '',
    `[heads.${STACK.soloHead}]`,
    'provider = "codexsolo"',
    `port = ${ports.solo}`,
    'discovery_prefix = "claude-e2e-solo--"',
    `pinned_model = "${STACK.soloModel}"`,
    '',
    '[providers.openrouter]',
    'dialect = "openai-chat"',
    'base_url = "https://openrouter.invalid/api/v1"',
    'auth = { kind = "api-key", env = "CONSOLE_E2E_NO_SUCH_KEY" }',
    '',
    '[[providers.openrouter.models]]',
    'id = "e2e/key-model"',
    'label = "E2E Key Model"',
    'context_window = 200000',
    '',
    `[heads.${STACK.keyHead}]`,
    'provider = "openrouter"',
    `port = ${ports.key}`,
    'discovery_prefix = "claude-e2e-key--"',
    'pinned_model = "e2e/key-model"',
    'models = [{ id = "e2e/key-model", slot = "sonnet" }]',
    '',
    '[compaction]',
    `instructions = "${STACK.compactGlobal}"`,
    '',
    '[[compaction.model]]',
    `model = "${STACK.model}"`,
    `instructions = "${STACK.compactModel}"`,
    '',
    '[[compaction.project]]',
    `path = "${repo}"`,
    `instructions = "${STACK.compactProject}"`,
    '',
  ].join('\n');
}

/** One Claude Code registration file, in the fields SessionRegistry reads. The pid is THIS process,
 *  which is alive for the whole run, so the registry reports the session live; the file is named
 *  after the session rather than the pid because two sessions share it. */
function registerSession(dir: string, session: { id: string; name: string }, cwd: string, socket: string): void {
  mkdirSync(dir, { recursive: true });
  const now = Date.now();
  writeFileSync(
    join(dir, `${session.name}.json`),
    JSON.stringify({
      pid: process.pid,
      sessionId: session.id,
      cwd,
      name: session.name,
      kind: 'interactive',
      status: 'idle',
      startedAt: now,
      updatedAt: now,
      messagingSocketPath: socket,
    }),
  );
}

async function answers(url: string): Promise<boolean> {
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(2_000) });
    return res.status < 500;
  } catch {
    return false;
  }
}

function tail(file: string): string {
  if (!existsSync(file)) return '(no daemon log)';
  return readFileSync(file, 'utf8').split('\n').slice(-40).join('\n');
}

/** Polls `probe` until it returns a value or the deadline passes; the failure names what it waited for. */
async function until<T>(what: string, timeoutMs: number, probe: () => Promise<T | null>): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await probe();
    if (value !== null) return value;
    if (Date.now() > deadline) throw new Error(`console e2e: timed out waiting for ${what}`);
    await new Promise((ok) => setTimeout(ok, 250));
  }
}

/** The prompt of the plain turn. Exported so a journey can assert the console never prints it: no
 *  route serves a turn's body, so the text must not reach the page from anywhere. */
export const TURN_PROMPT = 'one turn so the console has a row';

async function postTurn(headPort: number, key: string, body: unknown, headers: Record<string, string> = {}): Promise<void> {
  const res = await fetch(`http://127.0.0.1:${headPort}/v1/messages`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'anthropic-version': '2023-06-01', 'x-api-key': key, ...headers },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(60_000),
  });
  const text = await res.text();
  if (res.status !== 200 || !text.includes('message_stop')) {
    throw new Error(`console e2e: the turn through ${STACK.oauthHead} failed (${res.status}): ${text.slice(0, 400)}`);
  }
}

/** One plain turn through a head. Exported: a journey drives its own turn after a console write.
 *  With `sessionId` the turn carries the client's session header, so its perf row is tagged to it. */
export async function driveOneTurn(headPort: number, key: string, sessionId?: string): Promise<void> {
  await postTurn(
    headPort,
    key,
    {
      model: STACK.model,
      max_tokens: 64,
      stream: true,
      messages: [{ role: 'user', content: TURN_PROMPT }],
    },
    sessionId === undefined ? {} : { 'x-claude-code-session-id': sessionId },
  );
}

/** The sender's turn after its SendMessage call: the call is the last assistant message of the
 *  history, which is exactly where MessageEdges looks for it, and the session header names the
 *  sender. The daemon records the edge; the text of the message never leaves the request. */
async function sendHandOff(headPort: number, key: string, toAddress: string): Promise<void> {
  const call = 'toolu_console_e2e_handoff';
  await postTurn(
    headPort,
    key,
    {
      model: STACK.model,
      max_tokens: 64,
      stream: true,
      tools: [{
        name: 'SendMessage',
        description: 'send a message to another session',
        input_schema: { type: 'object', properties: { to: { type: 'string' }, message: { type: 'string' } } },
      }],
      messages: [
        { role: 'user', content: 'hand the review to the peer session' },
        { role: 'assistant', content: [{ type: 'tool_use', id: call, name: 'SendMessage', input: { to: toAddress, message: 'review ready' } }] },
        { role: 'user', content: [{ type: 'tool_result', tool_use_id: call, content: 'sent' }] },
      ],
    },
    { 'x-claude-code-session-id': STACK.sender.id },
  );
}

export async function startStack(): Promise<Stack> {
  const jar = process.env.CONSOLE_E2E_JAR ?? DEFAULT_JAR;
  if (!existsSync(jar)) {
    throw new Error(`console e2e: no jar at ${jar} — build it with ./gradlew :app:shadowJar (the gate leg depends on it)`);
  }
  const mode = bundleMode();
  if (mode === 'dist' && !existsSync(join(REPO, 'console/dist/index.html'))) {
    throw new Error('console e2e: CONSOLE_E2E_BUNDLE=dist but console/dist/index.html is missing — run the console build first');
  }

  const home = mkdtempSync(join(tmpdir(), 'console-e2e-'));
  const ports = { control: await freePort(), mock: await freePort(), oauth: await freePort(), solo: await freePort(), key: await freePort() };
  const authFiles = { pooled: join(home, 'codex/auth.json'), solo: join(home, 'codex-solo/auth.json') };
  mkdirSync(dirname(authFiles.pooled), { recursive: true });
  mkdirSync(dirname(authFiles.solo), { recursive: true });
  writeFileSync(authFiles.pooled, JSON.stringify(dummyAuth('acct_console_e2e')));
  writeFileSync(authFiles.solo, JSON.stringify(dummyAuth('acct_console_e2e_solo')));
  writePoolAccount(authFiles.pooled, STACK.poolLabel);
  // A `.git` DIRECTORY is what RepoResolver reads as a main checkout; the path is resolved the way
  // the daemon resolves it (toRealPath), so the id the spec expects is the id the daemon reports.
  mkdirSync(join(home, 'e2e-repo/.git'), { recursive: true });
  const repo = realpathSync(join(home, 'e2e-repo'));
  writeFileSync(join(repo, 'CLAUDE.md'), '# e2e repo\n\nThe console e2e project.\n');
  const peerAddress = `uds:${join(home, 'e2e-peer.sock')}`;
  const sessionsDir = join(home, '.claude/sessions');
  registerSession(sessionsDir, STACK.sender, repo, join(home, 'e2e-sender.sock'));
  registerSession(sessionsDir, STACK.peer, repo, peerAddress.slice('uds:'.length));
  const configFile = join(home, '.config/splice/splice.toml');
  mkdirSync(dirname(configFile), { recursive: true });
  writeFileSync(configFile, config(ports, authFiles, repo));

  const upstream: string[] = [];
  const mock = await startMockUpstream(ports.mock, upstream);
  const log = join(home, 'daemon.log');
  const logStream = createWriteStream(log);
  // In `jar` mode the daemon runs from a directory with no ../console/dist beside it, so the only
  // bundle it can serve is the jar's own.
  const cwd = mode === 'dist' ? join(REPO, 'app') : join(home, 'run');
  mkdirSync(cwd, { recursive: true });
  // The daemon runs a PRIVATE COPY of the jar. app/build/libs/app-all.jar is a shared path any build
  // in this worktree rewrites, and a JVM loads classes from its jar lazily: on 2026-09-23 another
  // session's shadowJar replaced it at 10:53:18 mid-run, and every route not yet loaded answered
  // 500 from then on (12 journeys red; the same tree was 28/28 on the rerun).
  const runJar = join(home, 'splice.jar');
  copyFileSync(jar, runJar);
  const child: ChildProcess = spawn('java', ['-Xmx512m', '-Dsplice.noSystemBrowser=1', `-Duser.home=${home}`, '-jar', runJar, 'daemon'], {
    cwd,
    env: {
      PATH: process.env.PATH ?? '/usr/bin:/bin',
      HOME: home,
      XDG_CONFIG_HOME: join(home, '.config'),
      SPLICE_CONFIG: configFile,
      SPLICE_CONTROL_PORT: String(ports.control),
      CLAUDEX_QUOTA_POLL: 'off',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stdout?.pipe(logStream);
  child.stderr?.pipe(logStream);

  const base = `http://127.0.0.1:${ports.control}`;
  const stop = async (): Promise<void> => {
    if (child.exitCode === null) {
      child.kill('SIGTERM');
      const deadline = Date.now() + 20_000;
      while (child.exitCode === null && Date.now() < deadline) await new Promise((ok) => setTimeout(ok, 100));
      if (child.exitCode === null) child.kill('SIGKILL');
    }
    await new Promise<void>((ok) => mock.close(() => ok()));
    // A route that threw is logged by the daemon (RouteFailure) and nowhere else, and the home is
    // about to go: the lines reach the run's own output, so a journey's 500 names its cause.
    const failures = readFileSync(log, 'utf8').split('\n').filter((line) => /\[control\] \S+ \S+ failed: /.test(line));
    if (failures.length > 0) console.log(`console e2e: the daemon answered route failures:\n${failures.join('\n')}`);
    if (process.env.CONSOLE_E2E_KEEP === undefined) rmSync(home, { recursive: true, force: true });
    else console.log(`console e2e: kept ${home}`);
  };

  try {
    await until(`${base}/health`, BOOT_TIMEOUT_MS, async () => {
      if (child.exitCode !== null) throw new Error(`console e2e: the daemon exited ${child.exitCode} during boot`);
      return (await answers(`${base}/health`)) ? true : null;
    });
    // A daemon booted into a fresh HOME writes the current state layout (V4-177).
    const key = readFileSync(join(home, '.splice/state/mgmt-key'), 'utf8').trim();
    const read = async (path: string): Promise<unknown> => {
      const res = await fetch(`${base}${path}`, { headers: { Authorization: `Bearer ${key}` } });
      return res.ok ? res.json() : null;
    };

    await until(`head ${STACK.oauthHead} on :${ports.oauth}`, BOOT_TIMEOUT_MS, async () =>
      (await answers(`http://127.0.0.1:${ports.oauth}/v1/models`)) ? true : null,
    );
    await driveOneTurn(ports.oauth, key);
    await until(`the turn's perf row on ${STACK.oauthHead}`, LANDING_TIMEOUT_MS, async () => {
      const body = (await read(`/api/perf/turns?head=${STACK.oauthHead}`)) as { heads?: { rows?: unknown[] }[] } | null;
      return (body?.heads?.[0]?.rows?.length ?? 0) > 0 ? true : null;
    });
    await until('the account windows the turn reported', LANDING_TIMEOUT_MS, async () => {
      const body = (await read('/api/accounts')) as { accounts?: { five_hour_used_percent?: number | null }[] } | null;
      return body?.accounts?.some((a) => a.five_hour_used_percent === STACK.fiveHourUsedPercent) ? true : null;
    });
    await sendHandOff(ports.oauth, key, peerAddress);
    await until(`the hand-off edge from ${STACK.sender.name}`, LANDING_TIMEOUT_MS, async () => {
      const body = (await read('/api/sessions/edges')) as { sessions?: Record<string, unknown[]> } | null;
      return (body?.sessions?.[STACK.sender.id]?.length ?? 0) > 0 ? true : null;
    });
    return { base, key, oauthPort: ports.oauth, configFile, repo, stop };
  } catch (err) {
    const why = err instanceof Error ? err.message : String(err);
    const detail = `${why}\n-- upstream requests: ${upstream.join(', ') || 'none'}\n-- daemon log tail:\n${tail(log)}`;
    await stop();
    throw new Error(detail, { cause: err });
  }
}
