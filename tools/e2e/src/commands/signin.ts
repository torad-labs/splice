// `bun tools/e2e signin [--kind <kind>] [--jar <path>] [--json <file>]` — end-to-end provider
// sign-in verification (signin-e2e). Boots Splice from the candidate jar against local stand-in
// OAuth and inference servers, drives the real console in Chromium, verifies the CLI sign-in URL
// matches, completes sign-in (and remove + sign-in again), sends a turn through the door proving
// the newly minted credential is used, and checks all four failure paths.
import { spawn, type ChildProcess } from "node:child_process";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { layout } from "../../../gate/src/lib/repo.ts";
import {
  formatFailure,
  PAGE_CLOSED,
  readRegistryKinds,
  type KindReport,
  type Report,
  type Step,
} from "../lib/signin-registry.ts";
import { PROFILES, startStandIn, type ProviderProfile, type StandInServer } from "../lib/signin-standin.ts";

export const usage =
  "signin [--kind <kind>] [--jar <path>] [--json <file>]   verify every provider's sign-in end to end against stand-in servers";

async function freePort(): Promise<number> {
  return new Promise((resolvePort, reject) => {
    const srv = createServer();
    srv.unref();
    srv.on("error", reject);
    srv.listen(0, "127.0.0.1", () => {
      const addr = srv.address();
      const port = typeof addr === "object" && addr ? addr.port : 0;
      srv.close(() => resolvePort(port));
    });
  });
}

class FatalCheckError extends Error {}

