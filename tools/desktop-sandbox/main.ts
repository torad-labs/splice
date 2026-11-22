#!/usr/bin/env bun
// tools/desktop-sandbox (docs/specs/desktop-full.md, outcome 8):
// Runs the installed Claude Desktop in a private sandbox (own HOME, XDG_CONFIG_HOME, D-Bus session, basic password
// store, private X display, and a splice daemon from this build) without touching the person's own Desktop, Claude,
// splice, or keyring folders.

import { existsSync, mkdtempSync, readFileSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { assertSafeSandboxRoot } from "./lib/paths.ts";
import {
  captureShot,
  launchDesktop,
  readState,
  runSpliceCli,
  runXdotool,
  startSandbox,
  stopProcess,
  stopSandbox,
  writeState,
} from "./lib/runtime.ts";

const DEFAULT_ROOT = join(tmpdir(), "splice-desktop-sandbox");

const HELP = `Usage: bun tools/desktop-sandbox/main.ts <command> [options]

Commands:
  start [--root DIR] [--watch] [--no-app]   Start private display, splice daemon, and Desktop
  shot  [PATH] [--root DIR]                 Capture PNG screenshot of the sandbox display
  click <x> <y> [--root DIR]                Click at display coordinates (x, y) via xdotool
  type  <text> [--root DIR]                 Type text into the sandbox display via xdotool
  key   <keysym> [--root DIR]               Send a key press (e.g. Return, Escape) via xdotool
  splice <args...> [--root DIR]             Run splice CLI against the sandbox daemon & config
  relaunch [--root DIR]                     Restart Claude Desktop inside the running sandbox
  stop [--root DIR] [--clean]               Stop Desktop, splice daemon, and private display
  check [--root DIR]                        Run an unattended end-to-end sandbox round and verify
`;

function extractOption(args: string[], flag: string): { value: string | undefined; rest: string[] } {
  const rest: string[] = [];
  let value: string | undefined;
  for (let i = 0; i < args.length; i++) {
    const a = args[i]!;
    if (a === flag && i + 1 < args.length) {
      value = args[++i];
    } else if (a.startsWith(`${flag}=`)) {
      value = a.slice(flag.length + 1);
    } else {
      rest.push(a);
    }
  }
  return { value, rest };
}

function hasFlag(args: string[], flag: string): { present: boolean; rest: string[] } {
  const rest = args.filter((a) => a !== flag);
  return { present: rest.length !== args.length, rest };
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

async function runCheck(explicitRoot?: string): Promise<void> {
  const root = explicitRoot
    ? assertSafeSandboxRoot(explicitRoot)
    : mkdtempSync(join(tmpdir(), "splice-desktop-check-"));
  const cleanAfter = !explicitRoot;
  let state: Awaited<ReturnType<typeof startSandbox>> | undefined;
  try {
    state = await startSandbox({ root, launchApp: false });
    const on = runSpliceCli(state.root, state.jar, ["desktop", "on", "--model", "claude-sandbox--sandbox-model"], state.display);
    if (on.code !== 0) {
      throw new Error(`splice desktop on failed (${on.code}): ${on.stdout}${on.stderr}`);
    }
    const meta = join(state.root, "config", "Claude-3p", "configLibrary", "_meta.json");
    if (!existsSync(meta)) {
      throw new Error(`expected Desktop 3P configLibrary at ${meta}`);
    }
    console.log("step: configuration applied");

    state.desktopPid = launchDesktop(state.root, state.display);
    writeState(state);

    // Wait for Desktop to probe the sandbox door (GET / or /door/v1/models or /desktop/v1/models).
    const daemonLog = join(state.root, "logs", "daemon.log");
    const deadline = Date.now() + 90_000;
    let askedDoor = false;
    while (Date.now() < deadline) {
      const logText = existsSync(daemonLog) ? readFileSync(daemonLog, "utf8") : "";
      if (/\/door\/v1\/models|\/desktop\/v1\/models|desktop-credential|GET \//.test(logText)) {
        askedDoor = true;
        break;
      }
      // Also check `splice desktop --json` for a discovery call recorded by DesktopCaller.
      const status = runSpliceCli(state.root, state.jar, ["desktop", "--json"], state.display);
      if (status.code === 0 && /"relaunchNeeded"\s*:\s*false|"discovered"\s*:\s*true/.test(status.stdout)) {
        askedDoor = true;
        break;
      }
      // Also check Desktop's own main.log for gateway mode / door requests.
      const mainLog = join(state.root, "config", "Claude-3p", "logs", "main.log");
      const altLog = join(state.root, "config", "Claude", "logs", "main.log");
      const appLog = (existsSync(mainLog) ? readFileSync(mainLog, "utf8") : "") +
        (existsSync(altLog) ? readFileSync(altLog, "utf8") : "");
      if (appLog.includes(`127.0.0.1:${state.controlPort}`)) {
        askedDoor = true;
        break;
      }
      await sleep(500);
    }
    if (!askedDoor) {
      throw new Error("Desktop did not contact the sandbox splice door within 90s");
    }
    console.log("step: Desktop asked the door");

    // Allow initial window paint then capture a screenshot.
    await sleep(2_000);
    const shotPath = captureShot(state, join(state.root, "check-screen.png"));
    console.log(`step: screenshot ${shotPath} (${statSync(shotPath).size} bytes)`);

    stopProcess(state.desktopPid);
    state.desktopPid = null;
    writeState(state);

    const off = runSpliceCli(state.root, state.jar, ["desktop", "off"], state.display);
    if (off.code !== 0) {
      throw new Error(`splice desktop off failed (${off.code}): ${off.stdout}${off.stderr}`);
    }
    console.log("step: off completed");
    console.log("PASS");
  } finally {
    if (state) stopSandbox(state.root, cleanAfter);
    else if (cleanAfter) stopSandbox(root, true);
  }
}

async function main(): Promise<void> {
  const argv = process.argv.slice(2);
  if (argv.length === 0 || argv[0] === "--help" || argv[0] === "-h" || argv[0] === "help") {
    console.log(HELP);
    return;
  }
  const cmd = argv[0]!;
  const rawRest = argv.slice(1);
  const rootOpt = extractOption(rawRest, "--root");
  const root = rootOpt.value ?? DEFAULT_ROOT;

  switch (cmd) {
    case "start": {
      const watchOpt = hasFlag(rootOpt.rest, "--watch");
      const noAppOpt = hasFlag(watchOpt.rest, "--no-app");
      const state = await startSandbox({ root, watch: watchOpt.present, launchApp: !noAppOpt.present });
      console.log(JSON.stringify(state, null, 2));
      return;
    }
    case "shot": {
      const state = readState(assertSafeSandboxRoot(root));
      const out = rootOpt.rest[0] ?? join(state.root, `shot-${Date.now()}.png`);
      console.log(captureShot(state, out));
      return;
    }
    case "click": {
      const state = readState(assertSafeSandboxRoot(root));
      const [x, y] = rootOpt.rest;
      if (!x || !y) throw new Error("usage: click <x> <y>");
      runXdotool(state, ["mousemove", "--sync", x, y, "click", "1"]);
      return;
    }
    case "type": {
      const state = readState(assertSafeSandboxRoot(root));
      const text = rootOpt.rest.join(" ");
      if (!text) throw new Error("usage: type <text>");
      runXdotool(state, ["type", "--delay", "25", "--clearmodifiers", "--", text]);
      return;
    }
    case "key": {
      const state = readState(assertSafeSandboxRoot(root));
      const key = rootOpt.rest[0];
      if (!key) throw new Error("usage: key <keysym>");
      runXdotool(state, ["key", "--clearmodifiers", key]);
      return;
    }
    case "splice": {
      const state = readState(assertSafeSandboxRoot(root));
      const r = runSpliceCli(state.root, state.jar, rootOpt.rest, state.display);
      if (r.stdout) process.stdout.write(r.stdout);
      if (r.stderr) process.stderr.write(r.stderr);
      process.exit(r.code);
    }
    case "relaunch": {
      const state = readState(assertSafeSandboxRoot(root));
      stopProcess(state.desktopPid);
      state.desktopPid = launchDesktop(state.root, state.display);
      writeState(state);
      console.log(`relaunched desktop pid=${state.desktopPid}`);
      return;
    }
    case "stop": {
      const cleanOpt = hasFlag(rootOpt.rest, "--clean");
      stopSandbox(assertSafeSandboxRoot(root), cleanOpt.present);
      console.log("stopped");
      return;
    }
    case "check": {
      await runCheck(rootOpt.value);
      return;
    }
    default:
      throw new Error(`unknown command: ${cmd}\n\n${HELP}`);
  }
}

main().catch((err) => {
  console.error(err instanceof Error ? err.message : String(err));
  process.exit(1);
});
