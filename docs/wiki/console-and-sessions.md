[README](../../README.md) · [Wiki Home](README.md)

# Web Console, Sessions, Usage & Budgets

The console is splice's browser view of your sessions, accounts, providers, spend and settings, served on this machine
by the daemon's control plane. This page covers opening it, what each page does, how plan usage and prices are read,
daily budgets, and the splice manager that lives in the console's composer.

## Open the console

```bash
splice console               # sign a browser in and open the console
splice console --no-browser  # print the sign-in page's file address for a browser you open yourself
splice login                 # open the console straight on Accounts
```

The console answers on the control port, `control_port` under `[daemon]`, 3096 by default, on loopback only. A browser
that opens the port's root lands on Accounts.

```toml
[daemon]
control_port = 3096
```

`splice console` signs a browser in with a single-use code that expires after two minutes, so no address it opens
carries a key. With an action word, the same command drives an already open console page (`snapshot`, `read`, `tap`,
`fill`, `press` and others); see [cli-reference.md](cli-reference.md#status-diagnostics-and-console-controls).

### Keys and the control plane

| Key | Where it lives | What it opens |
| --- | --- | --- |
| Management key | `~/.splice/state/mgmt-key` (installs made before v0.4.0 keep theirs at `~/.claude-codex/state/mgmt-key`, which splice keeps reading in place) | The control plane's data and control API; answers only on this machine |
| Turn key | Derived from the management key, shared across the install | Every head's turn, status-line and resume-hook routes, but not management routes |
| Console key | Held by the console | What the console reads and changes |
| none | | `/health` reports the version without a key |

`splice rotate-key` replaces the management key while the daemon runs, and the old key and the turn key made from it are
refused at once. A session splice launches reads its turn key through Claude Code's key helper, so after a rotation its
next request reads the new key and goes on, on the same head, with nothing you typed lost. The old keys stay known as
splice's by their SHA-256, so a command started inside an older session drops the old turn key it inherits, and a head
that forwards your own Anthropic login refuses it rather than sending it on.

Two kinds of session are stranded by a rotation: a session launched before the helper, which holds its turn key in its
environment, and a session on a Claude head that holds logins of its own whose own login is not one of them (its launch
proof carries the turn key it was launched with, and the head answers that its splice key is not valid). A rotation
also replaces the MCP access key, and a running session reaches the MCP servers splice hosts with the key it was
launched with.

```bash
splice rotate-key
splice sessions relaunch   # repeat until it reports none left
```

`splice sessions relaunch` relaunches each such session on its own head, resumed. It leaves a session that is working,
waiting on an answer or holding words you have not sent, and the session it runs in, where they are and names each.

To follow a session's reply as it is written, the control API streams it with the same key:
[docs/live-reply.md](../live-reply.md) describes the route (`GET /api/sessions/<id>/live`, loopback control plane only).

## The pages

The 0.7.0 console ("Night Deck") puts six pages on one design, in night and day looks:

| Page | Address | What it is for |
| --- | --- | --- |
| Sessions | `sessions.html` | Every session, grouped by project; open one to read it, with suggested-task chips and its chapters in a rail beside it |
| Accounts | `accounts.html` | Logins and API keys per command, Use now pins, Day meters and budgets; the console's first page |
| Teams | `teams.html` | Teams of sessions, grouped by project |
| Providers | `providers.html` | Providers and their models (`?cmd=<command>`), Compare models (`?open=compare`), adding a provider (`?open=add`) |
| Activity | `activity.html` | Requests and spend for the window, split by project (`?project=<id>`) |
| Settings | `settings.html` | Sessions, Reasoning display, Concurrent requests, Stalled responses, MCP servers, Modes, Desktop policy, Desktop harnesses, Storage, Usage alerts, Projects, Splice, Advanced |

A team server adds `server.html` ([team-server-mode.md](team-server-mode.md)). Pages rest when nothing changes, type is
one size on every screen and scales with browser zoom, and a wide window shows more of Teams, Providers, Activity and
Settings.

Old addresses redirect with their query kept, so bookmarks keep working:

| Old page | Now |
| --- | --- |
| `requests.html` | `activity.html` |
| `usage.html` | `activity.html` |
| `models.html` | `providers.html` |
| `compare.html` | `providers.html?open=compare` |

The session reader that the Sessions page uses is also served on its own at `/reply-view/`; its theming hooks are in
[`app/src/main/resources/console/reply-view/README.md`](../../app/src/main/resources/console/reply-view/README.md).

### Suggested tasks and chapters

The `ccd_session` mode ships on for door and head sessions (not Claude Desktop's own sessions). The model can queue an
out-of-scope follow-up, withdraw it, and mark chapter boundaries; Sessions shows the follow-ups as chips and the chapters
in its rail. The routes are `GET /api/suggested-tasks` and `GET /api/chapters`. To turn it off:

```toml
[modes.ccdsession]
on = false
```

Modes in general are covered in [modes-and-extensions.md](modes-and-extensions.md).

## Projects and Compare models

Sessions and Teams group their rows by project, and a session whose directory belongs to no project sits under **No
project**. Activity splits the window's requests by project under Where it went, No project among them, and pressing a
project narrows Latest requests to it (`activity.html?project=<id>`).

**Compare models**, on Providers, sets up to three models side by side (input, output and cached input prices, context
window, and the last 24 hours' median response time and failure rate) and sends one prompt to each of them, showing the
answers side by side with their time, tokens each way and what each costs at API rates: your declared rate card where
you set one, else the vendor's published price. A send is a real request: it goes out on that command's own login,
counts against its budget (a send over budget is refused), and leaves a row on Activity with no session and no project,
which the answer's Request opens.

## Plan usage in Claude Code

splice passes provider-reported usage into Claude Code's status line, including usage windows, and shows a window's reset
time once it reaches 60%. On Claude heads, the reading comes from the account the pool has selected; without one, it
comes from the carrying login's quota, then from the next selectable login.

| Mark | When |
| --- | --- |
| `⚠` on a rate-limit reading | Once it reaches `usageWarnPct`, 80 by default; `0` turns that early mark off |
| `⚠` regardless of the setting | A reading at 98% or more |
| Plan-window bar yellow / red | 60% / 85%; the bars carry no mark |

The warning is advisory; it does not block requests. `usageWarnPct` is a serving knob (quoted, in `[defaults]` or on
Settings, or `SPLICE_USAGE_WARN_PCT`):

```toml
[defaults]
usageWarnPct = "80"
```

Subscription heads poll their own provider every five minutes while the daemon is running so usage can appear before the
first turn, using that head's own credential:

| Provider | Usage source |
| --- | --- |
| ChatGPT | its usage endpoint |
| Kimi | its usages endpoint |
| Grok | its billing endpoint |
| Muse | the usage in its key-mint response |
| Claude logins splice reads, added accounts included | Anthropic's usage endpoint, each with its own token |

Heads using an API key do not poll, and a login forwarded from Claude Code gets no usage reading. Set
`CLAUDEX_QUOTA_POLL=off` in the daemon's environment to disable polling, then restart the daemon after pending work
finishes; usage can still arrive in each turn's rate-limit headers. `splice usage --json` prints commands, logins and
plan windows as one JSON document.

## Daily budgets

A command can carry a daily budget in dollars, set on Accounts from its Day meter (**Set budget**), with the action
**Warn** or **Block**. It is kept in `budgets.json` in the state directory, and an edit there takes effect within a
second, with no restart. The budget weighs that command's estimated API-rate cost for the day, which starts at local
midnight in the daemon's time zone. Turns on a plan account do not count against it, and a turn on a model with no rate
card is counted as unpriced rather than added in, so the budget can read low.

| Action | What happens when the day's spend reaches the budget |
| --- | --- |
| **Block** | Each new turn on that command is refused. Claude Code shows splice's refusal, which names the spend and the budget and says new turns are refused until midnight, local time. A turn already running finishes. Raising or removing the budget lets the next turn through, and a Compare models send over budget is refused too. |
| **Warn** | Every turn is served. splice writes one line to the daemon log, `[<command>][budget] WARN reached`, once a day for that command, and posts a sentence to the webhook URL saved in the alert settings, if there is one. On Accounts, the command's Day meter fills and shows its bell. Nothing else announces it: desktop alerts are not delivered, and the status line shows no budget. |

A team server's per-person budgets are separate; see [team-server-mode.md](team-server-mode.md).

## Where a price comes from

A turn is priced with the first of these that exists:

1. The rate card you declared for the model or the command in `splice.toml`.
2. The price the provider's own model list publishes.
3. splice's table of each vendor's published price.

A model with none of the three is counted as unpriced.

The table ships in the build. Once an hour the daemon also fetches it from the project's main branch on
`raw.githubusercontent.com`, so a new model is priced and a changed price is right without waiting for a release. That is
one anonymous request: no token, and nothing about your machine, your accounts or your sessions. A file that does not
read as the table, or is older than the one in use, is ignored. To turn it off:

```toml
[daemon]
refresh_prices = false
```

`splice status` says which table is in use: the copy in the build, or the one fetched, with when it was fetched. Rate-card
keys are in [configuration-reference.md](configuration-reference.md#providers-models-and-prices).

## The splice manager

The composer at the bottom of the console is the door to one Claude Code session, the splice manager. It runs in
`manager/`, a folder inside splice's global settings folder (the one that holds `splice.toml`, usually
`~/.config/splice`), and its job is to help you run splice: read its status and logs, explain what it sees, change its
configuration when you ask, and message your other sessions for you through Claude Code's own `ListAgents` and
`SendMessage`. The composer itself never messages other sessions. There is one manager: the console resumes it when it
exists, and asks before starting it the first time, along with the head it runs on. Changing the head later keeps the
conversation. The control API exposes the same as `GET /api/manager`, `POST /api/manager/start` and
`POST /api/manager/head`.

### The composer's controls

With the conversation open, the foot of the sheet shows what a Claude Code status line shows and the controls a composer
needs:

| Control | What it does |
| --- | --- |
| Context bar, with Compact | How full the manager's context is, tokens used of its window in the status line's colours |
| 5-hour and 7-day bars | How much of its account's windows is spent |
| Head chip | The head it runs on |
| Model and effort chips | Type Claude Code's own `/model` and `/effort` into the manager's terminal and press the plain Yes on the "Switch model?" or "Change effort level?" question Claude Code asks while its cache is warm |
| Stop | Ends the turn in flight and keeps the conversation |
| Up arrow in an empty field | Brings back what you last said |

A model or effort change keeps the session, its transcript and its cache; the one cost is that the whole conversation is
read again after a change. The model menu lists the models the head offers; Claude Code refuses any other name and the
composer says the manager did not switch. Claude Code also saves a typed model or effort as its head's default, and in a
test splice's next launch of that head wrote the file again, so the saved value did not reach later sessions. The chips
rest while the manager works.

### Its files and its fence

The installer creates the manager's files in `manager/`: a `CLAUDE.md` with its standing instructions, a
`.claude/settings.json` that fences what it may read and run, and the `splice` skill under `.claude/skills/`.
`splice init` prints where the folder is, and every daemon start refreshes the files from the running release, so an
auto-update brings the new ones. A file you edit is never overwritten: splice keeps your copy and stops updating that
file.

The settings folder one level up holds your secrets: `keys.toml`, `key-ledger.json` and the login tokens under `auth/`
([Credential locations](providers-and-accounts.md#credential-locations)). The manager's messages go to the head you
pick, which can be a hosted provider, so it works one level down, where nothing secret is kept, and Claude Code asks you
before it reaches outside `manager/`, `splice.toml` included. Its settings also deny reads and edits of those files and
of splice's state folder, which holds the management key, and keep Claude Code's default permission mode, so a command
you have not allowed asks you. Its instructions tell it never to read, print or send a key. This is a fence, not a vault:
a command you approve can still read what it names.

## Related pages

- [providers-and-accounts.md](providers-and-accounts.md): signing in, account pools, pins and API keys on Accounts.
- [cli-reference.md](cli-reference.md): `splice console`, `splice sessions`, `splice usage` and the control API routes.
- [configuration-reference.md](configuration-reference.md): `[daemon]`, serving knobs and rate cards.
- [modes-and-extensions.md](modes-and-extensions.md): modes, including `ccd_session`.
- [mcp-hub-and-sync.md](mcp-hub-and-sync.md): the MCP servers list on Settings.
- [claude-desktop.md](claude-desktop.md): Desktop policy and harnesses on Settings.
- [peers-and-harnesses.md](peers-and-harnesses.md): sessions that message each other.
- [team-server-mode.md](team-server-mode.md): the server page, per-person keys and budgets.
- [disk-and-privacy.md](disk-and-privacy.md): `budgets.json`, `account-pins.json` and the other state files.
- [architecture-and-troubleshooting.md](architecture-and-troubleshooting.md): `splice doctor` and logs.