async function until<T>(what: string, fn: () => T | Promise<T>, timeoutMs = 30_000, intervalMs = 150): Promise<T> {
  const end = Date.now() + timeoutMs;
  let lastErr: unknown;
  while (Date.now() < end) {
    try {
      const val = await fn();
      if (val) return val;
    } catch (e) {
      if (e instanceof FatalCheckError) throw e;
      lastErr = e;
    }
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error(`timed out waiting for ${what}${lastErr ? `: ${String(lastErr)}` : ""}`);
}

interface PrivateDaemon {
  home: string;
  stateDir: string;
  configPath: string;
  controlPort: number;
  headPort: number;
  mgmtKey: string;
  doorKey: string;
  proc: ChildProcess;
  binDir: string;
  env: Record<string, string>;
  stop(): Promise<void>;
}

function buildTopologyToml(profile: ProviderProfile, controlPort: number, headPort: number, baseUrl: string): string {
  const provider = profile.providerKey;
  const dialect = profile.dialect;
  return [
    "[daemon]",
    `control_port = ${controlPort}`,
    "",
    `[providers.${provider}]`,
    `dialect = "${dialect}"`,
    `base_url = "${baseUrl}"`,
    `auth = { kind = "${profile.kind}" }`,
    "",
    `[[providers.${provider}.models]]`,
    `id = "${profile.modelId}"`,
    `label = "${profile.modelId}"`,
    "context_window = 200000",
    "",
    `[heads.${profile.head}]`,
    `provider = "${provider}"`,
    `port = ${headPort}`,
    `pinned_model = "${profile.modelId}"`,
    "",
  ].join("\n");
}

function installStandInClaude(binDir: string, standInBaseUrl: string, profile: ProviderProfile): string {
  const script = join(binDir, "claude");
  const page = `${profile.providerPage}?code=true&client_id=synthetic-client&response_type=code&state=synthetic-state`;
  writeFileSync(
    script,
    [
      "#!/bin/sh",
      `MODE_FILE="${binDir}/claude-mode"`,
      `MODE=$(cat "$MODE_FILE" 2>/dev/null || echo approve)`,
      'if [ "$MODE" = "unnamed" ]; then echo "cannot start auth"; exit 1; fi',
      'echo "Opening your browser to sign in."',
      `echo "If the browser didn't open, visit: ${page}"`,
      "printf 'Paste code here if prompted > '",
      "IFS= read -r code || exit 1",
      'if [ "$MODE" = "refuse" ]; then echo "Login failed: synthetic provider refused authorization"; exit 1; fi',
      'if [ "$MODE" = "expire" ]; then echo "Login failed: authorization code expired"; exit 1; fi',
      `TOKEN=$(cat "${binDir}/claude-token" 2>/dev/null || echo "tok-claude-synthetic")`,
      `EMAIL=$(cat "${binDir}/claude-email" 2>/dev/null || echo "synthetic@example.com")`,
      `UUID=$(cat "${binDir}/claude-uuid" 2>/dev/null || echo "uuid-synthetic-1")`,
      'mkdir -p "${CLAUDE_CONFIG_DIR:-$HOME/.claude}"',
      'printf \'{"claudeAiOauth":{"accessToken":"%s","refreshToken":"ref-%s","expiresAt":4102444800000}}\' "$TOKEN" "$TOKEN" > "${CLAUDE_CONFIG_DIR:-$HOME/.claude}/.credentials.json"',
      'printf \'{"oauthAccount":{"accountUuid":"%s","emailAddress":"%s"}}\' "$UUID" "$EMAIL" > "${CLAUDE_CONFIG_DIR:-$HOME/.claude}/.claude.json"',
      'echo "Logged in"',
      "exit 0",
      "",
    ].join("\n"),
  );
  chmodSync(script, 0o755);
  return `${standInBaseUrl}`;
}

async function bootDaemon(jar: string, profile: ProviderProfile, standIn: StandInServer): Promise<PrivateDaemon> {
  const home = mkdtempSync(join(tmpdir(), `splice-signin-e2e-${profile.kind}-`));
  const stateDir = join(home, ".splice/state");
  const configDir = join(home, ".config/splice");
  const binDir = join(home, "bin");
  mkdirSync(stateDir, { recursive: true });
  mkdirSync(configDir, { recursive: true });
  mkdirSync(binDir, { recursive: true });

  const controlPort = await freePort();
  const headPort = await freePort();
  const configPath = join(configDir, "splice.toml");
  writeFileSync(configPath, buildTopologyToml(profile, controlPort, headPort, standIn.baseUrl));

  if (profile.kind === "client") {
    installStandInClaude(binDir, standIn.baseUrl, profile);
  }

  const browserLog = join(home, "browser-opened.log");
  const browserScript = join(binDir, "capture-browser");
  writeFileSync(browserScript, `#!/bin/sh\nprintf '%s\\n' "$1" >> "${browserLog}"\n`);
  chmodSync(browserScript, 0o755);

  const env: Record<string, string> = {
    ...(process.env as Record<string, string>),
    HOME: home,
    XDG_CONFIG_HOME: join(home, ".config"),
    XDG_STATE_HOME: join(home, ".local/state"),
    SPLICE_CONFIG: configPath,
    SPLICE_STATE_DIR: stateDir,
    CLAUDEX_STATE_DIR: stateDir,
    SPLICE_NO_AUTO_UPDATE: "1",
    BROWSER: browserScript,
    PATH: `${binDir}:${process.env.PATH ?? ""}`,
    ...standIn.envOverrides(),
  };

  let logs = "";
  const proc = spawn("java", ["-jar", jar, "daemon"], {
    cwd: home,
    env,
    stdio: ["ignore", "pipe", "pipe"],
  });
  proc.stdout.on("data", (b) => {
    logs += String(b);
  });
  proc.stderr.on("data", (b) => {
    logs += String(b);
  });

  const exited = new Promise<number>((resolveExit) => proc.on("exit", (c) => resolveExit(c ?? -1)));

  await until(
    `daemon on :${controlPort} healthy`,
    async () => {
      if (proc.exitCode !== null) {
        throw new FatalCheckError(`daemon exited early (${proc.exitCode}): ${logs.slice(-1500)}`);
      }
      const res = await fetch(`http://127.0.0.1:${controlPort}/health`).catch(() => null);
      return res?.ok ?? false;
    },
    45_000,
    200,
  );

  const mgmtKey = await until(
    "management key file",
    () => {
      const p = join(stateDir, "mgmt-key");
      return existsSync(p) ? readFileSync(p, "utf8").trim() : "";
    },
    10_000,
  );

  // Mint/read terminal door key via /api/door/key or state file
  let doorKey = "";
  const termKeyPath = join(stateDir, "terminal-key");
  if (existsSync(termKeyPath)) {
    doorKey = readFileSync(termKeyPath, "utf8").trim();
  } else {
    const dkRes = await fetch(`http://127.0.0.1:${controlPort}/api/desktop/key`, {
      method: "POST",
      headers: { Authorization: `Bearer ${mgmtKey}`, "Content-Type": "application/json" },
      body: "{}",
    }).catch(() => null);
    if (dkRes?.ok) {
      const dkBody = (await dkRes.json()) as { key?: string };
      doorKey = dkBody.key ?? "";
    }
    if (!doorKey) doorKey = mgmtKey;
  }

  return {
    home,
    stateDir,
    configPath,
    controlPort,
    headPort,
    mgmtKey,
    doorKey,
    proc,
    binDir,
    env,
    async stop() {
      if (proc.exitCode === null) {
        proc.kill("SIGTERM");
        await Promise.race([exited, new Promise((r) => setTimeout(r, 5_000))]);
        if (proc.exitCode === null) proc.kill("SIGKILL");
      }
      rmSync(home, { recursive: true, force: true });
    },
  };
}

async function captureCliPage(jar: string, daemon: PrivateDaemon, profile: ProviderProfile): Promise<string> {
  const logFile = join(daemon.home, "browser-opened.log");
  if (existsSync(logFile)) writeFileSync(logFile, "");
  const child = spawn("java", ["-jar", jar, "login", profile.head], {
    cwd: daemon.home,
    env: daemon.env,
    stdio: ["pipe", "pipe", "pipe"],
  });
  let out = "";
  child.stdout.on("data", (b) => {
    out += String(b);
  });
  child.stderr.on("data", (b) => {
    out += String(b);
  });
  try {
    const found = await until(
      `splice login ${profile.head} to open/print provider URL`,
      () => {
        if (existsSync(logFile)) {
          const line = readFileSync(logFile, "utf8")
            .split("\n")
            .map((l) => l.trim())
            .find((l) => l.startsWith("https://"));
          if (line) return line;
        }
        const m = /https:\/\/[^\s"'<>]+/.exec(out);
        return m ? m[0] : "";
      },
      25_000,
      150,
    );
    return found;
  } finally {
    if (child.exitCode === null) child.kill("SIGKILL");
  }
}

async function mintConsoleKey(daemon: PrivateDaemon): Promise<string> {
  const res = await fetch(`http://127.0.0.1:${daemon.controlPort}/api/console/key`, {
    method: "POST",
    headers: { Authorization: `Bearer ${daemon.mgmtKey}`, "Content-Type": "application/json" },
    body: "{}",
  }).catch(() => null);
  if (res?.ok) {
    const body = (await res.json()) as { key?: string };
    if (body.key) return body.key;
  }
  return daemon.mgmtKey;
}

async function sendDoorTurn(
  daemon: PrivateDaemon,
  profile: ProviderProfile,
): Promise<{ ok: boolean; reply: string; raw: string }> {
  const res = await fetch(`http://127.0.0.1:${daemon.controlPort}/door/v1/messages`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${daemon.doorKey}`,
      "x-api-key": daemon.doorKey,
      "anthropic-version": "2023-06-01",
    },
    body: JSON.stringify({
      model: `${profile.head}--${profile.modelId}`,
      max_tokens: 64,
      stream: false,
      messages: [{ role: "user", content: "say hello" }],
    }),
  });
  const raw = await res.text();
  if (!res.ok) return { ok: false, reply: "", raw: `HTTP ${res.status}: ${raw}` };
  try {
    const body = JSON.parse(raw) as { content?: { type?: string; text?: string }[] };
    const text = (body.content ?? [])
      .filter((b) => b.type === "text" && typeof b.text === "string")
      .map((b) => b.text)
      .join("");
    return { ok: true, reply: text, raw };
  } catch {
    return { ok: false, reply: "", raw };
  }
}

async function runKind(jar: string, profile: ProviderProfile): Promise<KindReport> {
  const steps: Step[] = [
    { step: 1, name: "page-opens", ok: false, said: "not run", seen: {} },
    { step: 2, name: "login-completes", ok: false, said: "not run", seen: {} },
    { step: 3, name: "gateway-serves-turn", ok: false, said: "not run", seen: {} },
    { step: 4, name: "failure-says-so", ok: false, said: "not run", seen: {} },
  ];

  const standIn = startStandIn(profile);
  let daemon: PrivateDaemon | null = null;
  let browser: Awaited<ReturnType<(typeof import("@playwright/test"))["chromium"]["launch"]>> | null = null;

  try {
    const { chromium } = await import("@playwright/test");
    daemon = await bootDaemon(jar, profile, standIn);
    const consoleKey = await mintConsoleKey(daemon);
    const firstCred = standIn.mintNext("first");
    if (profile.kind === "client") {
      writeFileSync(join(daemon.binDir, "claude-token"), firstCred.accessToken);
      writeFileSync(join(daemon.binDir, "claude-email"), "first@synthetic.example");
      writeFileSync(join(daemon.binDir, "claude-uuid"), firstCred.accountId);
    }

    browser = await chromium.launch({
      executablePath: process.env.SCREEN_REVIEW_BROWSER || undefined,
      args: ["--no-sandbox", "--disable-background-networking"],
    });
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
    await context.addInitScript((k: string) => {
      localStorage.setItem("splice-console-access-key", k);
    }, consoleKey);

    let lastPageCode = "";
    await context.route(`${profile.providerOrigin}/**`, async (route) => {
      const reqUrl = new URL(route.request().url());
      if (profile.flow === "loopback") {
        const state = reqUrl.searchParams.get("state") ?? "";
        const redirectUri = reqUrl.searchParams.get("redirect_uri") ?? profile.callbackUrl ?? "";
        const target = `${redirectUri}?code=synthetic-auth-code&state=${encodeURIComponent(state)}`;
        await route.fulfill({
          status: 200,
          contentType: "text/html",
          body: `<!doctype html><h1>Approve ${profile.kind}</h1><a id="approve" href="${target}">Approve</a>`,
        });
      } else if (profile.flow === "device") {
        const uc = reqUrl.searchParams.get("user_code") ?? standIn.lastDeviceCode()?.userCode ?? "";
        lastPageCode = uc;
        await route.fulfill({
          status: 200,
          contentType: "text/html",
          body: `<!doctype html><h1>Device code</h1><p id="code">${uc}</p>`,
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: "text/html",
          body: `<!doctype html><h1>Claude sign-in</h1><code id="paste-code">synthetic-paste-code#synthetic-state</code>`,
        });
      }
    });

    const page = await context.newPage();
    const targetPage = profile.kind === "client" ? "accounts.html" : "providers.html";
    await page.goto(`http://127.0.0.1:${daemon.controlPort}/${targetPage}`, { waitUntil: "networkidle" });

    async function openSignInGlass() {
      if (profile.kind === "client") {
        const addBtn = page.locator('[data-act="add"]');
        await addBtn.waitFor({ state: "visible", timeout: 15_000 });
        await addBtn.click();
        return page.locator("#lens");
      }
      const card = page.locator(`[data-key="${profile.head}"], [data-key="claude-${profile.head}"], [data-key]`).first();
      await card.waitFor({ state: "visible", timeout: 15_000 });
      await card.click();
      return page.locator("#lens, .lens, [data-lens]").first();
    }

    const glass = await openSignInGlass();
    const pageOpensPromise = context.waitForEvent("page", { timeout: 15_000 });
    const signBtn = glass.getByRole("button", { name: /^Sign in$/i }).first();
    await signBtn.waitFor({ state: "visible", timeout: 15_000 });
    await signBtn.click();

    let tab: Awaited<typeof pageOpensPromise> | null = null;
    try {
      tab = await pageOpensPromise;
      await until(
        "tab navigates to provider page",
        () => tab!.url().startsWith(profile.providerPage),
        15_000,
        100,
      );
    } catch (e) {
      steps[0] = {
        step: 1,
        name: "page-opens",
        ok: false,
        said: `console did not open a tab on ${profile.providerPage}: ${String(e)}`,
        seen: { console_tab: tab?.url() ?? "", provider_page: profile.providerPage },
      };
      return { kind: profile.kind, flow: profile.flow, head: profile.head, ok: false, steps };
    }

    const consoleTab = tab.url();
    const cliPage = await captureCliPage(jar, daemon, profile);
    const step1Seen: Record<string, unknown> = {
      console_tab: consoleTab,
      provider_page: profile.providerPage,
      cli_page: cliPage,
    };
    if (profile.flow === "device") {
      const cardCode = await until(
        "device code on card",
        async () => (await glass.locator(".sign-code").textContent())?.trim() ?? "",
        10_000,
      );
      step1Seen.card_code = cardCode;
      step1Seen.page_code = lastPageCode || cardCode;
    }
    steps[0] = { step: 1, name: "page-opens", ok: true, said: "ok", seen: step1Seen };

    // Step 2: complete sign-in, verify no reload & "Signed in as <label>", then remove & sign in again
    let mainLoads = 0;
    page.on("framenavigated", (f) => {
      if (f === page.mainFrame()) mainLoads += 1;
    });

    async function completeActiveSignIn(activeTab: NonNullable<typeof tab>) {
      if (profile.flow === "loopback") {
        await activeTab.locator("#approve").click();
      } else if (profile.flow === "device") {
        const dc = standIn.lastDeviceCode();
        if (dc) standIn.approveDevice(dc.userCode);
      } else {
        const pasteInput = glass.locator("input.lr-in").first();
        await pasteInput.waitFor({ state: "visible", timeout: 15_000 });
        await pasteInput.fill("synthetic-paste-code#synthetic-state");
        await glass.getByRole("button", { name: /Send code/i }).click();
      }
    }

    await completeActiveSignIn(tab);
    const firstCard = await until(
      "Signed in as <label> on glass",
      async () => {
        const txt = (await glass.textContent()) ?? "";
        return /Signed in as\s+\S+/i.test(txt) ? txt : "";
      },
      25_000,
      200,
    );
    const firstLabel = /Signed in as\s+([^\n]+)/i.exec(firstCard)?.[1]?.trim() ?? "";
    const reloaded = mainLoads > 0;
    await tab.close().catch(() => {});

    // Remove the first login via management API, mint a second credential, and sign in again
    const accountsRes = await fetch(`http://127.0.0.1:${daemon.controlPort}/api/accounts`, {
      headers: { Authorization: `Bearer ${daemon.mgmtKey}` },
    });
    const accountsJson = (await accountsRes.json()) as { accounts?: { label?: string; heads?: string[] }[] };
    const toRemove = (accountsJson.accounts ?? []).find((a) => (a.heads ?? []).includes(profile.head));
    if (toRemove?.label) {
      await fetch(
        `http://127.0.0.1:${daemon.controlPort}/api/auth/${encodeURIComponent(profile.head)}/accounts/${encodeURIComponent(toRemove.label)}`,
        { method: "DELETE", headers: { Authorization: `Bearer ${daemon.mgmtKey}` } },
      );
    }

    const secondCred = standIn.mintNext("second");
    if (profile.kind === "client") {
      writeFileSync(join(daemon.binDir, "claude-token"), secondCred.accessToken);
      writeFileSync(join(daemon.binDir, "claude-email"), "second@synthetic.example");
      writeFileSync(join(daemon.binDir, "claude-uuid"), secondCred.accountId);
    }

    const againTabPromise = context.waitForEvent("page", { timeout: 15_000 });
    await page.reload({ waitUntil: "networkidle" });
    const glass2 = await openSignInGlass();
    await glass2.getByRole("button", { name: /^Sign in$/i }).first().click();
    const againTab = await againTabPromise;
    await until("second tab navigates to provider page", () => againTab.url().startsWith(profile.providerPage), 15_000, 100);
    await completeActiveSignIn(againTab);
    const againCard = await until(
      "second Signed in as <label>",
      async () => {
        const txt = (await glass2.textContent()) ?? "";
        return /Signed in as\s+\S+/i.test(txt) ? txt : "";
      },
      25_000,
      200,
    );
    const againLabel = /Signed in as\s+([^\n]+)/i.exec(againCard)?.[1]?.trim() ?? "";
    await againTab.close().catch(() => {});

    steps[1] = {
      step: 2,
      name: "login-completes",
      ok: Boolean(firstLabel && againLabel && !reloaded),
      said: !firstLabel ? "missing label" : reloaded ? "page reloaded" : "ok",
      seen: {
        label: firstLabel,
        card: firstCard,
        reloaded,
        again_label: againLabel,
        again_card: againCard,
      },
    };
    if (!steps[1].ok) return { kind: profile.kind, flow: profile.flow, head: profile.head, ok: false, steps };

    // Step 3: send a turn through the door and verify credential == secondCred and != firstCred
    standIn.clearSeenCredentials();
    const expectedReply = `verified-turn-${profile.kind}-${Date.now()}`;
    standIn.setExpectedReply(expectedReply);
    const turn = await sendDoorTurn(daemon, profile);
    const mintedToken = profile.kind === "muse-oauth" ? secondCred.apiKey : secondCred.accessToken;
    const removedToken = profile.kind === "muse-oauth" ? firstCred.apiKey : firstCred.accessToken;
    const seen = standIn.seenCredentials();
    const usedCred = seen[0] ?? "";
    const others = seen.filter((c) => c !== mintedToken);
    const step3Ok = turn.ok && turn.reply === expectedReply && usedCred === mintedToken && usedCred !== removedToken && others.length === 0;
    steps[2] = {
      step: 3,
      name: "gateway-serves-turn",
      ok: step3Ok,
      said: step3Ok
        ? "ok"
        : `turn failed or credential mismatch (reply="${turn.reply}", expected="${expectedReply}", cred="${usedCred}", minted="${mintedToken}", raw="${turn.raw.slice(0, 200)}")`,
      seen: {
        reply: turn.reply,
        expected_reply: expectedReply,
        minted: mintedToken,
        credential: usedCred,
        removed: removedToken,
        others,
      },
    };
    if (!steps[2].ok) return { kind: profile.kind, flow: profile.flow, head: profile.head, ok: false, steps };

    // Step 4: verify failure modes (refused, page_closed, expired, unnamed)
    const refusalMsg = "synthetic provider refused authorization";
    standIn.setMode("refuse", refusalMsg);
    if (profile.kind === "client") writeFileSync(join(daemon.binDir, "claude-mode"), "refuse");
    await page.reload({ waitUntil: "networkidle" });
    const gRef = await openSignInGlass();
    const refTabP = context.waitForEvent("page", { timeout: 15_000 });
    await gRef.getByRole("button", { name: /^Sign in$/i }).first().click();
    const refTab = await refTabP;
    await until("refusal tab", () => refTab.url().startsWith(profile.providerPage), 15_000, 100);
    await completeActiveSignIn(refTab);
    const refCard = await until(
      "refusal message on card",
      async () => {
        const txt = (await gRef.textContent()) ?? "";
        return txt.includes(refusalMsg) ? txt : "";
      },
      20_000,
    );
    await refTab.close().catch(() => {});

    // Page closed before completion
    standIn.setMode("approve");
    if (profile.kind === "client") writeFileSync(join(daemon.binDir, "claude-mode"), "approve");
    const closeTabP = context.waitForEvent("page", { timeout: 15_000 });
    await gRef.getByRole("button", { name: /Try again|Sign in/i }).first().click();
    const closeTab = await closeTabP;
    await until("close tab on provider", () => closeTab.url().startsWith(profile.providerPage), 15_000, 100);
    await closeTab.close();
    const closedCard = await until(
      "page closed message",
      async () => {
        const txt = (await gRef.textContent()) ?? "";
        return txt.includes(PAGE_CLOSED) ? txt : "";
      },
      15_000,
    );

    // Expired code
    standIn.setMode("expire");
    if (profile.kind === "client") writeFileSync(join(daemon.binDir, "claude-mode"), "expire");
    const expTabP = context.waitForEvent("page", { timeout: 15_000 });
    await gRef.getByRole("button", { name: /Cancel/i }).first().click().catch(() => {});
    await gRef.getByRole("button", { name: /Sign in|Try again/i }).first().click();
    const expTab = await expTabP;
    await until("expire tab", () => expTab.url().startsWith(profile.providerPage), 15_000, 100);
    await completeActiveSignIn(expTab);
    const expCard = await until(
      "expired message on card",
      async () => {
        const txt = (await gRef.textContent()) ?? "";
        return /expired/i.test(txt) ? txt : "";
      },
      20_000,
    );
    await expTab.close().catch(() => {});

    // Unnamed (fails before naming a URL -> empty tab is closed)
    standIn.setMode("unnamed");
    if (profile.kind === "client") writeFileSync(join(daemon.binDir, "claude-mode"), "unnamed");
    const unTabP = context.waitForEvent("page", { timeout: 15_000 });
    await gRef.getByRole("button", { name: /Try again|Sign in/i }).first().click();
    const unTab = await unTabP;
    const unTabClosed = await until("unnamed empty tab closed", () => unTab.isClosed(), 15_000, 100).catch(() => false);
    const unCard = ((await gRef.textContent()) ?? "").trim();

    const step4Ok =
      refCard.includes(refusalMsg) &&
      closedCard.includes(PAGE_CLOSED) &&
      /expired/i.test(expCard) &&
      Boolean(unTabClosed) &&
      unCard.length > 0;
    steps[3] = {
      step: 4,
      name: "failure-says-so",
      ok: step4Ok,
      said: step4Ok ? "ok" : "one or more failure checks did not match",
      seen: {
        refused: { provider_said: refusalMsg, card: refCard },
        page_closed: { card: closedCard },
        expired: { card: expCard },
        unnamed: { tab_closed: Boolean(unTabClosed), card: unCard },
      },
    };
    return { kind: profile.kind, flow: profile.flow, head: profile.head, ok: step4Ok, steps };
  } catch (e) {
    const firstPending = steps.find((s) => !s.ok) ?? steps[0]!;
    firstPending.ok = false;
    firstPending.said = String(e);
    return { kind: profile.kind, flow: profile.flow, head: profile.head, ok: false, steps };
  } finally {
    await browser?.close().catch(() => {});
    await daemon?.stop().catch(() => {});
    standIn.stop();
  }
}

export async function signin(argv: readonly string[]): Promise<number> {
  const { repoRoot } = layout();
  let kindFilter: string | undefined;
  let jarPath = join(repoRoot, "app/build/libs/app-all.jar");
  let jsonFile: string | undefined;

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]!;
    if (arg === "--kind") {
      kindFilter = argv[++i];
      if (!kindFilter) {
        console.error("signin: --kind requires an auth kind");
        return 2;
      }
    } else if (arg === "--jar") {
      const v = argv[++i];
      if (!v) {
        console.error("signin: --jar requires a path");
        return 2;
      }
      jarPath = resolve(v);
    } else if (arg === "--json") {
      jsonFile = argv[++i];
      if (!jsonFile) {
        console.error("signin: --json requires an output path");
        return 2;
      }
    } else {
      console.error(`signin: unknown argument ${arg}`);
      return 2;
    }
  }

  const registry = readRegistryKinds(repoRoot);
  const missing = registry.filter((k) => !PROFILES[k]);
  const selected = kindFilter ? [kindFilter] : registry;
  if (kindFilter && !registry.includes(kindFilter)) {
    console.error(`signin: unknown auth kind "${kindFilter}" (known: ${registry.join(", ")})`);
    return 2;
  }

  const kinds: KindReport[] = [];
  for (const kind of selected) {
    const profile = PROFILES[kind];
    if (!profile) {
      const step: Step = {
        step: 1,
        name: "missing-case",
        ok: false,
        said: `no sign-in check case registered for ${kind}`,
        seen: {},
      };
      console.error(formatFailure(kind, step));
      kinds.push({ kind, flow: "unknown", head: kind, ok: false, steps: [step] });
      continue;
    }
    const result = await runKind(jarPath, profile);
    kinds.push(result);
    if (result.ok) {
      console.log(`signin: PASS ${kind} (4 steps)`);
    } else {
      const firstFail = result.steps.find((s) => !s.ok) ?? result.steps[0]!;
      console.error(formatFailure(kind, firstFail));
    }
  }

  const ok = missing.length === 0 && kinds.length > 0 && kinds.every((k) => k.ok);
  const report: Report = { ok, jar: jarPath, missing, kinds };
  if (jsonFile) {
    writeFileSync(jsonFile, JSON.stringify(report, null, 2) + "\n");
  }
  return ok ? 0 : 1;
}
