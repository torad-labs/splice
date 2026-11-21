# console-own

A Splice of the campaign's own, with made-up data and a probe session, so a builder can try what the console writes
(sending in the composer, a setting, a sign-in, stopping a session) without ever touching the Splice that runs on the
machine.

    bun tools/console-own up [--json]
    bun tools/console-own down <home>

`up` builds the daemon from this checkout (`./gradlew :app:shadowJar`, through the build gate like any build) and
starts it in a home of its own directly under `/tmp` (`/tmp/splice-console-own-*`; `/private/tmp` on macOS, never the
per-user temp folder): its own HOME, config, state,
ports, logs and tmux socket. It refuses to start when any path it would write resolves into the person's own Splice,
Claude Code or keyring (`~/.splice`, `~/.config/splice`, `~/.claude`, `~/.local/share/keyrings`), never takes the
control port 3096, and runs nothing with the person's session bus, display or Splice variables.

What it holds, all made up:

- Two providers, `acme` (command `claude-acme`, model `mock-chat`) and `globex` (command `claude-globex`, model
  `mock-chat-2`), each with made-up prices, on a local stand-in upstream (`tools/e2e/docker/mock_chat.ts`, an
  OpenAI-compatible chat that answers every request with a short fixed reply and its usage) with a made-up key.
- A dozen requests through each command, sent the way Claude Code sends them, so Activity and Providers have rows;
  the last of each is refused by the stand-in with a 429.
- One team, `harbor-crew`, made with `PUT /api/teams`.
- A probe session: Claude Code launched through this Splice on `claude-acme` (the command linked to the
  `splice-launch` shim, as `splice install` links one), in a tmux on the home's own socket. It has said hello once, and
  `/api/sessions` lists it as live. It answers only from the stand-in, never from a real provider.

`up --json` prints, last, one line: `{"home", "console", "key", "launch"}`: the console's address, this Splice's own
management key (made up by it, good for this home only), and the launch page `splice console --no-browser` wrote,
which signs one browser in, once. Without `--json` it prints the same in a sentence. Progress goes to stderr.

`down <home>` stops every process of that home (the daemon, the stand-in, the probe session and its tmux) and removes
the folder. It removes only a console-own home.

## How a builder uses it to try a write

Everything runs in one `rig remote run` on the Mac with Java (the MacBook Pro: Java, Gradle, tmux and Claude Code),
never on the machine that runs the person's own Splice:

    rig remote run --needs system=Darwin/arm64 --needs java --fetch build/own-look -- bun /path/to/look.ts

where `look.ts` is a short Playwright script on `.campaign/checks/console-night-deck/lib/deck.ts` (its `browser()`
and the 1440 and 3840 sizes) that:

1. runs `bun tools/console-own up --json` and keeps its last line (`home`, `console`, `key`, `launch`);
2. opens the console it printed in headless Chromium, signed in with this home's own made-up key (the launch page, or
   `localStorage["splice-console-access-key"] = key` before the page loads);
3. makes the write: changes a setting, sends to the probe session in the composer, stops it;
4. reads the effect back from this Splice's own reads (`fetch(console + "/api/config", { headers: { authorization:
   "Bearer " + key } })`), never from the person's Splice;
5. takes its pictures into `build/own-look/` at both widths, in night and day;
6. runs `bun tools/console-own down <home>` in a `finally`, so the home is gone however the script ends.

`--fetch build/own-look` brings the pictures back; look at each one as an image.

Pictures for a pull request come from here: everything in this Splice is made up.
