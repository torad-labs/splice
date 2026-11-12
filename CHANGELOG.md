# Changelog

## Unreleased

Changes on main since v0.4.0. They take a version's heading when that version is cut.

### Added
- **splice tells you when a command is on its last login.** A command with several logins moves to the next when
  a plan limit is reached, and nothing said when it reached the last. On Nov 12, 2026 every Claude login on one
  machine ran out of Fable one after another and eleven sessions waited three hours, while the person had resets
  to spend (Marcos: "just let me know when we're on the last account close to hit limit and I'll call a
  reset"). The daemon now checks each command's logins once a minute, for every model a turn of the command
  asked for in the last hour, so the turns of helpers on a small model never hide the model the sessions wait
  on. When only one login can serve a model and it has used 80% of a limit, splice says so, with the login, the
  limit and when it resets, and says it again at 95%. When no login can serve, it says that and when the first
  comes back. Each
  is said once, a restart included, and again only when it returns after half an hour of not applying, so a
  login that drops out for a few minutes repeats nothing. It is posted to the webhook saved in the alert settings, and handed to a
  program of your own set in `splice.toml`:

  ```toml
  [daemon]
  alert_command = ["/usr/local/bin/notify-me", "--from", "splice"]
  ```

  The list is the program and its arguments, run with no shell. The message arrives on its standard input and
  what the program prints is discarded. The first time a command is set it is sent one line saying what it will
  be used for, so a wrong command shows that day. The key is read at the daemon's start and can be set only in
  the file. A command with one login says nothing: it has no last one to be down to.

### Fixed
- **A script call that is cancelled as its worker dies is cancelled, not failed.** On a ChatGPT command with code
  mode, a call into a script's worker that was cancelled (the turn was stopped, or its client left) could end
  with "Code-mode worker was stopped; source was not rerun" when the worker died of something else at the same
  moment. The cancelled call now ends as cancelled.
- **Usage counts the logins that have used up a model.** The head of Plans and keys read "0 of 10 logins at their
  limit" on Nov 12, 2026 while six of eight Claude logins had used up Fable, the model every session was asking
  for: it counted a login's own windows and left each model's week out. It now also says, for a model some login
  cannot serve, on how many ("6 of 8 with Fable used up"), the model out on most logins first and at most two. A
  login counts when that model's week reads full or when its provider refused the model there and the reset has
  not come, the two records Accounts and `splice status` read.
- **The session list no longer rereads every ended session from disk each time it is asked for.** The console
  asks for the list every five seconds on Sessions, Models and Teams, and so do `splice status` and anything
  else that reads `/api/sessions`. On Nov 12, 2026 one reply on a machine with 3,141 ended sessions read
  14.8 MiB in about 32,000 reads from disk. The record of ended sessions kept in memory held 2,048, fewer than
  the machine had, and the list is walked in one fixed order, so each record was dropped just before it was
  asked for again. Every row also checked the settings file five times and the teams file once, and, for an
  ended session on a Claude login, read every Claude login's files to find that login's name. The memory now
  holds 32,768 records, and one reply reads the settings, the teams and each login's name once. The reply is
  unchanged.
- **The session list no longer hashes a command's whole perf file each time a turn ends.** To name the login
  behind each session the list keeps an index over the command's perf file and reads only what was appended
  since its last look. Beside that it hashed the file again, from the index's start to its end, after every
  append, for a proof that is read only on a filesystem that reports no change time. On Nov 12, 2026, with the
  fix above installed, half the replies on a busy machine still read 33 MiB: the Claude command's perf file
  was 32 MiB and grew about every three seconds. The hash now goes on from where the last one ended and reads
  only what was appended.
- **A restart no longer cuts an answer that is already arriving on a Grok, OpenAI or OpenRouter command, on any
  other command that speaks the chat-completions API, or on a ChatGPT command, whether or not it runs code mode.**
  Only a command that speaks Anthropic's API kept such an answer until now (#523). A chat-completions turn keeps
  nothing on disk, so it only had to be allowed. A Responses turn (Grok, OpenAI, ChatGPT) keeps its
  conversation's reasoning on disk, and the next daemon read that directory once, at its first request: an
  answer finished after that would have left the conversation's next turn without its reasoning. The retiring
  daemon now writes each record to disk before it hands the client the end of the answer, and the next daemon
  reads a conversation's file again when another daemon has written it. A ChatGPT command that runs code mode
  keeps each conversation it is answering in the retiring daemon until that answer ends, and the next daemon
  reads the conversation from disk at its next request, so that request carries the scripts the answer ran (#525).
  A script that was still running, or waiting on Claude Code, when the answer ended does not survive the restart:
  the model is told once that it was lost and runs it again. Still cut, as before: a Muse command (it keeps where
  each conversation runs in a file two daemons would write over), and a local runtime with `slot_affinity` on
  (the next daemon would hand the answer's slot to another conversation).
- **Accounts and `splice status` show a login out of a model from the moment its provider refuses it there.**
  splice keeps two records about a login: the usage its provider last reported, and a hold it sets when the
  provider refuses a turn, which lasts until the reset the refusal named and decides where the next turn goes.
  The screens drew only the first. On Nov 12, 2026 a login refused for Fable went on reading 99%, so its card
  showed room and `splice status` counted Fable used up on six logins of eight when seven could not serve it,
  and a login reset at its provider read 0% and looked open while its hold still stood (Marcos: "after I reset
  it I saw it showing upo in SPlice right away"). The accounts reply now carries each login's held models
  (`models_held`, each with its claim and the reset the provider named). A card says "Fable used up until ..."
  for a held model whatever its bar reads, and `splice status` counts that login among the ones the model is
  used up on. `splice status` names the login the command's next turn goes to for the model its turns ask
  for, and "switched from ... to ..." is recorded only for a turn of that model: a new session's first turn on
  a small model starts on a login that is out of the sessions' model, which read as "switched from Seek Apps to
  Ethan: primary account reset" a minute after Ethan ran out of Fable.
- **A `claude` that goes through splice is named `claude`.** With plain `claude` wrapped, the console and
  `splice status` still named its command `claude-splice`, the name of its table in `splice.toml`, so a person
  who only ever types `claude` saw a second Claude he never starts (Marcos, Nov 12, 2026: "why ... is claude and
  claude-splice two identities? We use Claude only via splice"). While `claude` is wrapped, `/api/heads` and
  `/api/status` answer `claude` as that command's name, so Sessions, Accounts, Usage, Requests and Models show
  it, a session's resume line reads `claude -r <id>`, and the command column of `splice status` reads `claude`.
  Unwrapped, they name `claude-splice` as before. The head's key, its table in `splice.toml` and the
  `claude-splice` command itself do not change.
- **A login reset at its provider is used again at once.** When a provider refuses a login for a spent plan
  window, splice holds that login until the reset the refusal named, and only an answered turn ended the hold
  sooner. No turn is sent to a held login while another is open, so a login reset early at the provider stayed
  out of use: on Nov 12, 2026 one read 0% of its Fable week after a reset and would have been passed over for
  six more days while the last open login ran out. A usage reading that shows a held window lower than it last
  read, and below full, now ends that hold, for one model's week or for the whole login. The waiting turns are
  asked to move to it, and the daemon's log names the login and the window. A figure that reads below full
  without having fallen ends nothing: a week read at 99% a few minutes before its refusal reads 99% after it
  too.
- **Accounts marks where a command's next turn goes for the model its turns ask for.** A plan can use up one
  model's week while its own week has room. The page asked the daemon where the command's next turn would go
  without naming a model, so the mark could sit on a login that refuses the model every session of the command
  uses: on Nov 12, 2026 it sat on a login with Fable at 100% while the sessions ran on the one login that had
  Fable left. The daemon now answers `next_target` and `following_target` for the model most of the last 64
  turns of the command's sessions asked for. A turn Claude Code sends for a subagent is not counted: sessions
  that hand their reading to helpers on a smaller model send more helper turns than their own, and the mark
  would have followed the helpers' model.
- **The Claude command has one login: Claude Code's own.** Started by its own name, `claude-splice` ran Claude
  Code over a second home, `~/.claude-claude-splice`, with a sign-in of its own, and the command counted that
  home as its first login beside the one in `~/.claude`. A machine that signs in to `claude` and nowhere else
  carried a second, empty Claude login in the pool, in `splice status` and in the daemon's choice of where a
  session starts (Marcos, Nov 12, 2026: "why ... is claude and claude-splice two identities? We use Claude only
  via splice"). `claude-splice` now launches over `~/.claude`, as a `claude` that goes through splice does and
  whether or not it does: the same login, settings and sessions under either name. The second login is gone
  from Accounts, Usage, `splice status`, `splice doctor` and `/api/claude-logins`. If you signed in inside
  `claude-splice` and never in `claude`, sign in once more with `/login`; the old sign-in stays on disk in
  `~/.claude-claude-splice` and nothing reads it. `splice login claude-splice --label <name>` saved and switched
  copies of that second sign-in; it now changes nothing and says where to sign in. It still works on a second
  Claude command declared under another name.
- **A turn waiting on a used-up model goes the moment a login can take it.** On Nov 12, 2026 Anthropic answered
  429 for one model on the Claude command's logins from 5:58 AM CT, and eleven sessions on one machine each
  held one request open for about three hours. A waiting turn woke only for an account added under a new name.
  Signing Claude Code in to an account with room at 9:05 AM kept the login's name, so none of them heard it:
  the first answer on that account went to a new request, and each waiting one ended when its client closed
  it. A waiting turn is now asked to move when an account is added, when a login's credential changes to
  another account under the same name, and when Try again is pressed on a login's card. At that moment it
  forgets which logins refused it earlier and asks them again. A turn that no login can take keeps waiting; it
  used to end on its limit when a login joined that could not serve it. The command's log says how many
  waiting turns were asked: `a login joined: 11 waiting turn(s) asked to move`.
- **A session waiting on every login says so, and so do `splice status` and the Accounts page.** The line a
  waiting session printed named one login's limit. With more than one login it now reads "Every Claude login
  is at its usage limit for this model, the first until 1:00 PM. This turn waits and continues then, or as soon
  as a login frees or one is added." A plan can use up one model's week while the plan's own week has room, and
  nothing said so: `splice status` counted such a login as open, and its card showed only the percentage.
  `splice status` now adds "Fable used up on 6 of 9 logins", or "on every login, first back" with the time,
  and each such card reads "Fable used up until Sun 12:00 PM".
- **Removing an added Claude login is written to the daemon's log, and takes its hold with it.** A folder under
  `~/.splice/state/claude-accounts/<head>/` left no line when it was removed: on Nov 12, 2026 a login signed in
  again at 9:04:59 AM was gone 18 seconds later, and the log could not say what took it. What the login was
  held for stayed on disk under its label, so the next account filed as `account-8` would have read as out of
  one model until the removed login's reset. The file now goes with the login, and a login filed under a label
  starts with none.
- **A restart no longer cuts an answer that is already arriving.** A restart stopped the daemon and then started
  the next, so every answer in progress ended with the restart line. Claude Code sends a request again when none
  of its answer has arrived, but not one cut partway: it shows "API Error: Server error mid-response" and the
  session stops until someone types. On one machine, 8 of the 14 answers cut that way between Nov 10 and
  Nov 12, 2026 left their session stopped, for two to five minutes in seven cases and for two hours in one.
  Under splice's systemd unit the launcher now keeps the unit's first process and the listening
  sockets and runs the daemon as its child, and a restart starts the next daemon beside the running one. The
  running one stops taking requests, writes its books, hands over, and finishes the answers already arriving,
  for up to 15 minutes, while the next daemon serves every new request on the same ports. `splice restart`,
  `splice upgrade`, the console's Restart and a console save that restarts all go this way, and each finished
  answer's request row, tokens and cost are counted by the next daemon, once. Still ended with the restart line:
  a request its provider had not begun answering, which Claude Code sends again; a turn on a command that does
  not speak Anthropic's API (ChatGPT, Grok, Muse, OpenAI-compatible and local models); every turn on a daemon
  started outside the unit or on macOS; and the first restart into this build. An upgrade that brings a newer
  launcher restarts the unit whole, once, so that launcher starts. The daemon is now the launcher's child, not
  the unit's first process, and `/health` names its `pid`. On a machine whose own tools take a unit's first
  process for the service (an out-of-memory keeper that protects that process and not its children),
  `SPLICE_HANDOVER=off` in the unit's environment keeps the daemon first, and restarts as they were (#523).
- **A daemon stopping on a machine with no `~/.local/bin` no longer logs an uncaught failure.** The watch that
  keeps a wrapped `claude` wrapped waits on a timer where that folder is missing, and the stop interrupted the
  wait, which the daemon's log reported as `UNCAUGHT on wrap-guard` with a stack trace.
- **A login its provider refused stays held across a restart.** A login refused for its credential, or blocked by
  its account's organization, was held only in the daemon's memory. Each restart forgot the hold, so the next turns
  went to that login again and were refused again: on Nov 11, 2026 a blocked login met four to six refusals after
  each of the day's restarts, and its card on Accounts read as one a turn could take until the first answer came
  back. The hold is now kept in the state folder, beside what the provider said about that login's limits, and put
  back at the start: on the same account where the provider blocked the account, and on the same credential file
  otherwise. A sign-in made while the daemon was down ends it, as one made while it runs does.
- **A session's cost on the status line survives a stop nothing closed.** Each session's running total is written
  at most a second behind its turns, and a daemon stopped by a kill, a crash or a power cut used to throw those
  totals away at the next start, because the last second's turns might be missing from them. A session days long
  was then priced from the last rows of its command's request log alone: the daemon's log shows the totals
  starting over after each of two machine stops, on Nov 10 and Nov 11, 2026. The totals are now kept and marked
  as counting from that start, so the session reads the figure it had with the ≥ mark. A session that began
  before its total reads the larger of that total and the log's last rows, where it read only the rows.
- **"Saving stopped" on Settings keeps its time across a restart.** When prompts and answers stop being saved (the
  disk at its free-space floor, or the size budget spent), the card says since when. That time lived in the
  daemon's memory, so after a restart the card showed the first refusal since that start: on Nov 11, 2026 it
  read "Saving stopped 6:37 PM" a minute after a restart, while nothing had been saved since 6:00 PM. The time
  is now kept beside the saved requests and removed when a request saves whole again (#518).
- **A full disk no longer costs the prompts and answers already saved.** Saved requests live inside a 32 GiB
  budget, and a full budget removes the oldest day to make room for the newest. The free-space floor on the
  disk removed days the same way, so on a disk that other data kept under the floor, a day's saved prompts and
  answers went soon after the day ended, to save the next ones. On one machine every day from Oct 29 to
  Nov 11, 2026 went within three days of its end, half of them within four hours, and nothing said so. Under
  the floor a new request is now refused, with the floor for its reason on Settings, and every saved day stays.
  When the budget does remove a day, the daemon's log names it.
- **Models shows each model its own compaction rule.** The page found a model's rule by looking for the model's
  id inside the rule's label, so with rules for two models whose ids begin alike (`claude-opus-5` and
  `claude-opus-5-5`), one could be shown the other's. Each rule now carries its model. On the same page, the
  event list said Restored for any log line holding the word, and the only lines that hold it report a
  failure; that event is gone, and Settings re-read now appears when an edit to `splice.toml` changes a
  command's windows.

### Changed
- **The console reads names, not sentences.** Twelve replies of the daemon's API now carry a short name beside
  the sentence they always sent (why saving stopped, why a command cannot carry the manager, what a check
  found, why a team's repo was refused, and so on), and the console's pages choose their words by those names.
  A page's wording no longer depends on how a sentence in the daemon happens to be phrased. The sentences are
  unchanged, and so is every reply's existing field.

## splice v0.4.0: teams across models, resume on another model, and one-command setup - 2026-11-11

### Highlights
- **GPT keeps its reasoning across prompts.** `replay_reasoning` now ships on for every head on the Responses
  dialect: every earlier turn's reasoning goes back to the model until the conversation compacts, as Codex sends it.
  Off, a session lost its reasoning at each new prompt and each peer message. An install whose splice.toml says
  `replay_reasoning = false`, as the 0.3.x template wrote it, keeps it off until that line changes.
- **Local models get their reasoning back too.** On the `openai-chat` dialect, each earlier assistant turn sent to a
  local runtime carries the reasoning the model wrote, as `reasoning_content`, so its chat template renders the
  history the model produced instead of an empty think block on every turn. splice's own wait notices never ride,
  and a hosted vendor's requests are unchanged.
- **Tool callbacks progress as results arrive.** Code-mode cells can issue up to 32 callbacks while encoded frames remain bounded. A partial result batch settles its ready promises, retains unfinished callbacks and terminal errors for recovery, and continues without waiting on a paused source stream.
- **Deferred tool history stays attached to its callback.** Canonical history preserves generated declarations beside their relocated function calls, preventing a returned batch from abandoning its running script.
- **Responses heads retain richer conversations.** Mixed tool results keep their ordered text, images and files. Assistant phases survive history resends. Deferred tool definitions and withdrawals retain their original boundaries. Public request controls and non-strict function schemas no longer depend on Codex mode. The JavaScript executor, authentication and internal Codex routing stay provider-owned.
- **Optional WebSocket prefix preparation.** Explicitly enabled Responses WebSockets can prepare reusable instruction and function-tool prefixes without generating an answer. Codex retains its own negotiation and narrower prefix policy. Continuation state rejects stale responses. WebSockets remain off by default.
- **Less code-mode registry bookkeeping.** Membership and expiry lookups use indexes, and journal appends reserve for their selected operation before a full checkpoint is encoded. A conversation append admits its changed records first, which removes a whole-conversation reservation that had refused Claudex startup on a large journal; uncertain state, removed records and journal compaction still reserve a full checkpoint. Live response-time and memory effects remain to be measured.
- **Sessions can run turns without the key that controls splice.** In 0.3.x, sessions other than
  `claude-splice` held that management key. Upgrade, rotate the key, then relaunch your sessions ([Security](#security), [Upgrading from 0.3.x](#upgrading-from-03x)).
- **`splice rotate-key` replaces the management key while the daemon runs, and sessions carry on.** A session splice
  launches reads its turn key through Claude Code's key helper, so after a rotation its next request reads the new
  key and the turn goes on, on the same head, with nothing typed lost. `splice sessions relaunch` relaunches each
  session launched before the helper, each session whose launch proof carries the old turn key, and each session whose
  hosted MCP servers hold the MCP access key the rotation replaced, on its own head, resumed; run it after the rotation
  until it reports none left.
- **`splice upgrade`** fetches, verifies and stages a release, waits for in-flight turns, restarts
  the daemon and runs doctor. `--rollback` puts the previous release back.
- **Account pools on the ChatGPT, Grok, Kimi, Muse and Claude heads.** OAuth heads add labelled
  accounts through `splice login <head> --label <name>`. Claude heads add accounts through
  `POST /api/auth/<head>/login`, and `claude-splice` can also select plain claude’s login and claude-splice’s own login without
  refreshing their credentials. Selection is per head and sticky per session. Logins without a
  full usage reading are preferred, with pins or saved orders guiding selection among them. The
  default fallback prefers the soonest weekly reset, with the five-hour reset breaking ties. A full reading alone does not
  block a turn.
- **A Use now pin survives a restart and lets go when its login runs out.** The command's pin and each session's pin are
  saved, so a daemon restart no longer drops them. A pin lets go at the first limit its login reads that it did not read
  when Use now was pressed: a plan window spent, the provider holding the account, or a usage reading at 100%. While a
  pinned login cannot serve, its card says why and which login the turns use instead. A model's weekly cap holds that
  model alone, so when one login hits its 5-hour limit, an Opus turn moves to a login whose only hold is Fable's weekly
  cap.
- **One engine decides every retry, and a failure reads as one plain sentence.** The retry count, the backoff curve and
  the reasoning-fold settings are retired ([Upgrading from 0.3.x](#upgrading-from-03x)). Each turn's sends are counted
  on one ledger, and a turn ends within 24 sends outside holds. A 429 that names no window moves the turn to another
  pooled login before any answer reaches Claude Code, and otherwise waits 2, 4, 8, 16, 30, 30 and 30 seconds, a budget
  the whole pool shares. A limit that states its reset holds the turn until it lifts, up to 7 days, with Claude Code
  hearing only keepalives; a path that does not answer is waited on for about two minutes. What Claude Code prints is
  `[splice] <what happened>. <what happens next>.`, never a provider's raw reply.
- **Performance is measured and kept on your machine.** `splice perf` and the console's Performance panel read bounded
  local history, request evidence, objectives and alerts, and say in every answer what a reading covered and what it
  lost. Prometheus and OTLP export and bounded profiling are optional and stay off until you turn them on; a default
  install needs no collector ([Added](#added), `docs/observability/README.md`).
- **Local models are first-class** on the `openai-chat` dialect. splice asks the runtime what it
  serves and refuses a model Ollama, LM Studio or vLLM does not list. With `slot_affinity = true`,
  llama-server conversations keep their own slot. Local heads
  report token usage, so Claude Code auto-compacts, and their errors say what happened.
- **Requests and replies stay on your disk for your history window.** Left unset, trace follows
  `historyRetentionDays`; `traceRetentionDays` sets its own. Sign-in headers are removed, and only you can read
  them. A capture change applies from the next request; turning capture off stops new writes, and the next daemon start
  deletes that head's kept trace days.
- **One-command setup.** `splice add codex`, or `grok`, `kimi`, `muse`, `deepseek`, `openrouter`,
  `claude` or `local`, signs in where the service needs it, saves the head and installs its command. An optional turn checks
  that command; a failed check leaves the head saved.
- **Resume on another model.** `-r` lists sessions from every command that shares projects.
  A shared session another head wrote goes on as a copy under its own id when its rows would move (an isolating head copies it in under the same id), with earlier replies moved onto the resuming head's
  model; the original stays as its head wrote it.
- **New commands:** `splice add-model`, `splice sessions`,
  `splice perf`, and `splice doctor --json` (a shareable, redacted report).
- **New heads and modes:** `claude-muse` (a Meta Muse Code subscription); Claude head mode (wrap
  the default `claude` command); per-head system prompts, including a `strip` mode; custom
  compaction instructions; shared MCP hosting.
- **Temple MCP starts with Splice.** A durable Temple registration supplies Claude sessions and native Codex with the same named server. Its local tool definitions remain available before the Temple app starts. Native Codex receives only MCP-scoped access, without changing its permissions.
- **The MCP hub on Linux.** Every session splice launches gets its MCP servers from one hub that outlives the daemon,
  shared between sessions by default, so an upgrade or a crash of splice drops no session's tools ([Added](#added)).
- **Restarts wait for compactions.** Unless you choose `--now`, `splice restart`, `splice upgrade`
  and `POST /api/daemon/restart` wait for compactions in flight, up to 30 minutes, the default turn cap; a daemon whose
  cap is raised is still waited on for 30 minutes. A finished answer whose client disconnected is kept on disk for its
  identical retry.
- **Installing needs no GitHub account.** The downloaded release jar and launch shim are checked
  against the release's checksums, and build provenance is verified whenever `gh` is signed in.
- **Releases are downloaded from splice.torad.ai/download.** `curl -fsSL https://splice.torad.ai/download/install.sh | bash` installs
  the newest stable release, and `splice upgrade` and the beta channel read the same site. The GitHub release keeps the
  notes and carries no file.
- **Installing and launching need no Node.** The launch shim is plain `sh` and `curl`, so a machine with Java and a
  natively installed Claude Code runs `claudex` with no Node on it. `install.sh` and `splice doctor` no longer ask for
  it, and the installer ends on one next step, `splice setup`.
- **Code mode is on by default for eligible ChatGPT models.** The model batches tool calls in a
  short JavaScript program, while Claude Code still checks permissions and runs each operation.
- **Teams.** Sessions on different heads, say Claude and GPT-6 Astra, work one goal: each gets
  its role and team goal, with the lead's address once that slot is bound, and the teams API reports recorded message
  requests, sampled activity, estimated cost and plan-window use per role slot. A session started in a team's repo on
  a member's command can join that member by rule, and the board reads by command, by role or as a timeline.
- **Every usage-cost dollar figure is an API-rate estimate.** The status line shows the bare figure, `$0.85`, with no label, a
  budget's warning says "an estimated $2.50 in API cost", and a subscription head bills no token at
  all.
- **The console wears the Splice brand.** The bar shows the new splice mark, the tab shows a favicon that follows the theme, every colour comes from one tokens file, and the light theme now holds 4.5:1 text contrast on its Paper wall, with darker provider colours.

### Upgrading from 0.3.x
0.3.x has no `splice upgrade`. Re-run the installer pinned to this release. Releases are downloaded from
splice.torad.ai/download from 0.4.0 on, so the GitHub release carries the notes and no file:

```bash
curl -fsSL https://splice.torad.ai/download/v0.4.0/install.sh \
  | env SPLICE_VERSION=v0.4.0 bash
```

- **The installer leaves the running 0.3.x daemon alone.** The next launch of a head's command,
  such as `claudex` or `claude-grok`, replaces it: the launch shim sees the old version on `/health`, stops that daemon and starts
  0.4.0. The launch shim needs only `sh` and `curl`.
- **Config and credentials keep their contents, and the state root stays where it is.**
  `splice.toml` and its backups are tightened to owner-only (0600). An install
  made before 0.4.0 keeps `~/.claude-codex/state` and its management key. 0.4.0 tightens that
  directory to owner-only (0700).
- **Rotate the management key before relaunching sessions.** A 0.3.x session other than
  `claude-splice` still holds the management key and can run turns until rotation, but its old
  keyless status line is not answered by 0.4.0: it prints "splice updated: relaunch this session for its status line" instead of figures. Under 0.3.x the management key sat in those sessions'
  environment, where the model's tools could print it into a transcript. Run `splice rotate-key`:
  the daemon replaces the key while it runs, and the old key is refused at once. Then run
  `splice sessions relaunch`, which relaunches each 0.3.x session on its own head, resumed, until it
  reports none left. Its new launch reads the current turn key through Claude Code's key helper.
- **A provider on a loopback `base_url` with the `openai-chat` dialect is now a local runtime.** Ollama, LM Studio and vLLM rows
  are checked against what the runtime serves at boot: a row it does not list, or a
  `context_window` larger than it reports, is a config finding, so boot refuses and lists it with the file's
  others until it is fixed. A runtime that is down boots as before. `local = false` on the provider keeps the 0.3.x
  behaviour.
- **Three defaults changed for a 0.3.x file that never set them.** ChatGPT heads on the Responses
  dialect run code mode (`code_mode = false` in the provider's `quirks` turns it off). A Claude head shares its `projects`
  tree with `~/.claude`, merging by hard link on its first launch (list `share` without `projects`
  to keep it private). MCP hosting is on (`mcp_hosting = false` under `[daemon]` turns it off).
- **Settings 0.4.0 retired refuse boot while any still sets one.** A `splice.toml`, splice's environment or the settings
  splice saved in its state folder that still names a retired setting is a boot finding. splice does not start: it
  lists every finding at once, with the line where the file has one and the reason, and quotes no value you wrote. From
  a terminal, the command that met the refusal offers a fix session that removes the line, clears the saved value or
  names the variable to unset. `splice check-config` prints the same list and exits 3 when this build would refuse
  your file. A retired setting is refused wherever it sits: `[defaults]`, a head's `[heads.<key>.overrides]`, `[daemon]`
  for the fold fields, the environment, or the saved settings.
- **`upstreamRetries` and the three `retryBackoff*` settings are retired, with nothing to set in their place.**
  `upstreamRetries` (`CLAUDEX_UPSTREAM_RETRIES`), `retryBackoffBaseMs` (`SPLICE_RETRY_BACKOFF_BASE_MS`),
  `retryBackoffCapMs` (`SPLICE_RETRY_BACKOFF_CAP_MS`) and `retryBackoffJitterPct` (`SPLICE_RETRY_BACKOFF_JITTER_PCT`)
  gave way to the retry engine, which decides how often and how soon a failed request is sent again from what failed.
- **The four `fold*` settings are retired, with nothing to set in their place.** `foldReasoningModels`,
  `foldMaxContinue`, `foldMarkerText` and `foldMaxTier` (`CLAUDEX_FOLD_REASONING_MODELS`, `CLAUDEX_FOLD_MAX_CONTINUE`,
  `CLAUDEX_FOLD_MARKER_TEXT`, `CLAUDEX_FOLD_MAX_TIER`, and `fold_reasoning_models`, `fold_max_continue`,
  `fold_marker_text` and `fold_max_tier` under `[daemon]`) gave way to splice continuing on its own a Codex answer whose
  reasoning the backend cut short.
- **`codexAuthPath` and `grokAuthPath` are retired; each provider's `auth.file` replaces them.** `CODEX_AUTH_PATH` and
  `GROK_AUTH_PATH` no longer move a sign-in file, since the daemon, `splice login` and `splice status` now read every
  sign-in file from the provider's `auth.file` under `[providers.<key>.auth]`, which is where `splice login` writes it.
- **Four timing settings belong to the whole daemon.** `upstreamTimeoutMs`, `firstByteTimeoutMs`, `streamIdleMs` and
  `stallReanchorMs` set under a head's `[heads.<key>.overrides]`, or saved for one command, are boot findings too: set
  them under `[defaults]`. `upstreamTimeoutMs`, the whole-turn cap, is 30 minutes by default.
- **`claudex -c` continues only sessions 0.4.0 began.** A session begun under 0.3.x is not in the
  head's `splice-sessions.json`, so a bare `-c` starts a new one. `claudex -r <id>` resumes it.
- **The installer records 0.3.x as the previous release,** so `splice upgrade --rollback` has a
  target.
- **From 0.4.0 on,** `splice upgrade` fetches, installs and restarts in one command.
  It does not rotate the management key or relaunch sessions.

### Security
The management key opens the whole control plane: every head's system prompt, every transcript,
config writes and daemon shutdown. In 0.3.x it left that boundary through:
- `ANTHROPIC_AUTH_TOKEN` in every session except `claude-splice`, inherited by its tools.
  It was seen in transcripts sent to model providers.
- The launch shim's curl command line on every launch, and `splice dashboard` output even
  when piped. Other accounts can read command lines through `/proc/<pid>/cmdline`.

0.4.0 closes each path:
- **A session holds a turn key.** The turn key is derived one-way from the management key
  (HMAC-SHA256 under its own scope), so holding it reveals nothing about the management key. It
  is shared across the install and opens every head's turn, status-line and resume-hook routes,
  but no management route. The management key still opens everything and still runs a turn.
- **No key in `settings.json` or argv.** The statusline and the resume hook read the bearer from
  `<state>/turn-auth-header` (0600), which each launch keeps current,
  rewriting it after rotation or deletion.
- **DNS rebinding is refused.** The control plane and every head refuse a request whose `Host`
  names anything but `127.0.0.1`, `localhost` or `[::1]`, before any route runs. Each refused
  Host is logged once, up to 16 names, then counted at each doubling.
- **State stays owner-only.** The daemon holds the state root (when splice chose it), the state
  dir and the logs dir at 0700. It logs any directory that stays open, with the mode it kept.
- **A client-auth head never forwards splice's own keys upstream.** A turn that carries the turn
  key or the management key in `Authorization` or `x-api-key` is refused with a 401 that names
  the header and the variable that set it. This includes a key behind a blank first header line.
- **`splice dashboard` is removed, and no command prints the management key.** `splice console` opens
  the console through an owner-only redirect page that carries the key in the address fragment, never on a command line or in output.

splice is single-user, and its boundary is your Unix account. A process running as you can read
`mgmt-key`, as it can read your other credentials. What 0.4.0 closes is every place the key left
that boundary: transcripts sent to providers, argv other accounts can read, and pages on another
origin.

### Known limitations
- **Some source requirements have no Responses equivalent.** Enabled document citations, programmatic-only tools and unsupported account or geographic requirements are refused before sending. Valid cache hints use automatic upstream caching without promising source breakpoints or retention. Foreign file identifiers are not reused across vendors.
- **`splice install --all` leaves the MCP hub off.** It writes the hub's systemd units and enables nothing; `splice setup` turns the hub on only over units it recorded writing.
  Until setup turns it on, or you run `systemctl --user enable --now splice-mcp-front.socket`, which `splice doctor`
  names, each session starts its own MCP servers, as in 0.3.x.
- **Budget alerts reach the webhook, not the desktop.** A `warn` budget posts to the saved webhook
  and writes the head's log. The `desktop` alert flag is saved, but nothing turns it into a
  desktop notification yet.
- **A rate limit after text has streamed can end the turn.** ChatGPT, Muse and other Responses
  heads wait out the limit and resume the answer, up to five times, unless the cut reply had already started a tool
  call; then the turn ends, so re-send it. Other heads resume only when measured to continue, such as Kimi or
  a provider with `reanchor_prefill = true`; otherwise re-send the turn. Before any text,
  splice can retry the whole turn.
- **A restart ends the turns in flight.** Each running turn that is not a compaction ends at once with "splice
  restarted during this turn.", and the next daemon takes the session's next request. Claude Code sends a turn cut
  before its text began again on its own; a turn cut after its text began ends there, so re-send it. Compactions are
  waited for unless you restart with `--now` (see Changed). `splice upgrade` waits up to 30 minutes for every head to
  go idle, then leaves the release staged unless you rerun with `--now`.
- **Usage's Results by command counts every request, whatever the project or filters picked above.** It reads its own
  1 hour, 24 hours or 7 days: the page's 5 hours reads as 24 hours and its 30 days as 7 days. Its switch names the
  window it read, and with a project or filter picked its heading says "All requests". The header's door to it says
  the same, as in "3.0% failed in 24 hours, all requests". Results for one project, or for the page's own 5 hours or
  30 days, are not built yet.
- **What performance collection costs has not been measured.** The benchmark receipts in `docs/benchmarks` are
  calibration evidence. The acceptance run over 36 days of retained history stopped at its 20-minute exchange timeout,
  and the proxy overhead and capacity matrix has not been run, so 0.4.0 makes no overhead, capacity or performance-target
  claim for collection, retention or export.
- **GPT-6.1 Sol's thinking often shows headings only on a ChatGPT subscription.** The subscription returns its
  reasoning summaries as headings without their paragraphs, in every request shape tried on 2026-10-30. Setting
  `summary = "concise"` under the head's `[heads.<name>.overrides]` brought the paragraphs back in some turns, not all.
- **splice is tested against Claude Code 2.1.322.** When a session runs a newer one, doctor and
  `splice status` report it; the status line warns once per session during the daemon's lifetime.
- **Claude Code's header reads `API Usage Billing` on every head but `claude-splice`.** Claude Code
  only has names for Anthropic's plans and the clouds that sell Claude, so it prints that for any
  other service. It does not mean Anthropic is billing you: each head uses the plan, key or GPU you
  set it up with, and splice's status line shows an estimate at API rates, never a charge,
  where splice has a rate card for the model. On `claude-splice` signed in with a Claude subscription,
  the header names that plan. An Anthropic Console API-key login is billed through that API.

### Added
- **Use one machine's heads from another.** `splice share on` offers this machine's commands to your other machines
  on its LAN and prints each one's address. On another machine, `splice remote add <address>` links the command, and
  running it starts Claude Code pointed at the first machine, as Claude Code is pointed at Vertex or Bedrock: every
  turn runs on that machine's logins, and the second machine keeps the address and nothing else, no credential and no
  copy of the models. `?auth=<login>` on the address picks the login, `?models=<id>,<id>` the models shown. A
  machine that can't be reached is said before Claude Code starts, never as "overloaded". Sharing is off until turned
  on, answers its own LAN only, and is plain HTTP with no token; see `docs/specs/lan-share.md`.
- **A wrapped `claude` never depends on splice being up.** When the launcher cannot get a launch for plain `claude`
  (no daemon, a failed handshake, no recipe), it runs Claude Code directly on your own login and says so once.
  Named heads still stop with the reason, since they have no direct path.
- **Named peers any Claude Code session can message.** `splice peer register <name>` creates a peer that every
  session on the machine sees in ListAgents and can SendMessage to, with no session behind it. A small helper
  process per peer journals each message with a delivery receipt, labels each sender `registered` or
  `unregistered` by whether its address names a live session, and can push each message to a webhook signed with
  HMAC-SHA256. `splice peer send --as <name>` sends under the peer's own name, which Splice writes itself, so a
  recipient can reply; `inbox` and `status` read what came back, and `status --json` prints a send's stored record
  and receipt for a program to read. Each peer has its own key, which opens only its
  own routes, and the management key cannot send as a peer. A user who never registers one gets no process, socket
  or file. Guide: `docs/peers.md`.
- **Anything can be a peer, and a retried send is safe.** A script, bot or harness registers with `splice peer
  register`, again at every start: a repeat answers the peer it already made, with the same session id and key file,
  where it was refused before. `--owner <pid>` binds a peer to any running process. `splice peer send --id <id>` makes
  a retry harmless: the same text to the same target under an id already sent answers that send again and sends
  nothing, an inbox journals a repeated message id once, and `--pid` refuses the send when another process now answers
  to the target. A peer may send 20 messages at once and 60 a minute after that; `splice peer limit <name> <per-minute>
  <burst>` gives it its own, up to 600 and 100. One `peer-host` process serves every peer, each under a small anchor
  pid of its own, and it exits 10 s after the last peer.
- **A Codex conversation can be a peer.** `splice peer codex attach` registers a native Codex thread as
  `codex-<thread id>`, so every Claude Code session sees it in ListAgents and a message sent to it arrives in the
  conversation as a turn. Delivery goes through the app-server that holds the thread. For a thread open in the ChatGPT
  desktop app, splice finds the app through the Codex home's private socket, and the app takes the message into its own
  conversation with its model, effort, permissions and workspace kept. `--peer-name <name>` gives a peer you already
  registered automatic delivery into a thread, keeping its session id, key and inbox. Each hand-over is journaled
  before it goes out and again with how it ended (`started`, `refused` or `unconfirmed`), so nothing is handed over
  twice, and a refused message gets a `dropped` receipt. A thread no app-server holds is refused with the reason:
  splice writes no Codex hook, and `splice setup` offers none.
- **The splice manager can drive the console.** `splice console <action>` works on the console page the person has
  open, the way their own hands would: `snapshot` lists the page and every control with a ref, `read` prints its words,
  `tap`, `fill`, `select`, `press`, `hover` and `scroll` act, `goto` moves between pages, and `highlight` draws a ring,
  with an optional note, around whatever the manager is explaining. Every act flashes a ring on its target, so the
  person sees what was pressed, and its answer waits for what the press started and says what changed, or that
  nothing did. A refusal says whether the act was done, not done, or may have run. The composer itself is never driven, an act never pulls the focus out of it, and a
  field that holds a password, key, token or secret is never read out. Bare `splice console` still opens the console.
  The manager may look, read, point and move freely, and asks first before a press that deletes, signs out, restarts,
  spends money, changes a setting or touches a running session.
- **The splice manager's composer has the controls of a composer.** The sheet's foot shows how full the manager's context
  is with Compact beside it, how much of its account's 5-hour and 7-day windows is spent, and chips for its head, its
  model and its reasoning effort. The model and effort chips type Claude Code's own `/model` and `/effort` and answer
  its "Switch model?" and "Change effort level?" question, so the same session, transcript and cache go on. Stop ends the
  turn in flight and the Up arrow recalls the last message. `GET /api/manager` carries the new `line`: the manager's model,
  effort and context as its status line last said them, kept in memory from each status-line post. A prompt that shows
  only Claude Code's grey suggestion (`Try "edit <filepath> to..."`) no longer counts as a draft, which refused the
  first message to every new session.
- **The splice manager's folder, installed and kept current.** `splice init`, which the installer runs, and every
  daemon start write a `CLAUDE.md`, a `.claude/settings.json` that denies the session splice's keys, login tokens and
  state folder, and the `splice` skill into `manager/`, a folder inside the global settings folder beside `splice.toml`,
  where the console's composer runs its one resident session. It is a subfolder because a session working in the
  settings folder itself read `keys.toml` on a plain `grep -rn x .` or `cat *.toml`, with no prompt. A refresh replaces only the files splice wrote and nobody edited.
- **The MCP hub: one copy of each MCP server for every session, kept through restarts.** On Linux x86-64 with
  `systemd --user` running, the MCP servers of each session splice launches run under two small native programs
  installed beside the jar: `splice-mcp-front`, which holds `127.0.0.1:3095` and every session's connection, and one
  `splice-mcp-holder` per server. The front outlives the daemon, so an upgrade, a restart or a crash of splice drops no
  session's tools. Each launch hands Claude Code one owner-only `--mcp-config` file, `<state>/mcp-hub/launch-<pid>.json`,
  naming every server Claude Code would load in that folder under its own name, so tool names do not change. claude.ai
  connectors and `http`, `sse` and `ws` servers stay Claude Code's. A plugin's servers move to the hub only on a Claude
  Code version where splice's own check found `--plugin-dir-no-mcp` works. A server starts the first time a session needs
  an answer the front has not kept, a shared one stops after `mcpIdleTimeoutMs` (30 minutes) with no calls, and one with a call in flight is never stopped.
  When the front does not answer, the launch says "this session starts its own MCP servers" and Claude Code runs its
  own. Anywhere else the daemon hosts MCP in process, as before.
- **MCP servers shared or one per session, asked once.** A first `splice setup` asks whether MCP servers are shared
  between sessions and saves the answer as `[daemon] mcp_share`; shared is the default. With sharing on, setup turns the
  hub on by enabling and starting `splice-mcp-front.socket`, but only over units splice wrote itself, which it records
  with their SHA-256 in its own state; units anything else placed are left as they are, and setup prints the command
  instead. The console's MCP section
  switches it, for new connections with no restart, and shows measured memory against one shared copy
  (`GET /api/mcp/memory`). Servers that keep state per process, such as Desktop Commander, or that need the session's
  folder stay one per session, with the reason.
- **Installing the MCP hub.** On Linux x86-64, `install.sh` installs `splice-mcp-holder` and `splice-mcp-front` beside
  the jar, from the jar's siblings, the checkout's build or the release, where each is checked against
  `sha256sums.txt` and attested like the jar; a rollback restores the previous pair with the previous jar. An update
  reloads a running front and never restarts it, since a restart would drop every session's MCP connection.
  `splice install --all` writes `app-mcp.slice`, `splice-mcp-front.socket` and `splice-mcp-front.service` to
  `~/.config/systemd/user/` only where no unit of that name exists. `splice init` writes `~/.config/splice/mcp-front.env`
  with the hub's state folder and the control port. `splice doctor` has an MCP hub row: on, never run here with the
  command that enables it, not answering, or off with the reason.
- **`splice listeners`** prints the control port and each head's port as JSON, or the config findings when boot would
  refuse, so a service manager can hand the daemon its sockets.
- **Settings changes reach the next request, with no restart.** The first-byte and re-send timeouts, the request body
  cap and read ceiling, the materialization budget, the quota poll cadence, the MCP host's idle window, server ceiling,
  handshake and request budgets, the status line's progress line, and the reasoning summary, display and replay
  settings are read when a request or wait needs them, so a PATCH governs the next one instead of the next restart.
  The retry count, the retry curve and the reasoning-fold settings are not among them: they no longer exist
  ([Upgrading from 0.3.x](#upgrading-from-03x)), and what a turn retries is decided by what failed. Reasoning effort
  stays restart-only on purpose: it is part of the prompt-cache key, so changing it per turn would void the cache.
- **Settings is a page of what happened and what governs it.** Silent models and Many agents at once open on
  counts read from real requests: how many ended early or waited out this week, each command's peak today against its
  limit, and how many waited in line, each opening the matching requests. Reasoning lists the models that keep
  thinking on. Your data shows what each command saves and for how long, with an On/Off per command for saving
  prompts that applies at once, and asks before deleting history older than a shorter window. Version shows the
  installed and latest release, with upgrade and rollback. Every setting the daemon takes live has a row, and a test
  fails the day a setting turns live without one.
- **Sessions and Teams say why a session is held.** A session whose newest request was turned away at a spent
  plan reads At limit, with the account and when the window comes back; one with no credential reads Signed out,
  one tap from its sign-in. A silent turn reads Stalled with the silence counter, then Resumes in while splice will
  re-send it, or Won't resume when it will not: `GET /api/heads/<head>/turns/live` now carries `will_resume`, decided
  by each dialect's own re-anchor rule, and the words appear only once the provider has begun answering. Teams
  re-reads every five seconds, so the counters move. A session still reads At limit after a daemon restart, and a
  limit on the command a session moved off does not follow it to the new one.
- **`splice add local` finds the runtime.** With no `--base-url` it asks Ollama's, LM Studio's and vLLM's usual ports
  for a model list and uses the one that answers; if several do, it names them and asks for `--base-url`. A model the
  runtime itself reports cannot call tools is refused when the row is checked, read from Ollama's and LM Studio's
  capability lists, without `--live`; a runtime that reports nothing is trusted.
- **A restart fails no request.** systemd holds splice's ports: `splice restart` lays one socket unit per port from
  splice.toml (`splice-control.socket`, `splice-head-<key>.socket`) beside splice.service and starts them, and the
  daemon serves on the sockets it is handed, so a client that connects while the daemon restarts waits in the
  kernel's accept queue and the next daemon answers it. Claude Code prints no connection error and no session moves
  to its fallback model. The first restart that lays them, or one after a head or port changed, stops the daemon
  first so systemd can take the ports. A socket for a head splice.toml no longer has stays with systemd and never
  refuses the boot. `splice capabilities` says whether the native transport loads on this machine, so a manager
  learns a hand-off would be refused before it stops the daemon.
- **A restart no longer leaves a copy of the code-mode host in /tmp.** Each splice JVM unpacks the pinned host,
  about 77 MB, into its own temporary folder, and the daemon never removed it: 246 of them had piled up since
  Oct 24. The JVM now removes its folder when it exits, normally or on SIGTERM.
- **`splice uninstall --all` takes away splice's systemd units.** It stops, disables and removes the daemon's unit,
  the MCP hub's and every listener socket splice laid, sockets first, so the daemon no longer starts again at the
  next login or the next request from the jar left on disk. Another program's unit in the same folder, and your own
  unit of the same name in `~/.config/systemd/user`, stay as they are.
- **A session joins its command by the launch splice made,** read from the launch roster. The process environment
  only tells a session splice never launched (`direct`) from one it cannot place (`unknown head`).
- **A slash command reads as what the person typed.** In a session's conversation `/rename notes` shows as their
  line, its output as a quiet system line, and the reminders Claude Code adds for the model are not shown at all.
- **One dollar form on every page.** Nothing charged reads `$0`, a charge under a hundredth of a cent reads `<$0.0001`,
  and a team's figures say why there is no dollar amount, on your plan or with no price, the bigger count first.
- **Foreground tools keep their session live between model calls.** splice sees a tool start when
  a turn ends on tool calls and sees it finish when the client's next request carries the result,
  so a long tool does not make the session stale just because no request reached the model. No
  client hook is installed for it: a head's settings lose the foreground hook an earlier launch
  wrote the next time the head launches, and the two shell, perl and curl runs it cost on every tool
  call are gone. The result's request, session end or process exit ends that activity; a result
  that never comes expires after 12 hours.
- **`splice setup` can hand an NVIDIA card to rig.** On Linux x86_64 with a card nvidia-smi can
  name, an interactive setup asks (default no) whether to run a local model through
  [rig](https://github.com/torad-labs/rig): rig installs into `~/.local/share/rig`, downloads
  bonsai-2-27b and a prebuilt engine for the card, or builds an engine where the prebuilt cannot run (about 9 GB; about 18 GB of disk in all) and serves it
  on 127.0.0.1. Setup then adds a `bonsai` head (`claude-bonsai`) through `splice add`'s own
  checks, atomic save and restart, from what `rig describe` reports: its base URL, its advertised
  window, and its server facts as quirks (`reasoning_effort` off where the server rejects it,
  `slot_affinity` where it pins slots).
  The endpoint takes no key, so `BONSAI_API_KEY` in keys.toml gets a placeholder, only when absent.
  A headless setup never offers it, a configured `bonsai` is left alone, and a rig failure is said
  in plain words (rig's exit codes mapped, its last stderr lines shown; a card or disk too small for
  the model is refused in rig's own sentence before the download) without failing the rest of setup.
- **A project's record says what governs the repo.** Each row of `GET /api/projects` now carries
  `compaction`, the rules a compaction in that repo resolves to in the daemon's own
  precedence (a project rule takes precedence over model and global rules,
  and a longer matching project path takes precedence), each with its scope, source and length; `[]` when no rule applies (the client's own instructions stand) and
  `null` when the daemon never wired its table. Beside it, `statusline_roots`: per head, because
  `statuslineGitRoots` is per-head overridable, the trusted root that head's statusline probes the
  repo under (`home`, `tmp` or `statuslineGitRoots`), or none, where it shows no branch.
- **Perf history is kept past two generations.** Every head
  archives each retired generation into `<state>/perf-archive` for `historyRetentionDays` (`0`
  turns it off; `perfArchiveRetentionDays` is the older spelling, read as the window an install
  already had), the perf readers (the usage windows, team economics, `splice perf`) read the
  archive oldest first and skip, unopened, any generation that ended before the window, and a team's
  lifetime tally starts at the team's creation.
- **Heads without a model allowlist offer discovered models.** At start
  the daemon asks every remote head holding its own credential for its model list, using its primary
  credential or key. Undeclared models reach heads without their own `models` list within an hour,
  through roster refresh. Published windows apply, and a window setting narrows an undeclared model but never lifts it past
  its published ceiling;
  provider defaults cover missing sizes, and a head's window sets its declared rows. A provider may declare
  no rows at all. Declared rows keep everything they decide (order,
  label, window, rates, slot), and a discovered model is never placed behind a tier slot. What the
  endpoint itself says cannot run a turn stays out (OpenRouter's non-text or tool-less models, the
  Codex backend's hidden ones), and a new per-provider `discovery = { include, exclude }` glob filter
  names out the rest (xAI's `grok-imagine-*`); `exclude = ["*"]` turns it off. Every head is asked at
  once under a 10s deadline, and no answer ever costs a head its start: the last list the endpoint
  served is kept per head under the state directory, and with neither the head boots on its declared
  rows exactly as before. The Codex backend is included, and Muse is asked with its minted inference key.
  `splice models` marks undeclared models `+` discovered or `–` kept out, with the reason:
  the first eight are shown, or all with `--all`.
- **Shared MCP hosting reports all five kinds of source.** `/api/mcp` reports top-level
  servers in `.claude.json`, project overrides inside it, `.mcp.json` in repos Claude Code has
  opened, and plugins' `.mcp.json` or inline `plugin.json` servers. `/api/mcp`'s new `sources` section reports a per-kind scan (roots looked at,
  files scanned, registrations found) and a disposition for every server found: `migrated` when
  splice already hosts it as one shared process, `excluded` with a reason (a different Claude
  identity's file, a plugin-owned manifest that splice never rewrites, an entry the hosting plan
  leaves as declared, or a stale copy in a head's config), or `pending` for a project or repo override the census can now see but the
  rewrite pipeline does not yet touch. The pipeline that actually hosts a server is unchanged:
  only the canonical home's global entries are rewritten, exactly as before.
- **Claude accounts are pooled per head.** `POST /api/auth/<head>/login` can add a subscription
  through Claude Code's own browser sign-in into a separate splice-owned folder. Added accounts never replace another
  login and can join the running head when membership is published. Plain claude’s login and
  claude-splice’s own login are separate selectable places; splice reads their live access tokens
  but never refreshes either credential. Splice can refresh an added account's credential because
  its folder has no other owner. Removing or renaming a Claude login requires an explicit target
  kind and id, so a folder account and a physical login with the same visible name cannot collide.
- **Claude login attribution follows requests.** `GET /api/sessions` names the login a session's own
  newest request carried, an added account included, never another session's choice. The usage
  reading comes from the head's carrying login's quota, then the next selectable target if that
  reading is absent. `GET /api/accounts` marks the login that carried each head's newest request
  (`carrying_request`). An unidentified native login says whether its current token's
  profile read is pending or was refused, instead of reporting every missing identity as “not
  identified yet.”
- **Claude head mode: wrap the default `claude` command.** `GET /api/claude-head` reports which of the two modes is active
  (Separate, the default: `claude-splice` stays a splice-owned head with its own config dir; Wrap:
  the operator's plain `claude` becomes a splice launcher over the vanilla `~/.claude`), what
  `claude` on PATH resolves to right now, and the shim path. `POST /api/claude-head/wrap` installs
  the shim at `claude` and writes nothing into `~/.claude` or `~/.claude.json`: a wrapped `claude`
  runs over your own config, so its MCP servers, projects, trust, account, settings, hooks and
  plugins are the ones plain `claude` had, and the head's settings ride a `--settings` overlay.
  A Claude Code update re-points `~/.local/bin/claude`; the daemon puts the shim back at
  start, on a watch of that directory and on a slow tick, and records the new release as the real
  binary, so `claude` stays wrapped and on the new version. `POST /api/claude-head/unwrap` points
  `claude` back at the current real binary. When you add the Claude head, `splice setup` asks Separate or Wrap. Every OTHER
  head's launch is protected too: LaunchService
  now plants the real absolute claude binary instead of the bare `"claude"` string whenever wrap is
  active, because every head shares one `claude`-on-PATH argv[0] and a wrapped `claude` would
  otherwise resolve straight back to the shim mid-launch. `splice login claude-splice --label
  <name>` keeps several of Claude Code's own logins on `claude-splice` and switches between them,
  only while no session of that head runs and only after saving the login it replaces, unless you pass `--discard`;
  this labelled-copy CLI saves credentials byte for byte and is separate from per-request pool
  selection. `splice doctor` reports the mode.
- **Teams: sessions on different heads work one goal.** `PUT /api/teams` creates a team: a name, a repo, a goal and role slots,
  any of them marked the lead, each with a role, a head, its own instructions and a bound session or an open seat. From its next
  turn through splice, every session bound to an active team gets its team, its role, the team goal, the slot's
  instructions and where to reach the lead. The text is appended after the head's own prompt, even
  on a `replace` head, and an edit applies on the next turn with no restart; that turn cannot hit
  the prompt cache, and its perf row says so. `GET /api/teams` reports each team as stored, with the project of the
  team and of each slot. Message requests
  that passed through splice are read from the sender's own transcript, not treated as confirmed
  delivery. Activity is sampled at most once every 30 seconds, not on every turn.
  The economics API reports lifetime turns, tokens and estimated dollars per slot, and what each slot moved on a plan
  account over the last 5 hours and 7 days, by command and account (`GET /api/teams/{id}/economics`); the Teams page
  sets the plan's own used percent beside that, as Usage reads it, and never turns it into a share. A slot set to
  join by rule (`bind_by_rule`) takes a session that starts on its head in a folder inside the team's repo, at the
  session's start and before its first turn, while the slot has no running session; a running member is never
  displaced, and a session already on an active team stays there. The board has four views as tabs: Board, By
  command, By role and Timeline, each beside the chat and the activity, kept per team. Teams live in `teams.json`
  under the state dir and
  are archived, never deleted. A plain `claude` session can hold a slot, but its own messages
  don't pass through splice, so `GET /api/teams/{id}/chat` shows only what it receives.
- **Every head's logins can be signed in, switched, removed and renamed through the control API.** `POST /api/auth/{head}/login` starts a device or browser login off the request that
  asked for it and answers immediately with a login id; `GET /api/auth/{head}/login/{id}` polls it
  for the user code and verification link (device flow) or the browser URL (OAuth flow), through
  `signed_in` once the credential lands and `live_after_restart` once the head has restarted.
  A newly labelled OAuth account joins its pool after `splice restart`; a Claude account can join
  without restart when its membership is published. `DELETE`/`PATCH /api/auth/{head}/accounts/{label}`
  remove or rename a pooled account. An OAuth primary is refused for both; a Claude edit must name
  its target kind and stable id.
  `POST /api/auth/{head}/switch` pins an account: from the next turn splice tries it first, ahead of
  the primary, and falls back to the usual order when the pinned account later becomes unavailable. Pinning an unknown account or one with no
  credential is refused; `DELETE /api/auth/{head}/switch` drops the pin (idempotent).
  `GET /api/accounts` joins every pool's accounts across
  every head, on the credential path. Two heads sharing one login are one row, not one per head,
  carrying each pooled account's window length as reported by the provider,
  the operator's pin, the next target by the real selector order, and single-login heads read from
  their `/api/auth` view. The statusline now records each session's `rate_limits` object (five-hour,
  seven-day, per-model weekly windows), window fields only and gated on `rate_limits_available`, so
  a session's real Claude-reported windows are on record beside splice's own derived quota.
- **The control API sets capture, budgets and webhook alerts, and sends a playground prompt.**
  Body capture is on by default and can be turned off per head. `GET`/`PUT /api/heads/{head}/capture`
  reports and changes capture per head, applying from the next request. Turning capture off stops
  new writes, and the next daemon start deletes that head's kept trace days.
  `GET`/`PUT /api/budgets` holds per-head daily USD budgets with a `warn`/`block` action, defaulting
  the action from the new `budgetDefaultAction` knob when a row omits one. `GET`/`PUT /api/alerts`
  holds a desktop-notification flag and one webhook URL, and `POST /api/alerts/test` posts one test to the saved webhook URL, never an unsaved draft.
  It answers 409 when no URL is saved; the desktop flag delivers nothing. `POST /api/playground` sends one prompt
  through one head's own credential and provider: anthropic-passthrough, openai-chat or
  openai-responses, whichever the head is on. A head passing Claude Code's own login through,
  such as `claude-splice`, is refused. It is a one-shot call outside the turn pipeline, recorded like
  any request (see "Compare models sends are recorded like any request" under Changed). Every new store (`budgets.json`,
  `alerts.json`) is a plain 0600 JSON file under the state dir, same shape as `teams.json`.
- **A llama-server head keeps each conversation on its own slot.** llama-server picks a
  slot by prompt similarity measured against the new prompt, and skips empty slots while doing so,
  so a new session sharing Claude Code's ~30K preamble took an idle conversation's slot and that
  conversation re-prefilled from zero. The model's recurrent layers cannot be rewound, so a slot's
  state is all or nothing. With `quirks = { slot_affinity = true }` splice sends `id_slot` per
  conversation (its session plus its opening messages, so a subagent or title request sharing the
  session id does not queue behind the main conversation), gives a new conversation the least
  recently used slot with no turn in flight, reads the slot count from the server's `/props`, and
  sends a turn unpinned whenever no slot is free or the count is not known yet. A slot is held until
  the turn truly ends, detached compaction drives included.
- **Local heads report their token usage, so Claude Code auto-compacts.** Token counts are
  opt-in on the OpenAI-compatible chat dialect (`stream_options.include_usage`), and splice asked
  only on grok, so every other head on that dialect reported zero tokens per turn, Claude Code's
  context meter never moved, and a session ran into the context wall instead of compacting. A
  provider that is local (a loopback `base_url`, or `local = true`) now asks by default; hosted
  vendors are unchanged, because strict ones reject unrecognized `stream_options` members. The new
  `[providers.<key>.quirks] stream_usage` overrides it either way, for a local runtime that refuses
  the field or a hosted vendor known to accept it.
- **A `context_window` edit needs no restart.** The running daemon re-reads the windows in
  `splice.toml` (a model's `context_window`, `extra_windows`, `window_rules`,
  `default_context_window`, a head's `context_window`) when the file changes, so running sessions
  compact at the new window through usage scaling, the next launch plants it as
  `CLAUDE_CODE_MAX_CONTEXT_TOKENS`, and `GET /api/models` shows it. A
  local runtime is asked about a new window the way boot asks it, off the request path, and a window it refuses is
  not applied; a file that does not parse, or a head it no longer declares, keeps the windows in
  force. Each re-read writes one daemon.log line per head whose windows changed, were refused or are no longer declared, and one line when the file does not parse. `/health`'s `topologyDigest` now names the version the
  daemon runs: a window-only or comment-only edit leaves `splice doctor` with nothing to restart
  for, any other edit still reads stale until `splice restart`, and `PUT /api/topology` answers
  `restart_required: false` for a write that moved only windows.
- **Per-head system prompt.** `system_prompt` (or `system_prompt_file`) under `[heads.<key>]` gives
  a head standing instructions that ride on **every** turn, at that dialect's own system seam:
  anthropic-passthrough, openai-chat and openai-responses each place it the way their wire expects.
  Setting both keys, or naming a file that cannot be read, is a config error at load, never a
  silently empty prompt; absent or `""` leaves the request bytes byte-identical to before, pinned
  per dialect. `system_prompt_mode` picks the seam: `"append"` (the default) places text beside the system field on Anthropic,
  in a system message before the first user on chat, and in a trailing developer item on Responses.
  Existing client bytes and cache breakpoints survive; with nonempty instruction text, `"replace"`
  substitutes that whole field. An empty source leaves Claude Code's instructions unchanged.
  `splice doctor` notes a configured replacement prompt as information.
- **`splice add <profile>` adds a second provider without editing TOML.** Nine profiles
  (`codex`, `grok`, `kimi`, `muse`, `deepseek`, `openrouter`, `claude`, `local`, `api-key`); the command authenticates with the login verb's
  own flow, takes models and context windows (`--model id:window`), always checks the candidate
  before writing (the file parses, the credential is present, the base URL answers, the models are
  listed where the dialect publishes a list, checking each available published window and naming
  models without a published size as unchecked), then
  appends the provider and head tables through a sibling temp file and one rename. After linking
  and restart handling, `--live` or yes at the prompt checks the installed command for any profile.
  A failed check prints a diagnosis and leaves the head saved. A refusal before saving leaves
  the previous file byte-identical, and a command line it cannot mean (an
  unknown flag, a flag without its value, a second word) is refused with the usage, never swallowed.
  A model id may itself carry colons (`--model qwen3:4b:32768` is the id `qwen3:4b`); a numeric window of zero or less is refused. A non-numeric
  suffix is part of the id and uses the default window. The config is read again right before the rename, so a file
  edited while the sign-in and checks ran is left alone and the command says to rerun; and when the
  daemon restart that was asked for fails, the command exits non-zero (the head is saved and
  `splice restart` is named) instead of printing a launch line for a head the daemon does not serve.
  A config saved without a trailing newline is not "changed" on every run; a flag followed by
  another flag (`--model --yes`) is a missing value, never a model named `--yes`; a config with no
  head yet gets the first head port. A model id given twice is refused (the catalog keys rows by
  id, so the second window would silently win). A config that cannot be read again at save time
  (deleted or replaced meanwhile) is refused, never recreated from the stale candidate; a typed
  context window that is not a positive integer (`32k`, a decimal, a number past Long) is asked
  again instead of silently becoming 128000, and three misses refuse the add.
- **`splice add-model` puts more OpenRouter models on a head.** It asks which OpenRouter head,
  offers the curated models that head cannot reach yet, and writes the picked rows (and, for a head
  that lists its own `models`, its roster) through one rename. A composed file that does not parse
  is refused and the old one is left as it was.
- **`splice upgrade [--to vX] [--now] [--rollback]`.** Fetches and verifies a release exactly as
  `install.sh` does (sha256 against `sha256sums.txt`, and the GitHub build-provenance attestation
  whenever `gh` is signed in), stages it under `~/.local/share/splice/releases/<version>/`, runs the
  candidate's own doctor, waits up to 30 minutes for every head's in-flight count on `/api/heads` to reach zero
  (or skips the wait with `--now`), otherwise leaves the release staged, repoints the live jar, restarts the user unit when one supervises this install, and
  runs doctor. The launch shim is always refreshed with the release (it is version-locked to the
  jar by the launch handshake, so a kept old shim could launch nothing); a shim edited since its
  recorded release was installed is saved beside that release as `splice-launch.edited`, with
  its diff printed. A flat install's live launcher is kept as `splice-launch.edited`, because
  splice cannot tell whether it was edited. Its live bytes are also recorded with its jar so a
  rollback has one to restore. The restart is judged by what
  `/health` serves afterwards, never by an exit code: the old CLI no longer polls for its own
  version and calls a good restart a failure, a loaded-but-inactive user unit (a daemon started by
  hand) takes the stop-and-start path instead of restarting a JVM that exits on the daemon lock,
  and a daemon still on the old version is named. `--to 0.4.0` and `--to v0.4.0` name the same
  tag; a candidate older than 0.4.0 skips the `doctor --json` preflight it cannot answer instead of
  running its text doctor against the live install and being refused for it; another running
  upgrade's staging directory is never pruned, and a pruned one is a refusal, not a stack trace.
  `install.sh` records the flat install it replaces as `previous`, so the first `--rollback` after
  it has somewhere to go. Release downloads never follow an HTTPS-to-HTTP downgrade; every process the upgrade runs
  (`gh attestation verify` included) has a deadline; `--rollback --to` is refused instead of the
  version being ignored; temp links are named per process and instant.
  `--rollback` repoints at the previous release, kept until the next successful
  upgrade. Config and credentials live elsewhere and are never touched; a failed verification
  activates nothing. `install.sh` keeps the pristine release copy the comparison needs. One
  upgrade runs at a time per install (an OS lock under `releases/`; a second run is refused before
  it fetches anything, so two runs can never prune each other's release). A download that fails
  (DNS, TLS, a timeout, a 403 or 5xx) is refused by its class, never reported as a missing asset.
  An activation that began without a live shim and failed removes the shim it wrote. `install.sh`
  reads the `previous` link back after writing it and warns when something else is in its way.
- **`splice sessions` and `/api/sessions`.** The Claude Code sessions on this machine
  (`~/.claude/sessions/*.json`) joined to the head that launched each on Linux, with an unknown head elsewhere, and live / stale / gone
  derived from the pid and the last update rather than the file's own status, and the copyable
  `SendMessage` line per live session. A live pid whose process started long after the
  registration is a reused pid and reads as gone. Read-only; headless `claude -p` runs never
  register and the footer says so. `splice sessions --json` prints the same registrations as one JSON line for a program
  to read: each session's identity (id, name, pid, kernel start, socket and address), its state as Claude Code wrote it,
  and how splice judged it (live, stale or gone, with its route and head).
  On Linux, when Claude Code records process identity, a pid from another container or a reused
  pid reads gone. Without that identity, a process started more than five minutes after its
  registration reads gone. A sessions directory that exists but cannot be listed (a permission failure,
  a file in its place) is reported by the command (non-zero) and the API (`error`), never read
  as no sessions; a missing directory is genuinely none.
- **`splice perf [--window 1h|24h|7d]` and `/api/perf/summary`.** Per head: p50/p95/max of time
  before first byte, time streaming and total, outcomes by tag, failure share overall and per
  outcome tag (rows whose outcome cannot be read are shown as unattributed, never as failures),
  retries and refreshes, cache hit ratio, peak in-flight, and a lower bound on the file-io writes
  the daemon dropped inside the window (a dropped perf row is absent, so this is the evidence one
  is missing; the counter restarts at zero with the daemon, so this is a minimum). A window the rotated perf files
  cannot reach back to is reported clamped, with how far they reach; a quiet window over files that
  do reach past it is sparse traffic, not a clamp; no rows at all says so; a perf file that cannot
  be read is reported as a read error with the coverage marked unknown, never as short retention;
  when none of a head's perf files holds a valid row, it says "no valid perf rows read" with the skipped count
  (`skipped_lines`), never "no perf rows recorded yet".
  Empty data is reported as empty, never as zero-latency traffic. Performance records keep an
  explicit permanent or retryable failure decision when one was observed, including when the
  client disconnects. Reads stream the files, never hold a generation whole, and a rotation during
  the read is read again. `splice perf` is read-only and refuses malformed flags.
- **Performance history, request evidence and a Performance panel, kept locally.** Collection is on by default and
  needs no monitoring service. It keeps minute and hour aggregates, per-head request evidence and a record of which
  configuration was in force, each within a bound, and every answer says what it covered, what is missing and whether
  it is partial, stale or busy, so a gap is never read as a fast interval. `splice perf` still prints its text table
  from the retained files with the daemon stopped. `splice perf schema`, `summary`, `series`, `diagnose` and `export`,
  and `--json` on the text form, answer the same queries for a program over one contract, and `splice perf profile
  capabilities` says what this machine can capture. The console's Performance panel on Usage and Requests follows one
  interval and cohort into latency distributions, queue history, request timelines and traces, and keeps saved
  baselines in your browser with no request content in them. The control plane serves the same readings at
  `/api/perf/schema`, `/summary?contract=1`, `/series`, `/turns?contract=1`, `/observations`, `/diagnose`, `/runtime`
  and `/instrumentation/status`, and the older summary and turns shapes answer as before without `contract=1`.
  `perf_collection_enabled = "false"` under `[defaults]` turns the new collection off at startup and keeps the older
  performance rows and your local traces. Guide: `docs/observability/README.md`.
- **Objectives, alerts, profiles and optional export.** An objective is a request-level threshold with its cohort, and
  an alert is a sustained condition with a minimum count, coverage, hold and recovery rule (`GET` and `PUT
  /api/perf/objectives`, `GET /api/perf/alerts`). A fresh install creates no rule and sends no notification. A profile
  is an explicit, bounded JFR capture, or one from an installed native profiler, kept as an artifact for an hour and
  downloaded with the control plane's bearer (`/api/perf/profiles`). Prometheus is `GET /api/perf/metrics`, behind the
  same bearer and loopback guard, and OTLP sends metrics, traces and logs over `http/protobuf` or gRPC. Both are set
  under `[telemetry.operator]` (`prometheus_enabled`, `otlp_endpoint`, `otlp_protocol`, `otlp_headers`,
  `otlp_signals`) and apply on restart. With no endpoint, or no signal listed, no exporter is built and nothing leaves
  the machine, and request and tool content, tool names and exception text never enter an exported signal. A request
  never waits on a collector, and export failures are counted on their own at `/api/perf/export/status`.
- **`/login` inside a head starts over, and can name an account.** A second `/login` while a
  sign-in was still waiting for its browser callback used to die silently on the callback port
  while the hook promised a browser; on Linux and macOS the waiting sign-in started by an earlier hook is cancelled first and the reply
  says so. `/login ` with a trailing space or `/login --label NAME` is intercepted like `/login`
  (it no longer reaches the model as a prompt), and on OAuth heads `--label NAME` rides through to `<head> login`,
  so a second account of the head's kind can be signed in from inside the client. On OAuth heads, any other
  argument, or a label the CLI would refuse, is refused by the hook itself and nothing is started
  (a bare login would sign the primary in again). The receipt for a labeled sign-in says the
  account is saved beside the primary and joins the pool after `splice restart`; only an unlabeled
  sign-in is "using the new credentials", because that is the only one this session switches to.
  The hook proves the login command resolves BEFORE cancelling a waiting sign-in, and reports a
  replacement that exits as soon as it starts; an
  installed launch shim from before this release is reported stale by the daemon and doctor.
  The hook cancels only the sign-ins it started (each carries `SPLICE_LOGIN_ORIGIN=hook` in its
  environment): a sign-in you began in a terminal and are finishing in the browser is named and
  left alone, never killed and replaced by a primary login. An input whose top-level prompt cannot
  be read is refused with the same "no prompt string" reason on an api-key head as on a browser
  head, instead of being answered as a bare `/login`.
  A sign-in started as `splice login <key>` is found under that spelling too (the hook matches the
  wrapper word or the head key), so it is named and left alone, or cancelled when the hook started it.
- **`splice doctor --json [--with-logs] [--out FILE]`: a shareable, redacted report.** Schema
  version 1 carries the splice and Claude Code versions, OS and JVM, the topology's SHAPE
  (kinds, dialects, model ids and windows, quirk names, a host but never a URL with credentials),
  every check with its fix, and the last 200 perf rows per head (across both perf file
  generations) restricted to the named numeric fields, the compact and cache-cold flags, and
  model, outcome and account as safe tokens. Emission is an
  allowlist and authored strings pass the redactor: known credential shapes, opaque tokens of
  32 characters or more, e-mails, UUID-shaped ids and foreign paths are masked. Paths survive
  only under splice's own directories, `~/.claude`, `~/.lmstudio`, `~/.ollama`, `~/.local/bin`,
  `/usr`, `/etc` and the daemon's own routes, with home shown as `~`. an operator-authored name that is not a plain
  token (a provider or head key, a model id, a prefix) is omitted or aliased rather than shown,
  and `--with-logs` appends the last 500 daemon events reduced to their structure (timestamp,
  tags, event, key=value pairs) with a count of the lines that were not daemon events; an MCP host
  line keeps its server name when it is a plain, secret-free token and its event head, so a report of a hosting problem
  carries the hosting lines. `--out` writes the file owner-only (0600) in one atomic step. A malformed flag prints
  usage and writes nothing. Nothing is uploaded.
  Per-turn daemon log lines keep their `compact=` and `model=` pairs in the report.
  A JSON-quoted credential key (`"refresh_token": "..."`, `"api_key": "..."`) in free text is
  masked like a bare one, with quotes and structure kept.
- **Automatic account switching when a provider's limits are hit.** `splice login <head> --label
  <name>` adds a second (third, ...) OAuth account of the same kind in a folder
  `<kind>/<primary file name>/` next to the primary credential file, by default
  `~/.config/splice/auth/chatgpt-oauth/codex.json/`. Heads sharing a credential share its pool;
  same-kind heads with different credential files do not; the first login stays the primary in the file it always
  had, so nothing migrates. Selection is per turn and sticky per session. A full usage reading
  prefers another free login, but never blocks a turn by itself. If the current login's own
  credential is rejected, the next turn tries a usable account. A 429 that names a spent plan window or asks to
  wait more than 15 s hands the same turn to a free login before any byte reaches the client, on the ChatGPT, Grok,
  Kimi and Muse pools as on a Claude one. Either way the account is taken in the saved order, or by default the
  soonest weekly reset, the five-hour reset breaking ties. Among the preferred
  free logins, a manual pin comes first. With no saved order, a session keeps its usable account
  rather than returning to the primary at every reset. A turn
  in flight finishes where it started; the first turn after a switch is accounted as cache-cold and
  its perf row names the account. When every account is out the turn fails honestly, naming the
  earliest reset. A credential is only ever used by the kind it carries; a mislabeled file is refused.
  A turn's time limit for reaching the provider starts when splice begins sending it. Time
  waiting in splice's own queue does not count, so a queued turn still gets its first call. On a pooled head the
  status line draws each window from the selected account's own tracker first and the client's
  headers fill only a window the tracker lacks; a locally answered side query carries the session's
  selected account's quota, not the primary's; an account's headers ride on top of the provider's,
  never instead of them; and a 401 from `/api/auth` reads as a failed read, not "no pools". A file
  in a pool directory that is not a credential at all (unparseable, or without a kind) is skipped
  with one diagnostic naming it instead of taking the whole head down; a credential that declares
  the wrong kind or label is still refused. Per-session account stickiness is kept for at most
  4096 sessions, least recently used first out.
  When the head holds more than one login, the status line names the one the session runs on.
  `splice status` and `splice doctor` show the head's accounts and its last automatic switch. Doctor names the login
  carrying requests and reports the newest retained refusal under its owning head or account.
  Labeled credential files are read without following symlinks (a linked file is never loaded and is listed as refused with a reason in
  `/api/accounts`; its label stays taken, so a new sign-in never reuses it), matching how they are written; the primary file is resolved as before.
  A 401 rejecting the account's own credential excludes future turns from that account,
  while a rejection naming another key does not, never the
  turn it was issued on; the exclusion lifts at once on an evidence-backed re-login (the credential
  file changed) and otherwise admits one timed recovery probe after holds of 5, 10, 20, 40 and 60
  minutes (60 minutes at most). If the credential file cannot be read, the hold stays.
  An older success cannot lift a hold set by a newer failure. A cancelled or failed trial
  frees the account for the next trial. Auth exclusion is separate from rate-limit state and is shown in
  `/api/auth`, `/api/accounts` and `splice doctor --json`.
  A 429 that names no window or reset moves the turn to an available pooled account, and with none free is retried
  after 2, 4, 8, 16, 30, 30 and 30 s, two minutes shared by the whole pool, before it ends as a retryable failure; it
  holds no account for a reset the provider never stated. A reset stated at 15 s or less is waited out on the same
  account in waits of 2, 4, 8 and 15 s, and a longer one hands the turn to another account or holds it to the stated
  time, up to 7 days. A spent plan window hands the turn to a free account or ends it with the provider's words. Real
  quota exhaustion respects its reported reset.
  Kimi automatic and explicit labels share cross-process login leases: a concurrent login to the
  same label is refused, a re-login may replace its credential, cancellation frees the lease, and
  validation precedes lock-directory creation; the login receipt names the label actually
  persisted, collision suffix included. Admission with every account exhausted is a local health
  failure with zero upstream calls and carries an IMF-fixdate `Retry-After` only for a known reset.
  A turn out of time before its first request ends as a watchdog timeout. Between retries it
  ends with the last upstream error. Neither makes another upstream call. A pool account whose credential file is missing now warns once when it goes missing, not on every status
  check, and again only if it comes back and goes missing once more. `Retry-After` asks to retry within 120 s at most while the
  message names the provider's earliest reset, and a provider reset too far out of range to print can no longer
  turn an honest 429 into a 500.
- **Custom compaction instructions.** `[compaction]` in `splice.toml` carries global text (inline
  or `file =`), `[[compaction.model]]` rows keyed by upstream model id and `[[compaction.project]]`
  rows keyed by absolute directory (optionally per model). The most specific scope replaces the
  less specific ones (project+model, project, model, global); `instructions = ""` opts out. The
  text rides after Claude Code's own summarizer prompt on compaction requests only, so the cached
  request prefix is byte-identical with and without it. Compaction rows in `/api/compact` show
  the effective source, never the instruction text, including for older kept records.
  `/api/compaction/instructions` lists the rules in effect by scope, source and length, never the text.
  A `[[compaction.project]]` path may start with `~/`, and a relative one resolves under the
  topology directory, exactly like `file =`; a tilde no longer stops the daemon at boot. A session
  whose project cannot be resolved is not looked up again for 5 seconds (a hit is never cached as
  a miss, so a new registry entry becomes visible). On the `openai-chat` wire the instructions
  extend a trailing user message instead of adding a second user message after it.
  A `file =` rule re-reads its file when it changes, so an edit is live at the next compaction
  without a restart (a file that becomes unreadable disables the rule, as at boot). Project paths
  and the session's cwd compare as physical paths, so a project configured through a symlink
  matches the cwd Claude Code records. splice recognizes a compaction retry from the request
  as Claude Code sent it, before custom instructions were added, so a project found late or an edited file cannot
  start a second upstream compaction; and when a dialect cannot place the tail (no user text to
  extend) the compact row says `(not applied)` instead of claiming instructions the wire never carried.
- **Shared MCP hosting.** Eligible stdio servers from top-level `mcpServers` in `~/.claude.json`
  start once in the daemon for heads sharing `mcps`. A launch line with a working directory,
  relative path, location flag, existing directory or `${VAR}` stays per-session, and so does a
  server with a `cwd`, or one that has asked the client for its roots. Hosted servers use Streamable HTTP on
  loopback (`/mcp/<name>`, one MCP session per client session, JSON-RPC ids remapped, notifications
  fanned out, `initialize` cached for new sessions; other requests go to the server); each head's `.claude.json` is rewritten to point at the hosted
  URL, a wrapped plain `claude` gets them in an owner-only `--mcp-config` file, and the operator's file is never edited. Servers whose transport type is `http`, `sse` or `ws` pass through
  untouched. A server starts at the first session's initialize, idle servers are reaped, and the longest-idle server is
  evicted when a new one would exceed the server cap; a
  crash fails pending calls honestly and the next call restarts the server, never replaying tool
  operations. `[daemon] mcp_hosting = false` turns it off, `mcp_hosting_exclude` keeps named
  servers per session; `/api/mcp` shows eligibility and ownership. A client that
  falls a full buffer (256) of notifications behind on its stream loses the stale backlog, never the
  fact that its lists may have changed: the backlog collapses to the three `list_changed`
  notifications plus the newest one when every queued item is a list change. With anything else
  queued, that server's tools stop working in that session until you relaunch it. A hosted child
  runs in the home directory (stated, never the daemon's accidental cwd); a server that reads its
  working directory without naming it belongs in `mcp_hosting_exclude`. A server whose last
  session ended is idle from then, not from forever, so the next session reuses the process
  instead of the next sweep killing it; a server mid-initialize is never swept; and a reservation
  taken by an initialize always ends, so capacity can no longer leak until restart. A client's
  progress token is private to its session: the child sees the host's request id and the progress
  notification goes back to that one session with the client's token restored. A second crash in a
  row waits before respawning (5 s, 10 s, ... 60 s; calls in between fail in words); a child that
  ignores TERM is torn down outside the registry lock, so the other servers keep answering.
  The generated hosted-MCP configuration carries a domain-separated HMAC
  bearer accepted only on `/mcp/*`; a management bearer still works there. This keeps management
  authority out of generated MCP headers. Except on a head that uses your own Claude login, such as `claude-splice`, a session's `ANTHROPIC_AUTH_TOKEN` holds the turn key,
  inherited by its per-session MCP servers. Daemon-hosted servers inherit the daemon's environment. Only list-change
  notifications coalesce; a backlog of anything else that overflows ends that MCP session, sessions
  without an open GET stream included, and when an overflowed session's stream closes, the server can be reaped immediately. A malformed config entry
  (a non-string transport type, a non-string member in `args` or `env`) passes through whole with
  the reason, never hosted with a different launch tuple; the respawn backoff exponent is clamped;
  at shutdown splice stops accepting work and gives all servers 2 s in total before killing them; a child's stderr is
  drained bounded and only its presence is logged.
- **Local models are first-class on the `openai-chat` dialect.** A provider on a loopback
  `base_url` is local by default (`local = true|false` overrides). At boot and in doctor splice asks
  the runtime what it serves (Ollama `/api/version`, `/v1/models`, `/api/show`, `/api/ps`; LM Studio
  `/api/v0/models`; vLLM `max_model_len`) and refuses an Ollama, LM Studio or vLLM row the runtime does not list, or one that declares more context
  than the runtime reports for it (the window a loaded model was given; for a model not loaded yet, its configured window or else the model's maximum), naming the listed models and that window; a runtime that is
  down boots as before, and so does one whose model list does not answer yet (a list call that
  fails is not a runtime that lists nothing: no row is refused for it, and doctor shows a warning).
  The boot probe is bounded (2 s to connect, 5 s per request, `/api/show` only for the configured
  rows), so a wedged local runtime cannot hold the daemon's other heads hostage. The rows checked are each head's effective ones (a head `context_window`
  override applied, picker suffixes stripped) and the probe carries the provider's headers and
  bearer. `splice doctor --live` sends one tiny streamed request for each configured model the runtime lists, each
  with one tool. `splice status` labels these heads `local runtime (OpenAI-compatible)`; doctor shows `local:<provider>` with
  the runtime's name. Neither implies a subscription or quota. Proven live against Ollama 0.30.5 and LM Studio (llmster 0.0.24), one model each
  (`tools/e2e/local-models/`); vLLM documented.
  A row named without its tag matches the runtime's `:latest` listing (Ollama lists `qwen3:latest`
  and serves `qwen3`), and a generic OpenAI-compatible server's model list (a proxy's aliases, a
  llama-server file path, listing turned off) is not authoritative, so an unlisted row there is
  trusted rather than refusing the head at boot; a refusal now names `local = false` as the opt-out.
  The live probe proves a tool call by shape (a `choices[0]` delta or message whose `tool_calls`
  names `ping`), never by marker strings an error chunk could carry, and a non-200 reply is
  described by its status class and size only, its body never printed into the doctor.
- **Version-drift warning.** `Versions.kt` records the Claude Code version the fresh-machine e2e
  ran against; the daemon reads the client version from the `User-Agent` already on every request,
  and when a session's Claude Code is newer than that, doctor, `splice status` and the status line
  say so once. Equal or older is silent; no scheduled job, no live probe.
- **`claude-muse`: a head on a Meta Muse Code subscription.** `splice login claude-muse` shows a
  sign-in code to enter at auth.meta.com, opens that page in your browser when it can, and writes
  `~/.config/splice/auth/muse.json`, then mints the inference key with one
  `POST https://api.meta.ai/muse-code/key`. The account token is not itself an inference
  credential, so the mint runs at the end of the login and the first turn never waits on it; a
  mint that fails leaves a signed-in file behind instead of failing the login, and the next
  refresh mints. The head speaks OpenAI Responses at `https://api.meta.ai/v1/responses` with the
  minted key as a bearer. Reasoning comes back as readable summaries, and each conversation sends a
  `prompt_cache_key`, 24-hour cache retention and Meta's gateway session header, so a long session
  reads its history from Meta's cache (96.9% of input tokens over a 62-turn session, measured
  2026-09-28). A `[providers.muse]` table written by a pre-release build (dialect
  `anthropic-passthrough`, or a `base_url` without `/v1`) still boots on Responses, and doctor
  names each line to update. `splice add muse` writes the provider and head tables with
  `muse-spark-1.3[1m]` and `muse-spark-1.2[1m]` at a 1,000,000 window, and the example config carries
  `[heads.claude-muse]`. By default it uses its own credential file, not the Muse
  CLI's, and that file is merged rather than rewritten, so a key minted at runtime cannot drop the
  fields the login wrote. In the daemon a rate-limited mint is held for at least a minute; one at the end of login is
  retried by the first turn, a
  mint still in flight when the daemon stops writes nothing afterwards, and a re-login while the
  usage poller is minting cannot be mistaken for a rejected credential.
- **Muse subscription usage on the status line and in doctor for pooled accounts.**
  splice reads the allowance from `subs_usage` in the key-mint response: the five-hour window
  and the weekly window come from a poll on the quota poller's own cadence, never from the
  request path, and a rate-limited mint is held rather than retried.

- **A ChatGPT account signs in from the console by device code.** The Accounts page starts the flow on
  the daemon and shows a verification address and a code; open the address on any machine, enter the
  code and approve. The code lives 15 minutes, and a refused or expired one says so on the page.
  `splice login codex [--label <name>]` in a terminal keeps the browser flow; Kimi and Muse use the
  device code in both places. A log line for a refused response carries the status and the OAuth error
  code, never the body. Signing the first account in again under its own label replaces it in place.
- **Two ChatGPT accounts are told apart by a short ref.** The ref is eight hex characters of a hash of the
  account id, and no page shows an email or an account id.
- **The first ChatGPT, Grok, Kimi or Muse account can be renamed and removed like the rest.** Rename saves
  a chosen name in splice's own account-names file and never touches the credential, which can be the vendor
  CLI's own file. Remove is offered only on splice's own file, and a file shared with the vendor's CLI keeps its refusal.
- **A pin can name one session.** `POST /api/auth/<head>/switch` takes `"session"` beside `"label"`: that
  session uses the account and the head's pin stays for every other session. `DELETE` with `?session=`
  drops that session's pin; without it, the head's. A session's pin outranks the head's for that session
  only, a pin on an account that is out of quota falls back to the automatic rule while it stays set, a pin lets
  go when its account reads a new limit, and a restart keeps every pin. Without a pin a session keeps its account until a limit, then moves to the
  account whose weekly window resets soonest. `splice status`, `splice sessions` and `/api/sessions`
  show the pin: `pinned to <label>` on a head, and `account` plus `account_pin` per session.
- **A key is its own account.** An API-key head's key appears on Accounts with its variable name, a
  fingerprint (the first four bytes of its SHA-256, never the key), the day splice first saw it, and
  the keys it replaced with their own first-seen days. `key-ledger.json`, 0600 beside the key store,
  holds the fingerprints; if it cannot be written the list still answers and the daemon log says so once.
- **`GET /api/config` answers every knob inline.** Each entry in `knobs` carries its `value`, the `scope`
  that set it (default, toml, head, file, env or runtime), its `disposition` (`live` or `restart`) and
  `editable`; a knob that cannot be changed carries a `read_only_reason`. `mirrorReasoning` reads
  `restart`, is not editable and says it is pinned false by project rule.
- **A second head can use the same provider.** `splice add <profile> --name <key>` adds another
  provider-and-head pair for a profile you already use. The key takes lowercase letters, digits and
  dashes and must be new; splice picks a free port and names the command `claude-<key>` unless you pass
  `--command`; an API-key head reads its own `<KEY>_API_KEY`. A key, command or table that already
  exists is refused with a sentence naming it. A saved head ends with the doctor run, not a hint to run it.
- **splice ships each vendor's published list price.** A fresh install prices its turns at the vendor's own
  API rates for Anthropic, OpenAI, xAI, Meta, Kimi and DeepSeek (DeepSeek's peak hours at their own factor), so a plan turn reads
  as what the same work would cost on the API. A card in `splice.toml`, a head's card or a price the
  provider's model list publishes always wins. The data is `published-rates.tsv`, with each vendor's source and the day it was read.
  A prompt-cache write is priced by how long it was held: Anthropic and Kimi publish a higher rate for a
  cache kept an hour than for one kept five minutes, and splice charges each write at the rate for the
  duration the usage reports (`cache_write` and `cache_write_1h`, with `long_context_cache_write_1h` for a
  long-context tier). A turn on a model the roster does not name takes its published card too, so a Claude
  head whose client picks the model no longer leaves those turns unpriced and out of a budget's sight. The OpenAI
  gpt-5 family on api.openai.com (gpt-5 to gpt-5.5, with some mini, nano and pro variants) and the xAI grok-4.20
  variants carry their cards too, so a turn on them is priced; the ChatGPT backend lists only its own gpt-6 and
  gpt-5.6 models. GPT-5.6 Sol's price is a promotion that OpenAI says holds through at least November 21, 2026, so
  that row is read again then.
- **The shared MCP host caches `tools/list`.** Every session sharing a hosted server gets the listing from one
  cache, dropped when the child changes its tools or exits, whoever owns the process slot at that moment.
  `/api/mcp` reports each hosted server's resident memory.
- **The perf view says what it dropped and splits turns.** It reports how many rows telemetry dropped
  (rows whose cumulative `async_io_drops` is positive) and splits turns by model and by kind. Claude Code's
  activity side query leaves a perf row of its own kind, counted as no turn. A turn whose session cwd
  cannot be resolved while `[projects]` is configured carries `system_prompt_cwd_unresolved=1`, and doctor
  fails a project prompt table that sets both `system_prompt` and `system_prompt_file`, as the daemon refuses it at load.
- **A budget day runs midnight to midnight where the daemon runs.** Under the UTC day a Chicago budget lifted
  at 7 PM. The refusal says it lifts at midnight, local time, and Accounts draws the same day, taking its start from
  the daemon, so the two days a year the clock changes run 23 or 25 hours and are counted as they ran. Accounts adds up
  whole hours only; where midnight falls inside an hour (a zone offset by half an hour), the hour holding it is its
  own figure beside the day's and is never guessed into either day.
- **Accounts reports what a provider says about usage.** A provider that answers with no usage clears the
  account's bars and the card says "No limits reported"; a failed probe keeps the last reading
  with its time. Claude's per-model weekly windows (Opus, Sonnet) appear under the weekly window, each with its own reset,
  and a model week past its own reset is not drawn as a current reading. Each bar is marked stale from its own
  reading, so a fresh five-hour window beside a week that has ended no longer shows that week's spent figure as
  today's. A window whose reported reset has gone by says "Ended" and that time, with no ring, instead of an empty
  cell beside the ended week's figure; a window that reported no reset keeps the empty cell. When two heads share one login and only one of them pins it, the pin shows on that head alone, so Unpin acts
  on the head that holds it.
- **A provider can say whose machine runs it.** `discovery = { family = "vast" }` on a provider groups a
  rented GPU reached through an SSH tunnel as vast.ai in the console, where its loopback address would say This computer.
- **A launch inside tmux records its pane** with the launch, so a tool that drives sessions finds the pane of a session it started.
- **A `splice.toml` with findings refuses boot and lists every one at once.** Each finding names its key, and its
  line where the file has one, says what to write instead, and never prints a value you wrote. `splice restart` refuses before it stops a
  running daemon, so a bad edit no longer takes a healthy daemon down.
  So do `splice upgrade` and the launcher that replaces an older daemon after an install: the new build checks your
  `splice.toml` first, and when it would refuse, the old daemon keeps running and you see the list.
  `splice check-config` is that check on its own: it prints the list, changes nothing and exits 3 when this build
  would refuse your file. A plain `systemctl restart` cannot be checked first, because systemd stops the unit
  before splice runs.
  Run from a terminal, a `splice` command that hits the refusal offers a fix session, `splice restart` included:
  you can remove the finding's line unless it is a model row's, set a new value unless the key is not a splice.toml
  setting, open your editor at that line, or skip it for now.
  `splice.toml` is backed up before the first change, the file is checked again after every fix, and the command
  runs again only when nothing is left, so `splice restart` repairs the file and then restarts in one go. A value
  you type is not echoed to the screen, since it can be a token. A `splice.toml` that is a link is fixed at the file
  it points to, and stays a link. Off a terminal the command prints the list, exits non-zero and changes nothing.
- **The console reaches past Accounts.** It has eight pages: Accounts, Sessions, Teams, Requests, Usage, Models,
  Compare models, which sends one prompt to several models side by side, and Settings.
- **A running session takes a note from the console.** `POST /api/sessions/{id}/message` writes the note to the
  session's inbox socket. It is proven on each Claude Code version `/api/sessions` names, from 2.1.282 up to the
  tested 2.1.322, so a page can refuse before anyone types.
  `GET /api/sessions/{id}/transcript` pages a transcript forward from its start with a cursor
  (`before=end` reads the newest first), governed by the live `transcriptView` knob.
- **The operator can stop a live turn.** `POST /api/heads/{head}/turns/{id}/stop` ends it, and the client's
  re-send of that turn is refused.
- **API keys are managed through the control API and the console.** `GET /api/keys` names the keys (never a
  value), and `PUT` and `DELETE /api/keys/{ENV}` set and remove one, as `splice key` does.
- **One setting says how far back splice keeps your history.** `historyRetentionDays` covers both
  the hourly spend totals and the request records: days, or `forever`. A change to it applies at once, with no
  restart. A fresh install's
  `splice.toml` carries 35 days, which is a month of plan billing plus the days it is read over.
  An install made before this setting keeps the widest window it already had (`perfArchiveRetentionDays`,
  90 unless you set it, or the 35 days the hourly totals always kept), because an upgrade never shortens your
  history on its own. An hourly total priced without a
  rate card is priced again later from its perf rows at the cards splice holds then.
  Usage reaches as far back as the turns splice holds, whatever window you keep: an hour the hourly totals lack is
  built from the request records behind it, priced at the cards splice holds now, so the month view is no longer drawn over
  fewer days than the records on disk. An hour is built only when it can be exact: a read that was not whole
  backfills nothing, the oldest hour the records reach is left out, and an hour the totals already have is
  never replaced. Widening the window rebuilds the hours it now reaches without a restart. A window of `0` still keeps today, because a daily budget reads today's records; they go
  at the local midnight. `GET /api/history` answers the saved window, what is held (turns, bytes, the oldest
  moment) and the pace in MB a month; `?days=N` adds what choosing N would delete, counted by the hour (the cut is floored to an hour, which keeps at most one hour more than the window names and never less), and
  `PUT /api/history` saves N and deletes exactly that, from every place splice keeps it: the request records, the
  hourly spending totals, the message edges (who a session messaged) and each finished session's total, so a person who
  chooses "Today only" no longer reads last week's spend on Usage. The cut falls at your own midnight, and the
  message-edge day that midnight falls inside is cut row by row, so yesterday evening's rows go too; a row that cannot
  be read is kept, because it cannot be shown to be one of those you were asked about. A session's total goes by its
  last activity, and a session bound to a team that is still going is spared. A store that could not be trimmed is
  named in the answer. It refuses a moment newer than the window now warrants, a window that is neither a count of days nor
  `forever` (the refusal quotes what it received), and a save made while any record file could not be read whole;
  that one reads "Not saved", because nothing was saved and nothing was deleted.
- **Settings Storage lists what splice keeps and clears it.** It covers retained turn statistics and live session
  totals, separate from the trace deletion capture-off already does.
- **Two provider quirks.** Setting `summary_delivery` to `off` stops sending
  `stream_options.reasoning_summary_delivery`, which switches off the cutoff that empties gpt-6.1-sol reasoning
  summaries. `tool_name_cap` caps tool-name length: Muse caps at 64 by default, and a passthrough head caps only when
  its quirks table names a length. A capped name is restored on the way back.
- **Upstream sockets use TCP keepalive,** so a half-open connection no longer reads as healthy for the whole turn cap.
  A peer that died behind a NAT or a proxy is found within 25 s instead of 60 s.
- **`splice doctor` and `splice models` honour `NO_COLOR`.** Doctor gives its problems more room than passing
  checks, and each doctor row says whether its fix is a command or advice (`fix_kind`).
- **Usage rows report each turn's reasoning tokens** and give the reason when a row has no cost.
- **A session splice did not launch joins the head its requests reach.** With no launch record, a session is placed on
  the head whose port its requests arrive on, and keeps that head across a restart. `unknown head` stays for a session
  splice still cannot place.
- **The launch shim is POSIX `sh` plus `curl`** (marker `shim-24`). It sends each argument exactly as typed, whatever
  it holds, and when `curl` is missing it says so: "splice needs curl to start Claude Code, and curl is not installed"
  with the install command for this machine. A cold start creates `daemon-boot.log` owner-only (0600), or tightens one
  already there, before the daemon writes to it.
- **`SPLICE_JAVA` names the java splice runs on.** A machine whose `java` on PATH is older than 21 for its own work, an
  Android toolchain pinned to 17 for one, can point the launcher at a JDK 21 without changing that default: every java
  the launcher runs for splice (the CLI, `record-launch`, `check-config` and the daemon's cold start) uses it when it is
  set. The Claude Code the launcher starts keeps the caller's PATH.
- **Translated heads no longer show Claude Code's auto-mode notice.** A session on any head but `claude-splice` used to
  open with "this session isn't eligible… ask your gateway to implement", and an interactive auto-mode session held
  its first checked action until it was answered. The launch now tells Claude Code the server-side checks are not
  available there, so it runs its own and shows nothing.
- **Compare models.** Ask several commands and models the same plan and read their answers side by side. The plan is
  written as markdown and rendered the way it will be read. Any command and model that can send is listed, which for a
  Claude command means an account added to splice, never the client's own sign-in. Each answer arrives on its own
  clock with its tokens, its time and its price. The price is the one Requests would show: "On your plan" with its
  value at API prices, "≈$…" for a key, "No price" for a model with no rate. A plan window that is spent shows a lock and
  when it frees, and no Try again. A reply is priced by `GET /api/perf/price`, from the head, account, model and
  the tokens it reported. **Request** on an answer opens that exact request in Requests' own view.
- **Models is a page.** Every command is listed with its state and the fix splice offers (Start, Restore). The open command
  shows the slots it really sends, its own settings, and a log of its events, newest first, with repeats folded and
  the daemon's own lines behind Raw log. Saving and Restart splice are two acts, so edits to several commands restart
  once. A command's own value on a Settings row is set through `PATCH /api/config?head=<command>`, which edits that
  command's overrides in `splice.toml` and keeps its comments.
- **A command can be removed, and its history stays findable.** Remove takes two presses on the open command, and a command a
  live session is on says In use and opens those sessions. `GET /api/heads/{key}/removal` says what stays and who is on it.
  `DELETE /api/heads/{key}` drops the command and its project layers from `splice.toml` (a backup first), unlinks its
  wrapper unless another command shares it, and empties its account order. It is refused with the live sessions named
  while one is on the command. A removed command's requests, sessions and files stay findable, and adding the command back
  drops that record.
- **Usage is a page.** Plans show each provider window as Accounts reads it, Keys show a key's spend as money, and a plan's
  requests read "On your plan" with their value at API prices, never as money. Columns cover 7 days from the daemon's day
  totals and the last 5 hours by quarter hour. A breakdown by account, project, agent and model comes from the daemon's own
  totals over every request, not the newest 2,000. Each command's speed and failures in the table read every request
  in the window, and its slow or failing flag reads the newest 2,000. One plan account under several labels is one row.
  Every figure opens its requests on Requests with its filters carried, and a new window shows placeholders until its
  figures arrive.
- **Requests can show the requests that ran in no project.** `GET /api/perf/turns?project=none` lists them, the rows that
  print `project: null`, and Requests' No project pick and its figure ask the daemon for them.
- **One project pick across Sessions, Teams, Requests and Usage.** One resolver answers "which project is this?" for a
  session, a team slot, a request row and `GET /api/projects`. Settings has a Projects topic for the folders under which
  each child folder is a project of its own, written the way people type them (`~`, `~/work`). Sessions are grouped by
  project and a fold never hides a session that waits on you. Each project's figure shows in the picker and its menu.
  A session row says where it is: the working directory with the home written as `~`. A session with no name shows the
  first line of its first message, cut to 80 characters, and request rows carry that title and their project.
- **Settings Version can take you on.** One card per version there is, a Check that reads the newest published release once
  per tap, and Upgrade and Restart splice from the same card.
- **Accounts are shown by a name, never an email or an account id.** Every login carries a short reference and shows the
  name you gave it, or its plan word, numbered within its provider ("Max", then "Max 2"), until you do. Any login can be named, your own Claude login included, from
  Accounts: Name it on a default, Rename, and Add, which opens an empty Name field. The names live in
  splice's own state (`account-names.json`, `PUT /api/accounts/names/{ref}`), so no credential file is written. Requests
  and Usage name each recorded account the same way, and an old row recorded with an email is shown by its number, such as "Max 2", its reference
  only grouping its rows, and is never rewritten.
- **Requests can be ordered by highest price.** Dearest first, requests with no price last, a plan request's value under
  "On your plan", and the footer names the highest-priced request kept. `GET /api/perf/turns?order=price` asks the
  daemon for it.
- **The sessions list names the command a session arrived on,** where it used to print "unknown head" for a session that had
  registered.
- **Settings lists your MCP servers,** with a Test act on each shared row.
- **When you add an account to a command after hitting its plan limit, the sessions the limit stopped continue on
  their own.** Each gets "A new account is available. Continue where you left off." A session still waiting in its own
  retry comes back through that retry, and one you stopped yourself, in its terminal or from a card, gets nothing.
- **Accounts lists the sessions on each login now,** under its card, each with the lamp Sessions gives it and a link
  to it on Sessions. Past four, the rest show as a count.
- **Accounts shows each command's last move between logins** under its rail, such as "Moved from Personal to Work ·
  4:12 PM · 5-hour limit used up", read from `last_switch` on `/api/auth`.
- **Accounts says why a login's limits are not read.** A login reads "Not read yet" only while its first read is in
  flight, then "Not read" with the reason: not answering, signed out, refused, or unsupported for a sign-in splice holds
  no token for. `/api/accounts` carries it as `last_read_failure`, with no provider text in it.
- **Models shows what a local runtime says about itself.** A local command's pane has a Runtime row naming the runtime and
  its version, and each model shows the window it serves against its ceiling and whether it calls tools.
  `GET /api/models/local` asks each local provider's runtime.
- **A command's pane edits its provider's address.** Sends to is a field, checked as an http or https address and saved
  with Restart splice. A field the provider's other commands share names them beside it.
- **A command just added from Models opens on how to use it:** the command to type with Copy, doctor's checks for it,
  and Send one request, a single test turn that is sent only when you press it.
- **Every model marks each command's main model.** It leads its command's group, with the command's colour at its edge.
- **A command's timing settings show on Models only once it holds its own,** under their Settings names. Add setting
  lists the rest beside the value every command uses, and models read their catalog names, such as GPT-6 Sol.
- **Settings shows the settings that apply on restart,** each reading "Applies on restart" until it changes, then Saved
  with Restart splice.
- **Many agents at once has an Advanced section** holding the request-read wait, the memory for reading requests and
  the three retry waits. It stays closed until you open it, and opens while a search finds one of them.
- **Settings › MCP shows how much memory each shared server's process holds,** "Running" when none was read, and "Not
  running" until a session asks for it. A server at a web address leaves the cell empty.
- **`splice add` takes a profile's command as well as its name,** so `splice add claudex` adds what `splice add codex`
  does.
- **Usage shows how each command answered,** one row per command beside the Breakdown, where the Commands cards were.
  Each row has its requests, its failures, and first byte, streaming and whole request, typical and slowest 5%, over
  the last hour, 24 hours or 7 days. Cache hit, retries, sign-ins renewed, cold cache and most at once appear once a
  command has a figure for them. Each count opens its requests on Requests, each kind of failure included, and a row
  says when its record covers less than the window. `/api/perf/summary` carries `cache_cold`, the turns that began
  on a cold prompt cache, and `null` for a command that never records it. Record gaps counts the requests during
  which splice lost some of its own local writes, and shows only when there were any. Under a row, each reason
  its requests failed reads once, largest first, with its share from 1%, and opens every outcome it covers; its
  compactions open Requests with Compactions on; and the status labels splice wrote for Claude Code read as
  "12 status labels splice wrote · $0". A command that used two models names each on one line with its requests,
  typical whole request and failed share. One that used three or more lists them as sub-rows in the table's
  Requests, Failed and Whole request columns, the three busiest first and the rest behind "N more models". A model
  that never answered prints no time.
- **Requests opens on one kind of failure** with `?tag=` and an outcome tag, or several tags as a comma list
  (`?tag=error:a,error:b`), shown as one chip you can clear, and with Compactions on with `?compact=1`.
  `/api/perf/turns?outcome=` takes the same comma list and matches any of its tags.
- **Requests pairs a request splice cut on a restart with the client's re-send of it.** The cut row reads "Sent
  again after the restart" and the re-send reads "Resent after splice restarted", each a door to the other, where
  both are in the list. They pair on the same command, session and messages: every request that names a session,
  streamed or not, now records `messages_hash`, and `/api/perf/turns` serves it. Rows written before the change
  carry no hash and pair with nothing.
- **Requests goes to a time.** The window menu's Go to lists the requests up to a day and minute, in the narrowest
  window that reaches it, under an Up to chip you can clear.
- **Settings › MCP chooses between sharing and one copy per session, and says what sharing saves.** The switch,
  Shared or One per session, writes `[daemon] mcp_share`; unset reads as Shared. What sharing saves, read from
  `GET /api/mcp/memory`, sets the memory every copy of a shareable server holds now against one copy each. A server
  kept one per session either way is listed under that heading with its reason, and one Claude Code starts on its
  own under Started by Claude Code.
- **Plain `claude` reaches the MCP servers splice hosts, with no write to `~/.claude.json`.** A wrapped launch hands
  Claude Code an owner-only `--mcp-config` file naming each hosted user-scope server, which replaces the stdio entry of
  the same name, so plain sessions share one copy instead of each starting its own.
- **`GET /api/mcp` carries `plans`:** one per config home and folder a running Claude Code session started in, with
  the servers Claude Code would load there, the ones it would leave out and why, and each server's hub class beside its
  scope. A plugin server's `${CLAUDE_PROJECT_DIR}` and `${CLAUDE_PLUGIN_DATA}` are filled in as Claude Code fills them.
- **splice checks `--plugin-dir-no-mcp` itself for each installed Claude Code version,** once, in the background, in a
  throwaway home against a loopback stand-in model. Doctor names each installed version where it failed.
- **The MCP hub stops a shared server no current plan names,** once no live session is bound to it, and names the
  sessions bound to each server it serves. Its sweep no longer deletes the front's own state.
- **`transcriptCopyDays` sets how many days a transcript copy is kept:** 30 by default, at least 1, read live. Settings'
  Transcript copies card sets it.
- **`/api/models` carries `max_context_window`** where the provider publishes a ceiling. Models caps a window field at
  it, says Over the max past it, and says when none is published.
- **Models › Projects edits compaction rules in place.** Edit under the Compaction ladder opens the four rules that reach
  a compaction for this project and command, widest first, and Text and File say they replace the rule above.
- **A second command on a provider asks which key it sends with,** the provider's own or a new one. On Accounts the
  command sits on the card of the key it sends with, and the keys a key replaced fold into one quiet line.
- **A node's plain `claude` can run on the server's Claude logins.** `splice remote add <address>/claude-splice --as
  claude` makes the wrapped `claude` launch on the server's Claude head, over the node's own `~/.claude`, so its
  settings, instructions and sessions stay the person's and only the place its turns go changes. The server offers a
  Claude head only once every login in its pool is one it holds itself, native sign-ins and added accounts, and never
  while the caller's own sign-in is one of them. The node sends the word `splice-lan`, and the head's pool signs the
  turn with a login the server holds, so no splice key or node word reaches Anthropic. A remote command's session is
  recorded as that command's launch, so it starts without the ownership warning.
- **A Use now pin outlives the daemon and lets go when its login runs out.** Each head's pins, the command's and each
  session's, are saved in `account-pins.json` (0600, with one `.bak`), so a restart keeps a pin and an unpin alike. A
  pin lets go the moment the login it names reads a limit it had not read when Use now was pressed: the provider naming
  a plan window spent (the whole login's, or one model's week), the provider holding the account, or a usage reading at
  100%. A limit the login already read at the press keeps the pin, since Use now on a login refused Fable alone still
  serves Opus, and so does a short rate limit, which a turn waits out on the same login. What each login read is saved
  with the pin, so a limit read just before a restart still lets it go at the first read after it. A pin or unpin whose
  save the disk refuses is put back before the press is answered. While a pinned login cannot serve, the pin stays and
  the pool says why: `GET /api/auth` and `GET /api/accounts` carry `pin_refusal`, which names a login held for every
  model with its window and when it serves again, one held for one model's week with that model and until when, one
  whose usage reads full with that window and its reset, one its provider refused (sign in to it again), and one
  blocked until its owner accepts new terms (that step), and `pin_models_held`, the models a serving pin cannot serve
  yet. The pinned card on Accounts draws that line every time the page loads. Use now on a login held for every model
  answers 409 with the window and the time and pins nothing, and on a login held for one model it pins and says which
  models stay off it until when.
- **A held turn continues at its reset, and a held login can be tried now.** `POST /api/heads/<head>/holds/try-now`,
  with `?login=<key>` for one login, makes the turn held longest on a login probe it at once; every turn held there
  continues when the login serves, and the console lists a turn as held once try-now can reach it. A session whose turn
  ended at a plan limit is told to continue when the limit's stated reset passes, as one is told when a new account is
  added.
- **A GPT script's opening Write streams into Claude Code's Write block.** When a code-mode script starts with
  `tools.Write` and a literal path and content, Claude Code shows `Write(path)` as soon as the path has arrived and the
  file fills in as GPT writes it, the way a native Write does. Before, nothing showed until the whole script had
  streamed: 5.5 minutes for a 52 KB stylesheet on Nov 3. Claude Code writes exactly the file the script asks for. If the
  script does not make that call as streamed, the block is closed with input Write refuses, so nothing is written, and
  the script itself never reaches the client. It runs only when the client declares a `Write` tool.
- **A session starts at its head's reasoning effort, and Claude Code's own pick rides on every head.** A launch hands
  Claude Code the head's configured effort (`splice.toml`, or Settings, read at each launch, else `high`) as `--effort`
  unless you gave one, and every adapter sends the effort Claude Code's request names. `none` and `minimal` reach the
  wire: a head set to either launches with `CLAUDE_CODE_EFFORT_LEVEL=unset`. Chat heads send it on the rungs their
  vendor takes. A Kimi turn sends it on Kimi's own rungs (low, high, max), never a fixed max, and each word lands on the
  highest rung not above it. A request with thinking off is sent with the effort any other request gets on Responses and
  chat heads, and Claude Code 2.1.310 never sends thinking disabled.
- **A `verbosity` setting for every Responses head.** `low`, `medium` or `high`, in `[defaults]` or a head's
  `[heads.<key>.overrides]` or on Settings, rides each turn as `text.verbosity`, as Codex sends `model_verbosity`. A
  head with no setting sends what it sent before: `low` on a ChatGPT lite turn, nothing elsewhere. It is read when the
  head is built, so a change takes a restart.
- **A head on the OpenAI Responses dialect ships a standing prompt.** A head that sets neither `system_prompt` nor
  `system_prompt_file` gets splice's instructions appended beside Claude Code's own prompt: carry a task to the end
  instead of closing a turn on a plan or a promise, read only what the work needs, check in proportion to the change,
  and do not poll what has not changed. A prompt the head sets replaces it, `system_prompt = ""` turns it off, and other
  dialects ship none, so their requests are unchanged.
- **A relaunch brings a session back as it was.** `splice sessions relaunch` starts a session again on the command it
  was started with, read back from its own process, so a session started as plain `claude` stays in its own config
  folder and signed in, with its agent and every other option it was given, never its first prompt, and on the model
  its row reports. In tmux it comes back in the pane it ran in: the pane is held while the client leaves and the
  session starts again in it, instead of in a new tmux session. A session that is running background shells or holds
  an unsent feedback draft is left where it is, as a working one is.
- **`splice trace --turn` prints each upstream attempt's whole body length and SHA-256.** A trace cuts a body past
  `traceMaxBodyChars`, so three different 20 to 30 MB requests on Nov 8 read as byte-identical in the trace: they
  differed only in the cut tail. Each attempt now records the length and SHA-256 of the whole body, taken before the cut.
- **Perf rows show early request waits.** `pre_admission_ms` and `model_roster_wait_ms` record how long a request
  waited before admission and for its head's model list, beside the stage clock that already ran from the same origin.

### Changed
- **The daemon's memory follows its live data, and its heap stops at 16 GiB.** The 2 GB heap, which ran out of memory 20
  times on Oct 2, is gone: the heap may grow to 16 GiB, or to 75% of the memory the daemon may use where that is
  less, and G1 now gives memory back so that what it holds stays within about 1.7 times the live data. It used to hold
  up to 3.3 times (10.5 GiB for 3 GiB live, measured Oct 19) and grew to 24 to 28 GiB during each start, before the
  16 GiB ceiling. The JVM options live in one place, `SPLICE_JVM_OPTS` in `splice-launch`,
  and every daemon start uses them: `splice restart` and `splice dashboard` now start the daemon through the launcher
  installed beside the jar, so that start also exits on running out of memory and leaves a heap dump. A user unit
  that runs `splice-launch daemon` counts as supervising the install for `splice upgrade`.
- **One engine decides every retry, from what failed and how far the answer got.** Where 0.3.x re-sent every failure
  up to a count with a backoff curve, each failure is now read as a cause and given its own budget: a burst limit is
  waited 2, 4, 8 and 15 s, a throttle 2, 4, 8, 16, 30, 30 and 30 s, an overload 2, 4, 8, 15 and 30 s (a model still
  loading is then asked every 60 s for up to 5 minutes), a path that does not answer 1, 2, 4, 8, 16, 30, 30 and 30 s,
  and a server error before the stream began is re-sent after 200, 400 and 800 ms. An answer whose stream began is
  continued from what arrived up to 5 times, a stream that closed before any event is sent fresh twice, an empty
  answer is asked again once, stale reasoning is left out of the body once, and an unrecognized failure is tried once
  more. A stated reset of 15 s or less is waited
  out on the login; a longer one is held to its time, probed on a schedule and ended if the cause does not lift at
  three of the times it stated. A held turn that outlasts what a session can wait is handed to Claude Code with the
  reset it names.
- **Claude Code is answered by the family of the failure.** A failure a re-send can heal reaches Claude Code as a 529
  `overloaded_error`, which it sends again. splice's own refusal of a request, and a failure the same bytes would meet
  again, reach it as a 400 `invalid_request_error` with `x-should-retry: false`, and a request too large keeps its 413.
  A login splice holds that is refused or missing reaches it as a 403, and a limit no session can wait out as a 429
  with the reset it names. A request whose identical predecessor already ended as a 529 within the hour is answered as
  a refusal, so a failure no re-send can heal ends instead of looping. Every `[splice] ...` line Claude Code prints is
  written in one place.
- **A turn may take 30 minutes, and Claude Code's own timers wait for splice's.** `upstreamTimeoutMs`, the whole-turn
  cap, is 30 minutes by default where it was 15, and it is daemon-wide, like `firstByteTimeoutMs`, `streamIdleMs` and
  `stallReanchorMs`. Every launch now sets Claude Code's `API_TIMEOUT_MS` past that wall, so a held turn ends by
  splice's wall, and turns off Claude Code's fixed 360 s cut of a request that writes nothing while it works
  (`API_FORCE_IDLE_TIMEOUT=0`), caps its re-sends of a non-streaming request that timed out at 2
  (`CLAUDE_CODE_NONSTREAMING_TIMEOUT_RETRIES`), and sets its stream-idle timer to the same wall, with its 300 s no-byte
  check kept for a dead connection. Only sessions launched after the install get them.
- **One model can fill several tiers.** `model_slots = { opus = "gpt-6.1-sol", sonnet = "gpt-6.1-sol" }` now loads and launches with both tiers set, and the picker still lists the model once. A `models` list still names each model once.
- **splice works with the systemd unit that runs it.** `splice start`, `splice restart` and `splice console` go
  through `splice.service` whenever a unit serves this shell, even while it is down, instead of starting a second daemon
  beside it. A unit that runs another home's daemon is refused by name and nothing is stopped, and a shell whose home is
  not the unit's gets a refusal that names the mismatch. A daemon no unit supervises restarts through a detached
  successor, from the CLI or the console's Restart. `splice restart` refuses any word but `--now`.
- **The installer keeps to the HOME it runs under,** and refuses a jar and launcher that do not match, fetching the
  release's launcher when none does. A plain `claude` run under another HOME starts Claude Code directly, or refuses
  with the reason. A launch against an older daemon stops and says to run `splice restart`, and a reinstall over a
  running splice prints the same.
- **`splice status` shows a full plan window.** A ready head whose provider window is full reads ready, at 100%, with
  its reset time; `/health` and `/api/heads` carry `quotaFull`.
- **The local runtime head `splice setup` builds is presented to Claude Code as a Claude model,** so Claude Code prints no unrecognized-model
  line and compacts at the runtime's own window. A runtime that starts late or loads a model later moves its head's
  window on the next check, with no restart; a runtime that is down keeps the window in force.
- **A request body that stops arriving is closed after 30 seconds of silence,** a live setting, `ingressReadIdleMs`.
- **The daemon log records a launch's inline settings by size and hash.** The whole `--settings` body on every launch
  rolled `daemon.log` within hours on a busy machine; every other argument is logged as before.
- **The release's license files** include each bundled dependency's own LICENSE and NOTICE text.
- **The setup sign-in line, the README and the example config** no longer carry a risk disclaimer.
- **Responses WebSockets retire at 35 minutes before their next round.** Age is measured from
  when the socket opened, not from its last use. Retirement replaces an idle connection before
  reuse, never cutting a round already running. The first round on the replacement sends the
  full conversation once, then chaining can resume.
- **Trace bodies use compressed daily packs with one shared rolling budget.** JSONL rows refer to
  deterministic chunks targeting 8 KiB, stored once as zstd frames in `.jsonl.bodies2`. Identical
  bodies and unchanged regions share chunks. Body packs across all heads share a 32 GiB budget.
  New body writes preserve a free-space floor on the trace volume: a twentieth of the volume, and 64 GiB at most.
  Pressure removes the oldest
  completed UTC day's body packs across heads. The current UTC day's packs are protected, and
  JSONL records survive body eviction. An evicted or refused body is marked unavailable with its reason.
  Existing v1 `.jsonl.bodies` and inline rows remain readable, and retention or purge removes
  both pack formats with the day. The JSONL index across all heads is capped at 16 GiB: past it, the oldest
  completed UTC day goes whole (index, every rolled generation and its bodies) across every head. Today and the
  day being written are never removed, and an index line that would pass the cap with nothing left to remove is
  refused with its reason, as a line below the free-space floor is.
- **Trace lists retain metadata, not whole conversations.** Trace API lists and `splice trace`
  tables validate compressed body references in bounded chunks without assembling
  their body text. A single-turn view still reads the stored bodies. Body literals and retained
  metadata keep their heap charges while held. The daemon bounds tracked heap use to half its maximum
  heap, with a separate share for trace reads. A capacity refusal is retryable.
- **Build and runtime dependency pins are updated.** Kotlin 2.3.21, Ktor 3.6.1, coroutines 1.11.0,
  serialization 1.11.0, GraalJS 25.4.4.1.1, Netty 4.2.19.Final, Jackson 2.22.4 and OpenTelemetry 1.67.0 match main's
  dependency baseline. Trace compression adds zstd-jni 1.5.7-25.
- **Batched text and thinking are paced on every head.** When visible text or thinking arrives
  together, splice's shared client write path spreads it over a one-second pacing window.
  Each 16 ms tick releases at least four queued deltas, or all that remain, without reordering
  frames. A lone delta after a quiet stretch, or a stream slower than one delta per tick, is
  written at once.
- **Each code-mode conversation owns its engine.** Its scripts use a GraalJS engine,
  isolate, guest heap and collector keyed by session, conversation and model. Host JVMs start only
  when a cell is admitted. `code_mode_workers` defaults to six hosts per head, with at most four
  session engines in each. A new session fills the least-loaded existing host to four engines before
  another host starts, subject to the host and memory caps. It stays pinned there. The host heap still
  defaults to 512 MiB, with a separate 384 MiB guest heap per engine.
  `code_mode_memory_mb` defaults to a 24,576 MiB head-wide admission reservation, not an
  operating-system resident-memory limit. Including collector and native reserves, a default host
  reserves 1,152 MiB and each engine 832 MiB: six hosts and 21 engines reserve 24,384 MiB; a 22nd
  engine would require 25,216 MiB. Admission refuses before dispatch when the pool is full,
  naming the memory budget, host and engine caps, and both settings in the script's interrupted
  tool output. The model sees that output and the turn continues without a 502. Capacity pressure
  reclaims known-dead sessions or unknown sessions past their 30-minute lease; live and fresh-unknown
  parked cells keep pending calls resumable. A refused incoming exec receives a typed capacity result
  in its tool output, so the turn continues without a 502.
  Empty engines are reclaimed least-recently-used
  at admission and become eligible for idle eviction after one minute. An engine also closes on
  explicit session close, whole-session state expiry, or session death after its last active
  record. A timer can reclaim a parked cell whose session remains unknown for 30 minutes;
  known-alive cells are not reclaimed by that idle sweep. A host that dies fails only its own
  cells, including ones waiting for source; their next cells boot a fresh host in the same slot,
  without rerunning lost source. `Promise.all` and `Promise.allSettled` over direct tool calls still dispatch as one
  batch.
  Saved code-mode state accepts additional fields from newer writers. Malformed or incomplete
  records still fail validation.
- **Code-mode scripts stay inside splice.** The `exec` script a GPT model writes never reaches Claude Code.
  Completed source usage is counted once. Source replies stopped before completion are counted,
  while their token usage stays unreported when no usage arrived.
- **The dashboard is replaced by the console.** 0.3.x served a dashboard at `/` and `/dashboard`, and
  `splice dashboard` opened it. 0.4.0 removes both. The control plane serves the console's pages at `/`
  with no key, because a page carries no data and reads every number from the keyed `/api/*` routes;
  nothing else opens without the key. `splice console` opens it. Accounts is the first page: sign-in,
  Use now and the pin, Soonest reset and My order, rename and remove, key replace and remove, the daily
  budget, and Add provider. `splice dashboard` is gone.
- **A screenshot-heavy session on a large-window model keeps its images.** Claude Code keeps at
  most 100 images in a request, or 600 when it counts the session as 1M-context, and strips the
  oldest past that. splice now hands Claude Code the 1M form of a non-Claude model whose id has no window suffix and whose configured window
  is at least 425,000 tokens, unless Claude Code already knows that row, and scales the token counts it reports so Claude Code still compacts
  at the model's real window. Smaller models keep their names.
- **Usage-cost figures are API-rate estimates, and the default ChatGPT, Grok, Muse and Claude models have rate cards.** The status line printed a bare `$0.85`, and on a subscription head no token is billed at
  all, so it read as a charge. It now reads `$0.85` (a label first, `API est.`, then dropped from the line on Oct 15 to keep it short), and a budget's refusal and warning say
  "an estimated $2.50 in API cost". Claude Code prices its own `total_cost_usd` at Anthropic's card
  whatever head it talks to, so that figure now shows only on a head whose upstream is Anthropic; on
  any other head a model splice cannot price shows `no rate card`, where it used to show Anthropic's
  price under GPT-6-Sol. On a head whose upstream is Anthropic, Claude Code's figure wins over
  splice's own whenever it has one, because it is priced at that upstream's card from the session's
  start. splice's own figure prices each turn at the card of the model that turn ran on, not the
  model the session is on now. It comes from a running total each head keeps per session as it
  records each turn (`<head>-session-totals.json`), rather than counting only the turns in the
  last 256 KiB of the perf log. It reads `≥` when a turn has incomplete or missing usage, when its
  model has no card, or when the session began before its head kept the total and the perf
  log no longer reaches its start. `splice add` writes the vendors' published API
  rates for gpt-6-sol, grok-4.7, muse-spark-1.3 and claude-opus-5-5. A card can carry a long-context tier (`long_context_over_input_tokens` with
  `long_context_input`, `long_context_cache_read`, `long_context_output` and optionally
  `long_context_cache_write`) alongside its ordinary rates. A build check catches splice's Kotlin code spelling a dollar amount anywhere but
  the one place that adds its API-estimate label; a budget limit you set is the one amount printed plain.
- **Restarts wait for compactions and keep their finished answers.** `splice restart`,
  `splice upgrade` and every restart the daemon takes on itself (`POST /api/daemon/restart`,
  an add's save) wait for compactions in flight before stopping. The daemon keeps serving during this wait and no head
  closes admission, so ordinary turns do not hold the compaction wait. Each compaction is waited
  for until it reaches the daemon's own cap on a turn (`upstreamTimeoutMs`, 30 minutes by default), and the whole wait has
  the same cap, because Claude Code 2.1.296 waits longer than that on a large conversation; `--now` skips
  it, and an unreadable in-flight count is reported before restarting without the wait. A finished
  answer whose client disconnected is kept owner-only under `<state>/compactions/<head>/`. Its
  identical retry can replay that recording for up to two hours without another upstream run;
  expired recordings are swept at head start and when another answer is saved.
- **Installing a release needs no GitHub account.** `install.sh` and `splice upgrade` used to stop
  unless the GitHub CLI was installed and signed in. Now the release jar and launch shim are still
  checked against the release's `sha256sums.txt`, and a mismatch still refuses. The build-provenance attestation is
  verified whenever `gh` is signed in, and a failed attestation still refuses. Without a signed-in
  `gh` the install goes ahead and prints the `gh attestation verify` command for each file it downloaded: the jar,
  the launch shim and, on Linux x86-64, the MCP hub's holder and front.
  `splice doctor` reports a missing or signed-out `gh` as information, not a warning.
- **Code mode is out of beta and on by default for ChatGPT.** A `chatgpt-oauth` +
  `openai-responses` provider enables the bundled JavaScript runner and guidance with no config
  line for models marked `code_mode_only` by its backend, plus `code_mode_models`; `code_mode = false` still turns it off, and every other provider shape stays off.
  A single tool result over the 64 KiB text frame that admission used to reject is now truncated
  at admission behind a `[truncated N chars]` marker and the turn completes.
- **Errors the client sees name splice, not another vendor's product.** A stream that ends without
  its completion event, a refused or failed upstream response and a context-overflow refusal used
  to reach Claude Code prefixed `claudex:` or attributed to the `ChatGPT backend`, on every head
  including the ones that have nothing to do with ChatGPT. On the Responses path, a stream that ends early now reads
  `splice: upstream stream ended` and a model refusal reads `upstream: model refused`. Other dialects keep their own
  tags. A context overflow reads `prompt is too long: ` followed by the upstream message.
  Saved test expectations use the new error text; recorded traffic is unchanged.
- **Vendor-specific rules live in each vendor's own module.** Request rules moved out of
  shared protocol code into the provider adapters. The shared Responses dialect still carries Codex's WebSocket
  header names.

- **Code mode sends the tool surface Codex sends.** A code-mode turn declares `exec`, with hosted tools other than
  `tool_search` beside it, the
  way codex-rs does for a model its catalog marks `code_mode_only`: a freeform tool with Codex's lark
  grammar, whose description is the manual of every fully declared tool rendered as a TypeScript declaration;
  deferred tools appear in `ALL_TOOLS`. Before, every client tool rode beside a small `splice_exec`, and the model called the
  runner on 0 to 10% of turns and read files one call at a time. In a script a tool is
  `await tools.Read({...})`, `text()` adds a line to the script's output, `ALL_TOOLS` lists every tool with its
  declaration (deferred ones too), and `exit()` ends early; `tools.call(name, args)` still runs for
  scripts written before. No `tool_search` rides beside `exec`, as Codex sends none: a deferred tool
  is found in `ALL_TOOLS`. A script's result reads the way Codex frames it: `Script completed`,
  `Script failed`, or `Script terminated` when splice ended the script, the wall time, `Output:`, what the script returned, and on a failure
  `Script error:` last. The guidance batches independent reads in one `exec` with
  `Promise.allSettled`; a client that disables parallel tool use gets the sequential variant.
- **Claude Code's system messages reach GPT-5.6 and GPT-6 the way Codex sends context.** Peer messages, task
  notifications and hook output arrive from Claude Code as `system` messages; on the ChatGPT backend's GPT-5.6 and GPT-6 models they
  now ride as developer messages, as codex-rs sends mid-conversation context, and a script whose
  results they follow still completes.
- **The ChatGPT backend refuses `parallel_tool_calls = true` on GPT-5.6 and GPT-6.** Batching there comes from
  code mode. The setting applies only to those models on the ChatGPT backend, where it must stay false; on any other model or backend it does nothing.
- **Repository layout consolidated.** The `experiments/` cache-replay
  reproducer, the `goals/` note and the `.superpowers/` leftovers are gone; the one tracked
  milestone report now sits under `docs/superpowers/plans/`, and the untracked `dev/`
  tree is folded into `.dev/`. Local machine paths are gone from the tracked ledgers and plans.
- **The shipped code compiles warning-free, and a new warning fails the build.** Every main
  source set compiles with `allWarningsAsErrors`, and a discarded result the compiler flags
  (`RETURN_VALUE_NOT_USED`) is an error in tests too.

- **An endpoint override that carries your token is honoured only on loopback.** The sign-in and usage
  endpoints can be pointed at a stand-in for tests (`CODEX_OAUTH_ISSUER`, `CODEX_OAUTH_TOKEN_URL`,
  `CODEX_OAUTH_AUTHORIZE_URL`, `GROK_OAUTH_ISSUER`, `GROK_OAUTH_AUTHORIZE_URL`, `GROK_OAUTH_TOKEN_URL`,
  `KIMI_OAUTH_HOST`, `CLAUDE_OAUTH_USAGE_URL`). Those endpoints receive your token, so splice accepts one only
  when its host is `localhost`, `127.0.0.1` or `::1`; any other host is ignored, the vendor's address is
  used, and the daemon log names the variable once.
- **A request that reaches a stopping daemon meets a closed connection, never a 529.** Claude Code 2.1.312 moves a
  session that has a fallback model onto it after three overloaded answers in a row, and the draining 529s of Oct 28 moved
  Haiku and Sonnet sessions onto their Opus fallbacks. A stopping head now closes the connection without an answer, and
  Claude Code retries a connection error on a new connection, which reaches the next daemon, with no fallback. On a
  socket its service manager handed it, a draining command stops accepting at the start of the drain, so a request that
  arrives then waits in the kernel for the next daemon, and a connection kept open and idle when the drain starts is
  closed, so the client's next request opens a new one that waits too. `tools/probes/restart-drain-read.ts` reads how a
  restart went at any install.
- **Accounts and Usage open quickly after the daemon has been idle.** The economics read stays warm between opens and is
  ready after a head's own hourly totals are read, not after its whole request window, so the first open no longer
  queues behind every head. The turns route reads a week from an in-memory store, and the session history index
  refreshes incrementally, reading only a transcript that is new or changed.
- **A chunked request body is admitted with a small charge that grows per chunk,** so four small chunked requests no longer
  fill the budget. One past the size cap or the heap is answered mid-body and its charge returned.
- **A credential hold says what happened.** With no relayed provider reply to hand back, it reads "The provider
  limited this account, so this gateway is holding new requests for Ns", not a sentence that claimed retries splice
  never made. A relayed vendor 429 hold waits like any other short hold.
- **Compare models sends every model the same system line,** "You are a helpful assistant.", so no answer comes from an
  instruction another model did not get, and each request shows it as its system.
- **Compare models sends are recorded like any request.** Each send leaves a row on Requests with the tokens its stream
  reported, counts in its command's figures, its price and its budget, and a stream that reports no tokens reads
  "Uncounted". A command over its budget refuses the send as it refuses a turn, with "Over budget" and a door to that
  budget, and leaves an "Over budget" row. These rows belong to no project: they are listed under "Compare models",
  never under "No project".
- **Teams, Usage and Settings draw their own shape while loading,** like Requests, instead of a blank page.
- **`/api/auth` returns only the fields the console and the command line read.** A login whose file cannot be read
  reads "can't read the login file".
- **GPT sessions no longer show the code-mode script as reasoning;** the reasoning summaries are unchanged (Marcos,
  Oct 12).
- **Restart splice, pressed while requests are in flight, names the agents mid-request** and says each ends at once with
  the restart line, since the stop gives a running turn no time to finish. Compactions are still waited for first.
- **The streaming-idle limit, the activity retention and message edges apply without a restart.**
- **A team member stopped at Claude Code's folder trust question waits for you** instead of timing out (Marlin,
  Oct 12). The Locked state shows on every page, and Teams and the trust page read as walked.
- **Models says why a save was refused,** and an MCP row shows its working folder.
- **End closes a session in any state, and End all closes a team's.** Teams and Sessions have the buttons; a session
  that is working is refused with the reason.
- **Sessions lists the week's ended agents, and every link to one opens it.** The Ended fold reads them from Claude
  Code's history, a search finds them by name or words, and a link from a request opens that agent at the request's
  moment, with Show newest to jump to the end.
- **A live session's pane gives the `SendMessage` line another session types to reach it,** with Copy. A session splice
  did not open takes a note from its pane when its Claude Code is a version the note reaches, and the pane says Note
  sent, since the client gives no receipt.
- **Sessions marks a session whose registration stopped updating "Not heard from since" its last update,** and ranks
  it with the idle ones. An empty list says runs started with `claude -p` never appear there.
- **`splice sessions` names the model each session runs,** beside its head and account.
- **`/api/models` and `/api/mcp` carry only what a reader takes.** `context_window_source`, `description`, an
  unresolved row's guessed window and the cache rates leave `/api/models`, and `GET /api/models/upstream` is gone.
  `/api/mcp` drops each server's `session_ids`, class, pid, streams, start time and restarts.
- **A tier with nothing set reads the model that answers,** with a muted Default.
- **A command chip and the One command menu name the command a person types,** never the head key splice files it under.
- **A local provider splice has no name for reads as its runtime,** such as Ollama, on its card, its pane and Every model.
- **A head's colour is its provider family,** and the Claude template reads in Claude's colour.
- **The Models log says Restored, Settings re-read, and No events when there are none,** and its summary times drop the
  seconds.
- **No string splice prints or sends carries an em dash.**
- **`splice login` names a Claude login by its label, never its email,** in every sentence it prints.

### Fixed
- **Prompts and answers are saved on a disk with less than 64 GiB free.** splice stops saving them when the disk is
  nearly full, and "nearly full" was 64 GiB free on every disk. That is more than most laptops have free, so there
  nothing was ever saved and Settings read "Saving stopped" from the first request. The floor is now a twentieth of the
  disk, and 64 GiB at most: a 256 GiB disk keeps 12.8 GiB free, and a disk of 1.25 TiB or more keeps 64 GiB as before.
  Settings names the figure for that disk ("The disk is down to the 12.8 GB splice leaves free"), where it said 64 GB
  on all of them, and the daemon's log line carries it too.
- **`splice setup` opens on the plans to connect.** Its first screen was a "Starting point" menu (OpenRouter, a
  ChatGPT, Grok, Kimi or Muse sign-in, or "Existing topology") whose pick changed nothing but one line of the summary:
  "Connect the chosen plan with: splice add openrouter", said even to a person who had ticked only Claude on the next
  screen. That menu and its line are gone. Setup now opens on the list where a plan is actually chosen, titled "Plans
  to connect", with Claude's own login at the top. A tick list says which keys act ("space ticks a row · enter
  continues"): it named none, and Enter on a list of empty circles connects nothing. Leaving the list with Escape or
  Ctrl-C ends setup, where it went on to the next question.
- **Two sign-in refusals no longer name a command-line flag.** A sign-in asked for a second account on a command with
  no first login was refused with "sign in without --label first to create the primary chatgpt-oauth account", on a
  console card as well as in a terminal. It reads "this command has no first login yet: sign in once with no label,
  then add another account", and the refusal of the first login's own label reads the same way.
- **A new Claude command names no model, so Claude Code shows its own.** `splice add claude`, and the Claude row of
  `splice setup`, wrote `pinned_model = "claude-fable-5-1"` and a list of four models (Fable 5.1, Opus 5.5, Sonnet 5,
  Haiku 4.5): a new install started on last month's model, and its list was behind the day Fable 5.2 came out.
  Marcos, Nov 9, 2026: "for default anthropic claude it should juyst show the default models iot shouldn't list
  anything otherwise when new models like this comes up wi'll miss them". The command writes neither line now, as
  the head doc always allowed for a provider that forwards the client's own login, and setup says "Claude Code's own
  models" in place of asking which to keep. The provider's rows, which carry what splice knows about a model and are
  not the list Claude Code shows, name Fable 5.2, Opus 5.5, Sonnet 5.5 and Haiku 5.5 at 1M tokens each, and
  `splice.example.toml` follows. An install that already has a Claude command is not rewritten.
- **Turns on Claude Fable 5.2 have a price.** The shipped price table had no row for `claude-fable-5-2`, and a Claude
  command takes whatever model Claude Code asks for, so every turn on it counted as unpriced: on one machine, from
  10 AM to 3:23 PM CT on Nov 11, 2026, 3,813 of 4,633 turns on its Claude command carried no price and the plan's
  value read a fraction of what it was. The row is there now at Anthropic's published price (8 in, 40 out, a cache
  read at 0.20, writes at 10 and 16 per million tokens, read Nov 11), beside one for `claude-haiku-4-5-20251001`, the
  dated id Claude Code still sends for some background turns.
- **Setup's summary no longer opens on a file it will not write.** On a new install the box before "Install now?"
  began "No starter will be written because one is already present", since the installer had written it a moment
  earlier. The summary lists what the install will do, and that line is gone when there is nothing to write.
- **`splice doctor` on a machine with no Claude login says to sign in, once.** A new install that added the Claude
  command before anyone had signed in to Claude Code read "every account is out (no reset time reported)" with the
  fix "Wait for the earliest reset above", and under it one warning per login place saying "Sign in again". There
  was no limit to wait out and nothing to sign in to again. It reads "not signed in to Claude Code yet" with one
  step, `Run claude-splice and use /login.`, and a login place that holds no login beside one that does is told
  "Sign in", not "Sign in again".
- **`splice setup` says what each row is, and its install no longer talks over itself.** A first run on a new
  machine, Nov 11, 2026. The list of commands to add named each row by its profile alone ("codex", "muse") and marked
  every row splice held no credential for "sign-in needed", the Claude row and the API-key rows among them. It opened
  with two lines that read as failures before anything had been tried ("splice add api-key: needs a base URL; run:
  ..."). The installer's first line ran into the spinner ("Installingsplice: wrote starter topology"), "installed
  'splice'" came with every command added, and `splice doctor`'s whole report came once per command. A Claude command
  was checked as "credential present" on a machine where nobody had signed in, and its four rows each said "unchecked:
  provider lists no window size" on one line. Now each row says what plan it is and how it signs in, and says "signed
  in", "key found in DEEPSEEK_API_KEY" or "key saved" only when splice already holds one, a key saved by `splice key
  set` included. The two kinds the list cannot add come after it, each as what it is and the command that adds it.
  The installer's lines come once the spinner has stopped, `splice` is announced when its link is made or changed,
  and the checkup runs once, after the last command. What setup found reads in words ("Found here: Codex (ChatGPT)
  signed in, splice running") instead of config identifiers, and the start menu's hints say "key found" or "not signed
  in" where they counted "1 keys" and "0 credentials".
- **The console is named where a new install looks.** Setup's last screen and `splice status` both say that
  `splice console` opens accounts, models and usage in a browser. The line had left the last screen on Oct 7 with the
  old dashboard, and nothing a person runs on a new install had named the console since it came back.
- **`splice` typed alone says what it is.** On a terminal it prints what splice does in a line, the four commands to
  start from, and every verb, and so do `splice --help`, `splice -h` and `splice help`. A bare `splice` started a
  second daemon in the person's terminal, which printed "another splice daemon serves on :3096; exiting (the winner
  serves)" and left, and `--help` printed the usage as an error. With no terminal attached a bare run still starts
  the daemon. The usage is broken into lines an 80-column terminal shows whole.
- **Cancel on a sign-in ends it, and Sign in again always gets a new sign-in.** On the Accounts page, Cancel on a
  sign-in only forgot it in the page. The daemon kept it: Claude Code's own sign-in ran on for its ten minutes, and a
  browser or device sign-in held its label and its callback port for up to five. The next Sign in on that login was
  then refused, as "In progress" with nothing to press on a Claude login, and as a config error or a bare "Not
  completed" on the others. On Nov 11 a sign-in started at 9:54 AM CT and cancelled on the page was still running at
  10:04, and its login could not be signed in again until it ran out. Now `DELETE /api/auth/{head}/login/{id}` ends a
  sign-in where it stands: its process or flow is stopped, what it held is free, and the page's Cancel waits for that
  answer before the card lets go. A new sign-in to the same login ends the one still open and takes its place, so
  pressing Sign in again is never refused by your own earlier press. Removing a login ends a sign-in still open for
  it, which until now could file the removed login again if its page was finished afterwards. A page opened while the
  daemon holds a sign-in (a reload, a second tab) shows it on its card with its Cancel, from `GET /api/auth/logins`.
  Discarding a provider being added ends its sign-in too. The daemon's log now has one line when a sign-in starts and
  one when it ends, saying how; it had none. The same holds on every page that signs a login in: Usage's sign-in box
  on a login's card, the Models page's sign-in for a command and for a provider being added (`DELETE
  /api/add/{id}/login`), and Compare's, which each only forgot the sign-in in the page. A sign-in on Accounts and
  Usage also follows the daemon now: one status read splice did not answer used to end it on the card as "Not
  completed" with no reason while the daemon went on holding it. The row now waits through reads splice does not
  answer, and a daemon restarted in the middle of a sign-in reads "Stopped", with that as its reason. A pasted code
  the sign-in does not take ends it on the daemon as well, with the daemon's reason on the card. Signing in again on
  a Claude login's card while the browser is signed in to another account read "Config error" with nothing to press;
  it now reads "Other account", says the login kept its own, and offers Try again.
- **A Claude login signed in again is asked again at once.** A login Anthropic had refused stayed held, and its
  card kept reading Blocked, after it was signed in again: the hold only ended when the login served a turn, and the
  login was not tried again until the hold's time ran out, up to an hour. On Nov 11 a login blocked while its
  organization's payment had failed was signed in again after the payment was fixed, and nothing changed on its card.
  Now a sign-in the person finishes for a login ends that login's hold, so the next turn that reaches it asks
  Anthropic with what was just signed in, and Anthropic's answer decides: served, and the block is gone, or refused
  and held again with the reason. Until that answer the card goes on reading Blocked, and says its next turn asks
  again. A credential file Claude Code rewrites by itself still does not end a hold. A limit the login read stays.
  The same holds when the sign-in comes back "Already added", as it does on a command's own Claude Code login signed
  in again with the account it holds: nothing is filed, the hold on the login that holds the account ends, and the
  card's reason says so.
- **A blocked login's card has Try again.** A login Anthropic refuses until its owner acts (new terms to accept, an
  organization that does not allow subscription access) read Blocked on Accounts with one act, Sign in again, in its
  menu, and a sign-in is not what a block waits for. Once its cause was fixed, nothing on the card told splice so: the
  login was asked again only when its hold had run out, up to an hour, and a turn happened to reach it, or after a
  daemon restart. On Nov 11 a login blocked while its organization's payment had failed was signed in again for want
  of any other act. Now the card has Try again beside its lock. Pressing it ends that login's hold
  (`POST /api/auth/{head}/ask`), and the next turn that reaches it asks Anthropic. The card goes on reading Blocked
  until Anthropic serves a turn on it, since only that shows the block is lifted, and says "its next turn asks again"
  with nothing left to press; a login whose hold ran out by itself reads the same. A login still refused is held
  again from that answer, with Try again back, and the turn goes on to the next login as it did before.
- **+ Add account on a ChatGPT or Grok command adds a login, and no longer writes over the first one.** A sign-in
  sent to splice with no label is the command's first login signing in again, and it lands over that login's file.
  Since Oct 13 the Accounts page asked for a place of its own only on Muse and Kimi commands, so on a ChatGPT or Grok
  command that already had a login, + Add account signed in as another account left one card, holding the new
  account where the first had been. Now every command that signs in through splice asks for a new place once its
  first login is there, and the new login joins beside it. A command whose first login is signed out has no pool to
  join yet, so its add still signs that first login in. An add that comes back as the account a ChatGPT command's
  first login already holds adds nothing: it reads "Already added" with splice's reason under it, and the card that
  account has pulses once. It filed a second login for that one account, since the sign-in page signs in whichever
  account its browser holds. An add that comes back as a login added earlier signs that login in again, and its
  card pulses once, where the tile closed with no sign that anything had happened.
- **Every Claude sign-in has the field for the code its page shows.** splice signs a Claude login in through Claude
  Code's own sign-in, and the page its link opens ends by showing a code to paste back. Only a command's own Claude
  Code login had the field for that code. Sign in again on an added Claude login, and + Add account on a Claude
  command, opened the same page and left the code with nowhere to go, so the sign-in read as still in progress
  until its ten minutes ran out. splice now says in a sign-in's status that its code is pasted back
  (`paste_code`), and Accounts, Usage, Models and Compare each draw the field from that. A code splice does not
  take ends the sign-in with its reason.
- **Models and Compare say why a sign-in failed, and Compare's sign-in has Cancel.** A failed sign-in on Models or
  Compare read its word alone ("Other account", "Expired"); splice's own sentence now stands under it, as on
  Accounts and Usage. A sign-in started from a Compare pane could only be ended by closing the pane; it has Cancel
  from its first moment, before splice has a link or a code to show, and it reads Cancelling until splice has ended
  it.
- **Usage draws no card for a Claude login place that holds no account.** A command's own Claude Code login place
  whose file held no usable sign-in was drawn on Usage as one more login ("Account 4, Signed out", with Sign in),
  where Accounts has left such a place off its board since Oct 27. Both pages go by one rule now: signed out, no
  proved account and no reading is no login.
- **A sign-in behaves the same on Accounts, Usage, Models and Compare.** Each of the four pages ran its own copy of
  a sign-in's steps, and the copies had drifted. Now they share one. On Models and Compare a sign-in that failed says
  how in a word (Expired, Stopped, Config error, Already added, Other account) where it read "Not completed" for
  every one, and a sign-in that ended because splice restarted says Stopped at once, where the page went on drawing
  "Signing in" for five minutes. On Usage the box says the same word over splice's reason, and offers Try again only
  where trying again can work. Cancel on Usage and Models reads Cancelling until splice has ended the sign-in, and
  "Not cancelled" when splice did not answer, as on Accounts. A sign-in whose card, pane or form is gone ends on
  splice too, and none of the pages gives up on one before splice does.
- **Removing an added login from a ChatGPT, Grok, Kimi or Muse command takes it out of the running command.** Those
  commands build their list of logins once, when they start, so a login removed on Accounts stayed in the running
  command, listed with no sign-in behind it and a Sign in that would file it again under the label just removed,
  until splice next restarted. The command now starts again after such a removal, as it does when a sign-in lands,
  and finds its logins as they are on disk. A Remove splice refuses is read against what splice holds now, so a
  login another tab already removed leaves with its card instead of keeping "Not removed" on it.
- **A measured period counts each recorded minute once, and reads all of them.** The Performance panel's "Measure any
  period" and `splice perf` gave a different rate for the same day minutes apart: 1.47 completions a minute, then 50.9
  an hour, then 3.59 a minute, beside a request log of 24,203 requests for that day. Three faults in how a measurement
  reads the recorder's files, none in what was recorded. The newest minutes were counted twice, once from the file and
  once from the recorder's memory: for ten minutes that held 92 completions the summary answered 123. A period that
  began or ended inside a minute lost every series that minute touched, for the whole period. And the read ran out of
  its 2 MiB after about 700 series, where one hour of a desk running ten commands records 616, so a day stopped inside
  its first hours and the newest went unread. The files now answer only for minutes the recorder no longer holds, a
  minute that crosses the period's edge stays out alone, and the read charges a series what it takes to hold, merges
  boots once time is one bucket, and past that turns away only a series it has not met, saying how many. A gap reason
  that repeats no longer hides the others: 55 "retention expired" intervals had filled the list. A period measured
  across a restart also counts what splice recorded, before it restarted, in the minute it restarted: those requests
  were left out for as long as that minute was the oldest one the recorder still held in memory.
- **The performance recorder folds old minutes into hours again, so its history reaches back weeks.** Minutes older
  than a day are folded into one file an hour. An hour that did not fit was tried again at every checkpoint, twice
  every five seconds, and no later hour was ever folded: on one desk the hour of Nov 9, 5 PM CT was read 66,183 times,
  minute files filled the recorder's 176 MiB, and its history reached back 38 hours where it is meant to reach 35
  days. That hour held 1,348 series. The fold charged each a flat 2 KiB and gave up near 900, and the write refused a
  file at a third of the size a read accepts, counting three bytes a character. The fold now charges what a series
  takes to hold, the write measures the file it would write, and an hour that still does not fit stays as minutes
  while the next hour gets its turn.
- **A measured period's History tab draws again, and `splice perf series` answers for more than a few minutes.** Every
  entry of the series reply repeated the reply's whole list of gaps, about 1,000 characters each on a desk with eleven
  to report, and a reply past its 4 MiB was refused whole: five minutes answered, fifteen did not, and the tab drew
  "No sampled history" for every longer period. An entry now holds its interval, scope and measurements, and the gaps
  stay once on the reply. When the entries alone are still too many, their intervals double in width until the reply
  fits, and `resolution_ms` says the width. The tab names a compaction's readings, which read as a second row of the
  same scope, and says so when the recorder refuses a period instead of calling it empty.
- **The Performance panel says what its rates are made of.** Under a rate it names how much of the period is missing
  from the recording, where it said only that the population was partial. "Arrivals recorded" says an arriving request
  is not yet known to go upstream, where it read "Not measured" with no reason. The request counts say they come from
  request-by-request records, which hold part of the period, while the rates come from the recorder's minute totals.
  The two Runtime readings both labelled "CPU" read "CPU time" and "CPU load".
- **The recorder no longer turns away a minute's first readings when an idle head or model wakes.** A gauge that had
  not changed for some minutes, on its next change, marked every minute the recorder held as needing to be written
  again. A marked minute cannot be given up for a new one, so for the few seconds until one was written a full
  recorder refused every new series, and what it refused was gone: on one desk, one completion of 602 in an hour and
  the tokens of two requests. Only the minute in progress is marked now; an ended minute's file already holds the
  same reading.
- **A measured period lists its recorded requests again, and `splice perf turns` answers on a desk with a long
  history.** The list read every kept generation of the request log from its first byte, three times each, and then
  digested them all twice more for a first page of one row, against 512 MiB of work. On a desk with 35 archived
  generations of 64 MiB the work ran out inside the third, and every period, the last minute included, answered
  "reached its work limit" with no rows. A generation that ended before the period now stays unopened, by the rule
  the Requests list already reads by. A generation is read once, its digest taken from the bytes that were read, and
  a page digests only the files its rows come from.
- **The Profiles tab no longer shows a failed capture nobody started.** With no capture taken, the profile status
  answered a made-up record, failed, with a new id on every call, and the tab drew a red "failed · 0 B". The status
  now answers `none` with no id, `splice perf profiles status` included, and the tab says no capture has been taken.
  An id that is asked for and no longer kept still answers `profile_not_found_or_expired`.
- **A Claude login whose organization refuses it is held, instead of being tried first by every new session.**
  Anthropic answers such a login with a 403 reading "OAuth authentication is currently not allowed for this
  organization", which Claude Code shows as "Your organization has disabled Claude subscription access for Claude
  Code". splice read it as a model the plan leaves out: the turn moved to another login and nothing held the refused
  one, so a pool that ranked it first sent every new session's first request to it, 20 of them in 22 minutes on Nov 10.
  It is now read as a block on the account, like the updated-terms answer: the login is held out of later turns while
  another can serve, a turn with no other login asks it once to learn whether an admin allowed it, the switch reason
  reads "previous login's organization does not allow subscription access", and a turn that ends on it says
  `<head>'s login <label> is blocked until its organization allows subscription access.` `An admin allows it in the
  organization's settings first, then send the message again.` A pin on such a login names the same step. Its card on
  Accounts reads "Blocked until its organization allows subscription access" under a lock, where it read "Signed out"
  beside a Sign in button that no block is lifted by; `/api/accounts` carries the step as `owner_block`. The hold
  outlasts a credential file Claude Code rewrites around the same account, which ended it before: on Nov 11 a rewrite
  one second after the refusal sent the next session's first request to the held login 56 seconds after its hold
  began. A file that names another account still ends it at once. The card and the switch reason keep the step after
  the hold's time runs out, until the login serves a turn: before, a login refused at 2:29 AM CT read "Use now" again
  from 3:29 AM, and a session that found its hourly ask already running moved off it with "account unavailable".
- **A new field in Claude Code's request no longer takes a Responses head down, and a head that refuses every turn says
  so.** Claude Code 2.1.321 sends a top-level `diagnostics` field, which refused every claudex turn from 5:51 to
  6:58 PM CT on Nov 9 while daemon.log, the perf file and the trace held nothing. Every top-level field of a Responses
  request is now a control the request carries or refuses, a field no model reads here (`diagnostics`,
  `context_management`), which is dropped and logged once, or an unclassified field, which is refused and named in
  daemon.log unless its name says it is the client's own record (`telemetry`, `metadata`, `trace`, `debug`), when it is
  dropped. A request refused while it was prepared is recorded like any other: one log line with the client's line,
  session and User-Agent, a perf row, the turn's start and end, and the trace when the head's is on, under the outcome
  `error:refused`. A head that refuses five requests in a row for one cause before any upstream turn reads degraded: a
  `HEAD DEGRADED` line in daemon.log, `refusingHeads` in `/health`, a `refusing` mark on the heads route and "refusing
  every turn: ..." in `splice status`, until the next admitted turn clears it. `/health` `ok` is unchanged, and liveness
  probes open no request row.
- **A request Claude Code sends again no longer restarts the work.** On Nov 8 and 9 one subagent's non-streaming request
  came back 228 times in 25 hours, each time cut by Claude Code at about 360 s, which is Bun's own fetch timeout, and
  each re-send began five minutes of upstream work again. A non-streaming turn with a session now runs on when its client
  hangs up, as a compaction does, and an identical re-send is answered from its hold while it runs, within 32 holds
  and 2 hours; a held turn that fails tells its waiters that failure, never a 200 carrying an error. Launches set
  `API_FORCE_IDLE_TIMEOUT=0` and `CLAUDE_CODE_NONSTREAMING_TIMEOUT_RETRIES=2`, so the head's deadline answers first and
  the client re-sends a timed-out request twice at most. A client that hung up while splice was still driving the turn
  now leaves its turn line in daemon.log.
- **A held streaming turn outlives Claude Code's stall watchdog.** While a head held a turn for a limit days from its
  reset, Claude Code counted thirty of its own pings, showed "response stalled" and sent the turn again: fourteen
  pairs in 75 minutes on Nov 6. A session launched through splice now has the client's stream-idle timeout set to its
  API timeout, which a hold already ends before, and keeps a 300 s check for a connection that sends no byte.
- **The Responses WebSocket's hourly close is a rotation, and a turn that thinks only emptily ends.** On Nov 8 one GPT
  turn ran 6.3 hours producing 6,715 near-empty reasoning items: the socket closes every 60 minutes, and each close was
  read as an overload and spent the recovery budget until the turn ended as "stayed overloaded". A close at that limit
  is now a rotation with its own count of 12 and no line to you. A run of at least 100 completed reasoning items with
  no summary, no text and under 2,000 characters of encrypted reasoning, lasting at least 10 minutes, ends the turn with
  a sentence that says so; silence adds nothing to the run, so a model thinking quietly is never cut. A round whose
  frame would pass the peer's 32 MiB limit goes straight to HTTP, and so does a 1007 close before the first event, with
  no fresh-socket re-upload.
- **A re-sent request no longer carries every earlier attempt's reasoning, and one conversation cannot crowd out the
  rest.** Each attempt of the Nov 9 request streamed about 190 reasoning items and ended unfinished, and the next
  re-send carried them all: 6,715 records at one position from 37 attempts. A turn now holds its reasoning until the
  client keeps what it led into or a round reaches its terminal, so a turn that ends before either leaves nothing a
  later request carries, and a daemon restart replays none of it. The first record a turn stores at a position
  replaces what another turn's attempt left there, unless the request ended on assistant output. The reasoning cache
  gives each conversation a quarter of each bound, so one growing conversation no longer evicts its neighbours whole.
  A refusal reads as stale reasoning only when it is a 400 or 422 for `invalid_encrypted_content` or for an item's
  `encrypted_content`, not when a 401, a 429 or a refusal of the `include` value happens to name it.
- **A GPT answer the backend cuts after a script step is carried on.** At 23:22 CT on Nov 9 a gpt-6-astra answer was
  closed with 1012 after 776 frames of its final answer reached the client, which was told "Server error
  mid-response". The continuation did not recognise the prose that followed a code-mode script step, so every answer
  cut mid-prose after one since Oct 24 reached the client as an error. It is now carried on and ends clean.
- **Code mode's history journals are bounded and swept.** At 08:00 CT on Nov 9 four claudex turns failed with
  "authoritative history journal exceeds its byte bound" while 1,977 journals held 7 GB. A journal near three quarters
  of its cap, or one an append would push past it, is rewritten as its newest posts within a quarter of the cap and
  moved into place; only a single post set over the cap is refused. An hourly sweep, first run two minutes after the
  daemon starts, removes journals idle past the records' time to live and orphan lock files, and spares conversations
  held in memory. A Settings clear or history cut leaves the files a running head holds. A journal removed or cut
  short under a running head is rewritten whole at the next write, and posts kept only in memory while journaling was
  off are written to their journal once it is back, before an eviction can drop them. A script or callback that
  already settled keeps its outcome, where a close a few milliseconds later could record it as cancelled, and a burst
  of native callbacks is coalesced without losing ownership.
- **claudex no longer reads "out of quota until Nov 12" while it serves turns.** A usage-limit hold written on Nov 5 was
  keyed by that day's access token, the token was renewed that evening, and the hold's file was restored at every boot
  with no send able to clear it. A head that holds a login of its own reads that login's hold alone.
- **Every credential file is renewed by one ladder.** The codex, grok, kimi and Claude account files and Muse's key
  had each their own copy of the renewal, and they had drifted. A renewed pair whose local write fails is kept in
  memory, served, and written again at the next renewal or lookup, where it was dropped after the old refresh token was
  already spent. An expired token whose renewal fails ends the turn as the refused login it is, and the retry engine
  asks again when the endpoint was only unavailable, rather than reading "no login". A Claude account's token that is
  still alive is served when its rotation fails, and a 401 always rotates. A device-code sign-in keeps polling through
  a 5xx, a 429 or a failed poll. A credential lock or file this machine cannot use reads as a local failure, never as a
  connection reset by the provider.
- **Muse's usage poll backs off, and a key it minted is not lost.** A refused poll waits a minute, doubling with each
  refusal in a row up to an hour and never less than the mint's `Retry-After`. A subscription the provider reports
  inactive shows on the account with its renew page until a mint grants a key, and refuses no turn. A minted key whose
  write failed is kept and written again, where every later turn minted again on the dead key.
- **A compaction whose client left keeps running when Ktor cancels the response.** In the moment between Ktor
  cancelling the response and the compaction detaching, a frame the compaction wrote ended it with no terminal, nothing
  was held, and the retry paid for a second upstream compaction. That write now counts as the client leaving.
- **The chat dialect fails an unknown finish reason honestly.** A `finish_reason` splice did not know
  (`insufficient_system_resource`, `abort`, `error`) read as a clean end of turn. It now fails the turn as the
  provider's own unrecognized failure. A finished empty answer ends clean instead of being re-sent as an overloaded
  `empty_model`, the translator stops reading at the backend's last word instead of waiting for the connection to
  close, a stream torn in the middle of a tool call's arguments reads as torn, and a new call that reuses an open
  index gets its own tool block.
- **A passthrough answer cut at its cap ends as `max_tokens`.** A tool call whose arguments were cut with the output
  was ended as a tool use. The upstream's `max_tokens` now outranks the tool block, a matched stop sequence is kept
  where it became `end_turn`, a server tool's input and a citation fold into their blocks, and `message_start` reports
  the backend's real opening usage.
- **A provider's quirk defaults and a large body cap are kept.** `store`, `cache_key`, `summary_field` and `tool_choice`
  now overlay the provider's profile only when set, so Grok keeps its session-id cache key and Grok and Muse keep their
  `tool_choice`. A `maxRequestBytes` past what a 32-bit count holds no longer wraps to a cap that answers every request
  413. Words boot cannot read in `[daemon]` for `show_reasoning`, `summary` and `effort` are findings at their line, an
  empty `SPLICE_CONFIG` or `XDG_CONFIG_HOME` is unset as the launch shim reads it, and a save of the topology is
  refused when the file no longer holds what the edit was built from, so a concurrent save is not patched back out.
- **The turns audit's findings are closed.** A round of only whitespace now reaches the empty-answer handling instead of
  counting as text. The loop guard's "same error" is the same error text, and any success in between ends a run, so an
  edit-and-rerun loop is not told to stop. A compaction whose upstream tears after its client left is re-anchored and
  recorded as a failure, not as abandoned. A raise of `maxInflight` admits parked waiters at the next sample, and a
  resumed held turn's cap does not run while it queues for its permit. A synthetic tool-call id carries a per-round
  nonce, so a call without an id no longer shares one across turns and has its pair dropped.
- **Telemetry tells a lost row from a pending one, and keeps an unreadable file.** A lost perf row is worded as lost,
  with its time, only in the windows that can hold it, and doctor's tails say the same. An unreadable, corrupt or
  oversize hourly-spend file is set aside as `.unreadable-<ms>` before the first write instead of being replaced, and a
  compaction-statistics append that fails is logged once per streak and counted.
- **Client delivery no longer waits for trace redaction.** A turn's outgoing frames are buffered under their own
  monitor, so a slow request or response capture holds nothing the client is waiting for.
- **A plain-text document counts by its text in the token estimate.** `count_tokens` charged every document the flat
  media cost, so a 200 KB text document read as 1,600 tokens. Only base64 media takes the flat cost.
- **A numbered list in Claude's last reply is no longer taken for a permission menu.** The console read any run of
  numbered lines on the captured screen as a live menu, refused to send what you typed, and drew buttons that typed a
  digit into an idle prompt. A numbered run with the input box below it is now a list you read, and the composer sends.
- **A `teams.json` that does not parse is said, not shown as no teams.** The team list and a member's Start answered
  as if no team existed. They now name the file and say to fix or move it, and the daemon log says so once for each
  change to the file.
- **Start on a team member that exited cleanly starts a fresh session.** The first press after a clean exit answered
  "did not start" and unbound the slot, and a second press was needed. It now starts the member at once.
- **Two sessions starting together in one folder no longer lose each other's entry in the head's ownership index,**
  so a later bare `-c` on that head cannot resume another member's session.
- **A Continue that is refused before the old client ends leaves nothing behind.** Its folder-trust grant and pending
  mark are closed, and with `state_dir` set they are kept under the daemon's own state folder.
- **A malformed `Content-Length` no longer drops every MCP stream or leaves a request unanswered.** The MCP hub's
  front exited on `Content-Length: -1` before it checked the key, so any local process could end every session's MCP
  streams; it now closes that connection and nothing else. It also answers a request with no key `401` before reading its
  body, closes a connection that sends no request for two minutes, and holds at most 256. On the daemon's own port, a
  `Content-Length` that is not a number is now answered `400`; it was never answered.
- **A Claude account label the pool cannot take is refused at sign-in.** The sign-in accepted capital letters and
  labels up to 64 characters, but the pool takes lowercase letters, digits, `.`, `-` and `_`, up to 48. A login
  filed as `Work` landed a folder the pool then threw on, and every request on that head failed until the folder
  was removed by hand. The sign-in now refuses the label with the rule in words. A folder like that already on disk
  is ignored, and named once in the daemon log.
- **Removing or renaming an OAuth account checks the label it is given.** `DELETE` and `PATCH` on
  `/api/auth/<head>/accounts/<label>` refuse a label that is not a valid account label. A label such as `../x` could
  reach a `.json` file outside the head's account pool.
- **An `http://` upstream with no port is checked on port 80.** The silent-stream check dialed 443, was refused, and
  ended a model that was still thinking on a live connection.
- **The device sign-in shows the provider's error code, not its reply.** A refused start, a server error or a
  declined code prints the HTTP status and the OAuth error code, as the browser sign-in already did. A sign-in link
  opens in the browser only when it is an `http` or `https` link.
- **A restart no longer wipes the session totals of a head that was idle.** The status line's per-session figures are
  kept in a file that is written at every stop. A head that had served no turn since it started wrote that file empty,
  so each restart, an auto-update's included, erased the saved totals of every idle head. The file is now read before
  it is written.
- **A tool the client just loaded no longer reads as a failed load.** When Claude Code defers tools and the model asks
  for one, the answer holds a `tool_reference` block. The chat dialect and the Responses heads without a tool surface
  sent the model "tool_reference block omitted ... unsupported on this backend" in its place, and a model that read it
  as a failure stopped calling any tool but Bash. They now send "[tool loaded: SendMessage. It can be called directly.]",
  naming the tool from the block's own `tool_name` and only when that is a plain tool name. Heads with a tool surface
  (Codex, Meta) keep the old text, and any other unknown block keeps its omission text. A resumed session that holds a
  `tool_reference` pays one cold prompt cache on its next turn, because the bytes of that old history changed once.
- **The Codex quota file says whose quota it is.** `codex-quota.json` held whichever account the head's sign-in named
  at its last reading, with nothing naming it, so a tool reading it could not tell one ChatGPT account from another.
  Each reading now carries `"account": {"ref": ..., "label": ...}`, with the same ref the Accounts page shows, and each
  account keeps its own `codex-quota-<ref>.json`, which signing in to another account no longer overwrites. The file
  keeps its windows across the upgrade, and gains the account at its next reading.
- **`install.sh` says when `gh` did not answer.** A `gh auth status` that took over 20 seconds read as "gh is not
  signed in"; it now reads "gh did not answer gh auth status within 20 s".
- **A daemon that runs out of memory exits** and leaves a heap dump, `splice-oom.hprof` in the state folder, instead
  of running on half alive.
- **A stop that the halt watchdog had to cut exits with status 70,** not 0, so systemd can tell it from a clean stop.
- **A planned restart now finishes as a clean stop.** Every planned restart on Oct 19
  ended in status 70 at 57 s: after the then-45 s drain, each head's server and then the control server waited up to 5 s more
  for their open requests, a wait that ignored the stop's own limits. Those waits now take the stop's limits, 2 s for
  a head and 0.5 s for the control server, and the log says when the heads drained, closed and the control server
  closed.
- **A stop during startup stays a stop.** A stop signal ends the startup at once, where 0.3.x ran the start to the end
  first. A head that had not started stays stopped, and a stop before the control port binds cancels the start.
- **`splice doctor` no longer creates a missing state or logs folder;** on a fresh home it says the folder is not
  created yet. An empty current state folder no longer wins over a pre-0.4 folder that holds the management key, and
  doctor warns about the folder it passed over. With `[daemon] state_dir` set, restart, doctor, logs, trace and perf
  find the key and state files there. Doctor's fix for a missing API key is `splice key set <VAR>` instead of an
  `export` line.
- **A launch carries the folder-trust records you already granted** for its folder, so `claudex` after
  `claude-splice` in one folder shows no folder-trust check.
- **`splice key set` and `unset` report a refused write** to `keys.toml` in one line and fail, instead of a stack trace.
- **A logs folder that cannot be created is reported as not created,** with no `daemon.log` kept, and a filesystem
  without POSIX modes no longer stops the logger from starting.
- **Doctor reads a slow daemon as running but slow to answer,** a warning, not as stopped; something else on the
  control port is a warning with an `lsof` fix. Times read as a person reads them and round up, so a hold never reads
  sooner than it is. A missing launcher reads "splice's launcher is missing". A bin folder that cannot be listed is a
  warning instead of "no leftover commands", and a leftover command link to the launcher that no head claims is a
  warning naming the `rm` that clears it. The Claude Code row reads the `claude` on the same PATH as the prerequisites
  row.
- **A source that finished is no longer counted as cut.** A client step that failed just after the upstream delivered a
  code-mode round's terminal added a cut to that request's row, though the source was whole and its tokens were
  billed. The cut count now rises only when a source is cut before its terminal arrives.
- **A restart no longer cuts the last chunk of a finished turn.** A turn's gate slot is released while its response
  is still being written, and the drain stopped the head once the gate was empty, so the client could read a chunked
  body that never ended. The drain now waits for every call to leave its handler too, within the same two-second budget.
- **The account pool orders by the weekly reset, as ruled.** Sessions move to the account with the soonest weekly
  reset, and the five-hour reset breaks ties. It ranked by the soonest of either window, so a sooner five-hour reset
  beat a later weekly one.
- **Compaction is recognised by its marker only.** A turn is a compaction when it carries the summarizer's verbatim
  marker; prose that merely mentions compaction is an ordinary turn.
- **A cross-head resume lands in the project the person is standing in.** It prefers the copy under the launch's own
  working directory before any other head's tree.
- **A refusal or a content filter after the answer began ends in words.** Claude Code never retries once content has
  reached it, so an error frame there read as a lost connection. Every dialect now appends the verdict to the answer, as
  a stall after content already does.
- **An unknown vendor error code is tried once more, a known one never.** A stream error with no HTTP status and a
  vendor code on neither list is sent again once, then its message is shown. A code on the deterministic list, such
  as `invalid_parameter`, fails at once with its message, and a code on the retryable list follows the normal retry
  curve. Only the Responses and Chat dialects re-send. A vendor's own code is read before its type, and only a round
  that showed the client nothing, a tool call included, is re-sent.
- **A standing prompt the head could not build refuses boot.** Both keys set, a prompt file that cannot be read, a
  relative project root or a strip pattern that does not compile is a config finding naming its table, listed with every
  other finding, so splice does not start rather than skipping the head. `splice doctor` names an unreadable project
  prompt file before a restart, and `splice check-config` names an unreadable head prompt file.
- **A `[compaction]` table splice cannot build refuses boot with the other findings.** Both `instructions` and
  `file` set, a blank or repeated model, or a repeated project stopped the daemon on its own before, so it was never
  listed beside the file's other findings and the fix session was never offered. A `file =` boot cannot read now
  refuses boot too, instead of starting with that rule off. A file that goes unreadable while the daemon runs still
  only turns its rule off.
- **A day file that rolls twice in one UTC day keeps its first roll.** A day file past 512 MiB rolls to `.1`, and a
  second roll that day replaced the `.1`, so the day's oldest rows were deleted without a word. The old `.1` now
  moves to the next free number first: `.2` is the day's first roll, `.3` its second, and `.1` always the newest.
  Reads, counts, purges, the trace body pack and the 16 GiB index cap take in every generation of a day.
- **Claude Code's own files say when splice could not read them.** A home folder splice cannot list now logs one
  line naming it, once until the daemon restarts, instead of every Claude Code identity under it vanishing from the
  MCP and plugin readers. A head's `settings.json` that does not parse logs one line naming the file before splice
  rebuilds it, since the rebuild drops the model saved there.
- **A resume hook whose move fails says why.** "session could not be moved" now carries the failure's class, a
  filesystem failure's reason without its path, and the line in splice that threw. The message itself stays
  withheld, since this route reads transcripts.
- **A resume word that is not a session id is no longer warned away.** Claude Code resumes a session by its name, or
  opens its picker on a search word, but splice warned any word in no transcript tree as an absent id Claude Code would
  refuse. The warning now stands only for a UUID, the shape of every session id.
- **A Bash activity label names the command, never a shell keyword or a flag.** `for k in …; do …` read
  "Running for k" and a continued line read "Running -g". A `for`, `select` or `case` header is skipped, the keywords
  that lead into a command are dropped, a backslash continuation joins its line, and a flag never heads the label.
- **The client-scaled tokens line is logged once per factor, not once per turn.** It filled about 5% of the journal.
  A row says its factor once, and again only when its factor, window or window source changes.
- **`splice add` checks what it says it checks.** The live turn runs the first model the person chose, so a head
  whose model is wrong fails the check instead of passing on the default; an OAuth credential counts as usable when it
  is unexpired or refreshable, not merely present; and an openai-responses head's model list is read with the one URL `splice models`
  asks.
- **Moving a session is safer.** A move runs the wrapper by the path splice checked, never sends words with
  `/exit`, resumes only once the old client has truly gone, goes on among the terminals the session ran in rather than
  the person's default tmux, and works on an Ended card.
- **Sessions no longer reads "API error: API Error:".** An API error Claude Code already words as one is served as
  it wrote it.
- **Every registered command can show help without running.** `splice <verb> --help` and
  `splice <verb> -h` print that verb's usage and exit successfully without starting the command.
- **An explicitly reported cached-token count of zero stays a measurement.** It is not discarded
  as missing usage.
- **Permanently rejected completed native input stays retired.** Code mode stops revalidating
  it on every round, including after restart. Missing history and unfinished input are not
  retired merely for being incomplete.
- **Code-mode continuation rounds keep the request prefix already sent.** Within a turn, later
  results append without rewriting the processed prefix, so its bytes and prompt-cache key stay
  unchanged.
- **Exhausted requests record the exact earliest known account reset.** The saved value stays
  separate from the shorter retry time sent to the client. No reset is invented when none was
  reported.
- **A retry is counted only when another upstream request starts.** A planned retry stopped by a
  deadline, a missing credential or an account hold before the next request starts is not counted.
  Performance records retain transport attempts that began before fallback, even without a
  response event. A request found ineligible for WebSocket transport does not count as a
  WebSocket start.
- **Code mode checks saved history in its captured order.** Later script replies are not treated
  as earlier input, and a shortened captured tail still has its order checked. Saved replies can
  remain available when their earlier input cannot be replayed, if the other history still matches.
  Matching reuses unchanged content instead of comparing large tool replies repeatedly.
  Performance records measure history rewriting and waits for another turn in the same
  conversation, including cancelled waits.
- **A launch can recover an interrupted hook-settings update after local edits.** Recovery keeps
  those edits when the remaining saved hook entries still identify the interrupted update.
- **A failure reading or saving Claude Code's recorded context window names its cause.** Invalid
  JSON is reported without copying its contents into the log.
- **Incomplete code-mode history is not treated as proof of an edit.** An occurrence-count
  mismatch in partly retained history, or an order conflict with missing saved ancestors, names
  a placement problem instead of asserting that the client edited its history. Missing native
  history and conflicts with a captured position have distinct reasons.
- **Interrupted code-mode history keeps the admitted exec prefix.** An unfinished streamed call
  replays the source prefix splice actually admitted, together with completed-call evidence,
  instead of an empty or uncertified raw input. The retained raw item is unchanged; replaying
  history never reruns the JavaScript. The daemon logs the structural interruption cause,
  including failure class, permanence, provider attribution or incomplete terminal shape,
  without copying script source or upstream message text into that diagnostic. History warnings
  include a short session tag and available native replay rejection details, without logging the
  native payload.
- **Code-mode turns chain on the WebSocket again.** On a code-mode turn every GPT tool call is `exec`, a custom tool,
  and the chaining check knew only function calls, so each round after an `exec` re-sent the whole
  conversation instead of the script's output (103 of 6,877 WebSocket rounds chained). A round now
  sends only what is new: the `exec` output and any context note after it. Measured on `claudex`
  after the fix: 55 of 58 rounds chained, none closed for size.
- **A GPT session on a ChatGPT sign-in compacts while there is still room for the compaction.** Claude Code decides
  when to compact from the token counts splice reports, at a share of the window it is told. On a
  head signed in with ChatGPT that point left too little of the model's real window for the compaction itself: the
  conversation's growth since the last count, the compaction prompt and the summary it writes.
  splice now scales the counts it reports so that Claude Code compacts at the model's window less a
  reserve measured from 1,420 successful GPT compactions: enough for how much the conversation grows
  through the compaction prompt in 95 of 100 compactions, plus what the model generates for a
  compaction, reasoning included, in 99 of 100. A model with too few compactions of its own takes the
  missing figures from its family; gpt-5.5, which has none, uses the GPT-5 family's typical growth and
  its 99-in-100 generation. Only the eight models splice has figures for get this reserve (gpt-5.5,
  gpt-5.6-luna, gpt-5.6-sol, gpt-5.6-terra, gpt-6-astra, gpt-6-luna, gpt-6-sol and gpt-6.1-sol); any
  other model, including one the backend adds later, keeps its old scaling. A row's
  `compaction_reserve_tokens` overrides the reserve on any row.
  When one turn still grows past that point, splice answers `prompt is too long` with HTTP 400
  before the answer starts, which is the reply Claude Code compacts on, instead of a failure inside
  an answer already under way. splice refuses only when the provider measured the conversation's
  earlier part and everything added since is text or text tool results, counting that new text at a
  safe upper estimate. A turn that adds the model's own tool calls or reasoning goes to the provider
  as before. A conversation splice has not measured since it started, one whose earlier part changed, or one
  whose new part carries an image or file goes to the provider as before. A compaction or a first
  message is refused only when its measured size alone exceeds the window.
- **`splice status` no longer calls a head ready when the daemon could not build it.** The table
  judged each row by its credential and its wrapper alone, so a head skipped at boot read "ready"
  while `/health` counted it failed. The row now reads "not running" with the daemon's own boot
  reason, and `/health` names each failed head and its reason beside the count.
- **`splice status` no longer calls a head ready when it cannot answer a turn.** A head whose
  provider refuses every turn until a reset (a spent weekly plan) reads `out of quota until` that
  reset, in the machine's own time zone, and `/health` and the usage view carry the same instant.
  splice learns it from the refusal and keeps it across a restart. A full quota reading guides
  account preference but never blocks a turn by itself. A saved native refusal stays scoped to the
  login that received it and can still answer its first request after restart without contacting
  the provider. A local head whose runtime does not answer reads `runtime not answering on :<port>`,
  and Settings under Health reports it down.
- **`splice doctor` gives the daemon's own fix for a splice.toml value of the wrong type.** It
  pointed at a file inside splice's source tree and suggested deleting the config; it now names the
  key, the line and the expected type, and says what to write, in the words the daemon prints when
  it refuses to start.
- **A code-mode turn that cannot save its state says why.** When the disk holding splice's state
  filled, every code-mode turn failed as "code-mode state persistence failed", which read as a
  code-mode bug. The message now says the disk is full. A file error names its path and reason, and any other save
  failure is withheld, since its text can quote file bytes.
  As before, the code is never run a second time.
- **A turn's cache write reaches Claude Code as a cache write.** splice reported the tokens an
  upstream wrote to its prompt cache as plain input and `cache_creation_input_tokens` as 0, so a
  session's transcript showed a cache that never wrote, and Claude Code priced each write as
  uncached input. The cache itself was never affected: in a mock-cache comparison of 0.3.2 and this
  release, turns 2–4 read 99.7% of their prompt from cache. The write now arrives
  in its own field. The context total Claude Code compacts on is unchanged, and heads whose upstream
  reports no cache write send the same numbers as before.
- **A sign-in no longer prints SLF4J warnings.** The jar carried slf4j-api (Ktor brings it in) and
  no provider, so the first log call printed three `SLF4J(W): No SLF4J providers were found` lines
  on the user's terminal, during every `splice add` sign-in. The no-op provider now ships beside it,
  and a test fails if the fat jar loses it again.
- **A sign-in finished in an old tab says so.** A browser sign-in completed in a tab from an earlier
  attempt carries that attempt's state, and splice rightly ignores it, but it used to do so without a
  word: the sign-in sat silent until the 300-second timeout. The tab's page now says the sign-in
  came from an earlier attempt and to finish it in the newest tab.
- **The prompt after a browser sign-in takes the first answer typed.** While a browser sign-in waits,
  splice also reads the terminal, for a redirect URL pasted by someone whose browser cannot reach the
  machine. That reader stayed parked on the terminal after the browser won, so it took the next line
  typed and threw it away: the live-turn question after `splice add codex` needed its answer typed
  twice. The reader now reads only what is already typed while the sign-in waits, and stops before
  the next prompt asks.
- **`splice add` says why it could not link a wrapper.** When the link failed, the add printed
  `not linked (failure (message withheld: it may quote file bytes))` instead of the reason, such as
  `launch shim not found at … (run install.sh)`. The add now prints the
  linker's own sentence, as `splice doctor`'s fix already did; any other failure is still withheld.
- **`splice add`'s passing checks say what the pass means.** A base URL that answered printed its
  status beside the green tick, so a codex add read `✓ base url HTTP 403 from …`; it now reads
  `reachable at …`. A provider that publishes no model list printed
  `no model list on OPENAI_RESPONSES; 4 row(s) trusted`; it now reads
  `4 models from splice's catalog; this provider publishes no list to check them against`.
- **`splice models` says why it could not ask for a list.** A provider whose list did not arrive
  printed `could not be asked: failure (message withheld: it may quote file bytes)`. A list that does
  not arrive within the 10-second budget, a TLS failure and a URL that does not parse now say so;
  any other failure is still withheld.
- **A team's message list no longer counts a member's calls to its own subagents.** A member's own subagent is
  addressed by name (`code-review`) and answers as `main`, and the teams API listed those calls as team
  messages: a team of four whose reviewer ran `/code-review` read 16 messages for 13 hand-offs. A
  call to a name that is neither a member nor a session address is now the member's own tool work.
- **A team's activity holds recorded samples.** Claude Code 2.1.282 sends its periodic
  activity query only from background agents, so a team whose members
  worked in their own sessions had no samples. splice now samples each session's
  latest tool call from its own turns, at most once every 30 seconds, and the client's answer to its
  own query counts as the sample when one comes.
- **Unreported usage stays visible in totals.** A turn whose provider never reported usage is
  counted as unreported, not as a measured zero. Budgets and the status line keep the reported
  spend of an incomplete turn as a lower bound. Usage hours and team totals keep its tokens and
  count the turn as unpriced. The status line
  marks its cost estimate `≥`. Incomplete reports keep the amounts that did arrive. A request
  splice refused without sending it contributes exact zero cost, even without a model rate card.
  Known priced amounts stay visible when other turns have no price.
  Wholly unknown costs stay unknown. The projects API no longer returns cost fields.
- **The claude profile prices every model it lists.** Only Opus 5.5 carried a rate card, so a turn on
  Fable 5.1, Sonnet 5 or Haiku 4.5, Claude Code's own background Haiku calls among them, counted as
  unpriced. Each now carries its card from Anthropic's pricing page, with cache writes at the 1-hour
  rate as for Opus 5.5.
- **New OpenRouter heads offer the discovered roster, not just their tier models.** `splice add
  openrouter` now writes `model_slots`, a tier-to-model map separate from the `models` serving
  allowlist. Its tiers are each family's latest as OpenRouter lists it: opus is Claude Opus 5.5, sonnet
  Claude Sonnet 5, haiku GLM-5.3 Flash and fable GPT-6 Sol, while every admitted
  discovered model keeps its own window and price. Existing explicit allowlists remain restrictive.
  A tier whose model is not listed stays unmapped, and the boot log names it. Slots inside `models`
  and a separate `model_slots` map cannot be combined. This also changes existing heads: when all
  declared tiers are missing from the roster, splice reports them instead of substituting the pinned
  model through positional fallback.
- **`splice add openrouter` prices its default models.** The ten models it writes carried no rate card,
  so every turn on one of them read `no rate card` on the status line, on the one route
  where you pay per token. Each now carries OpenRouter's own published card, read from its models
  listing on Sep 29, and GPT-6 Sol and Luna carry their over-272,000-token tier. `splice add-model`
  writes its rows with the card too. A head added before this keeps its rows as written, and rows without a rate card gain the price from OpenRouter's list.
- **A model you add by id is priced from OpenRouter's own list.** Only the ten models `splice add
  openrouter` writes carried a rate card, so a turn on any other OpenRouter model read `no rate card`
  on the status line and counted as unpriced against a budget. splice now reads the price OpenRouter
  lists for each model when the daemon starts and every hour after, keeps it with the model list it saves for the next
  start, and gives it to every model whose row carries no card of its own, the models the daemon
  discovers among them. A `rates` line you write on the row wins, and a model OpenRouter lists no price
  for, such as its `auto` router, whose price depends on the route it picks, still reads `no rate card`.
- **Provider model rosters refresh hourly while the daemon runs.** Each head's provider was
  asked for its models only at daemon start. splice now asks again each hour. Models admitted by
  the provider's discovery filters reach the head's `/v1/models`, newly launched pickers, the
  status line and team economics without a restart, subject to each head's `models` allowlist. Failed refreshes keep the last list and are
  logged. Successful answers are also saved for the next start; a save failure is logged without
  discarding the live answer.
- **Backend code-mode eligibility no longer needs a copied model list.** With code mode enabled,
  models the ChatGPT backend marks `code_mode_only` get `exec` without a manual model-list edit.
  splice reads each model's `tool_mode` at start and on hourly roster refreshes, saves it for the
  next start, and checks the current roster on every turn. The built-in list is gone;
  `code_mode_models` adds to what the backend marks, including models absent from its list.
  With no list known at start (no answer and none kept), only explicit `code_mode_models` entries
  run code mode, and the head's log says so once. `code_mode = false` still disables the runner.
- **`splice restart` no longer waits out systemd's restart delay.** Where the daemon runs under its
  systemd unit, `splice restart` stopped the daemon itself and then asked systemd to start the unit
  while it was still shutting down, so the start did nothing and the daemon came back only after the
  unit's automatic-restart delay, with every head down meanwhile. On a unit whose restart delay grows with each restart, the sixth restart took
  46 seconds, and the delay grows toward five minutes. It now asks systemd to restart the unit, which drains in-flight turns
  the same way, starts the new daemon at once and resets the delay. A daemon the unit isn't running
  is stopped and started as before.
- **Resuming a session on another head can no longer cut its transcript short.** To resume a
  session on a head that serves another model, splice moves the transcript's assistant rows onto that
  head's model. A session chosen in the `-r` picker is moved in your own Claude Code transcript (a named `-r` goes on
  as a copy, below). It rewrote the file in place, so a crash or a full
  disk in the middle of the write left the transcript truncated. The new transcript is now written
  beside the old one and moved over it in one atomic step, so an interrupted write leaves the original
  as it was.
- **splice's log no longer keeps the prompt you type on a command line.** Each launch wrote the whole
  `claude` command line to `daemon.log`, so a prompt given with `-p` or as a plain word, and the text
  given to `--system-prompt`, `--append-system-prompt`, `--agents` or `--cloud`, stayed in the log.
  The launch line now keeps recognized flags and writes each prompt as `<N chars withheld>`. A word splice
  cannot place, such as one after a flag it does not know or after `--`, is withheld too.
- **A team's message list files a message under the session it went to.** A message sent to a member by name,
  such as `gpt`, was matched to a session each time the team was read, so it belonged to whoever
  held the name at that moment: a new team's message list showed a message an earlier team had sent to its own
  `gpt`. splice now records the session that held the name when the message was sent. A message to a
  name that no session held then, or that two sessions held, belongs to no one.
- **splice keeps less on your disk, and the README lists what it keeps.** `daemon-boot.log` copied
  every line of `daemon.log` and grew without limit; it now holds what the daemon writes while it boots, a startup
  error included, and nothing after boot. When the daemon does not come up, the terminal prints that file's last lines
  under "daemon did not come up", or says it is empty and names `daemon.log`.
  A head's kept files go when their promise ends: an expired
  compaction answer, a head's code-mode state once code mode is off or the head is removed, and its
  trace files once trace is off are deleted when the daemon starts. Activity day files, which carry file
  names, commands and search patterns, are kept for today and yesterday (UTC) instead of 90 days, the
  two UTC days a local day can span. Copies of labels in `daemon.log`
  follow that log's size-based rotation instead. The README's new section, What
  splice keeps on your disk, names each file splice writes, what it holds, who can read it and how
  long it stays, and a test fails the build when the code writes a file the section doesn't name.
- **A bool setting splice can't read is named, not read as off.** A `trace` value such as `enabled`
  read as off, so the head ran untraced and its next start deleted its trace history. A bool setting
  that isn't true/false, yes/no, on/off or 1/0 is now ignored, keeping its default, and `splice
  doctor` names it; a head whose `trace` value can't be read keeps its trace days. `splice trace
  --purge` and a start's clean-up name any file they could not delete instead of counting it as
  deleted, a day file with one damaged character loses only that line, a file named for an
  impossible date stops no clean-up, and a midnight clean-up the computer slept through runs within
  10 minutes of waking.
- **A request the upstream stops taking is cut and retried, not left hanging.** When the network
  dropped or the upstream stopped reading partway through a request, the unsent bytes sat in the
  kernel and the turn hung until the 15-minute socket timeout. splice now watches an HTTP request while it still has unsent bytes, before
  its reply starts: if the upstream acknowledges none of the remaining bytes for
  `firstByteTimeoutMs`, splice cuts it with "<host> stopped taking the request for Ns: the write
  stalled" and retries on a new connection within the ordinary retry budget, never using the stalled pooled connection. This works the same over TLS,
  where a stalled write used to hold up every other head's write timeouts too. A slow link that
  keeps taking bytes is never cut. On macOS, where the kernel's send queue can't be read, only a
  write the kernel has accepted nothing of is cut.
- **A code-mode record goes 24 hours after its last use, even on a head that is never used again.**
  Code mode's records keep the model's reasoning summaries in plaintext. They were swept only when
  the daemon started or another code-mode turn came, and closing an idle script or stopping the head
  restarted their 24 hours, so a head that ran one script and went idle kept it for as long as the
  daemon stayed up. splice now checks every 5 minutes while it keeps any record, and neither closing
  the script nor stopping the head counts as a use. The build check on the README's list of files
  now also fails when a file the list names gains a write the check has not recorded.
- **A launch no longer puts back a Claude login that was already refreshed away.** Every launch of
  `claude-splice` copied the selected saved login over the head's live one. Claude Code's refresh
  tokens are single-use, so the copy was stale after the first refresh, and signing in with it could
  get the login revoked. A launch now never writes the head's credential file. `splice login
  claude-splice --label <name>` is the only way a saved login goes back, under four rules: the login
  it replaces is saved first under its own label, nothing changes while a session of the head runs
  or while splice cannot tell, a login is filed only under the label of its own account (recorded by
  id and email from the head's `.claude.json`, never a token), and anything splice cannot read stops
  the switch with the head untouched. A copy that may have been refreshed away is never put back
  until a live login re-saves it: one whose login left the head unsaved, through a `/login` to
  another account or a `/logout`, and one saved before splice recorded accounts. `--discard` lets a
  switch drop a login saved under no label.
- **`GET /api/heads` reports the turns a head has in flight.** It reported every gate's
  `acquired`, `released`, `waited`, `avg_wait_ms` and `stream_idle_ms` as 0 and its `live` list as
  empty, whatever was running. The gate now measures them: one live row per turn it holds (the
  session's short tag and the model, its `compact` flag, and `connect` until the upstream answers,
  then `streaming`, with its age and idle time), its counts since the head started, the mean queue
  wait, all read in one snapshot, beside the head's stream-idle limit.
- **A cancelled code-mode start waits for its worker to release its slot.** A cell
  started the moment another's startup was cancelled could fail with `Code-mode worker capacity
  reached`: the slot came back only when the JDK's process reaper got round to the killed worker.
  The cancelled start now kills its worker off the caller's thread, and the slot is released
  when the worker's exit is seen.
- **Code-mode host startup has its own budget.** Starting an on-demand host and opening the
  session's engine have a 30 s startup budget. `code_mode_timeout_ms` remains an accepted positive
  compatibility setting, not a per-script deadline.
- **An install that cannot link says so in one line.** `splice install` and `splice setup` stopped
  with a JVM stack trace when the launch shim was missing, dangling or unreadable, when two heads
  shared a wrapper command, or when a command name held a real file. Each now prints the one
  sentence it already carried (`splice: launch shim not found at … (run install.sh)`) and exits 1.
  A failed setup step (the install, a rig call) now ends its line with a red ✗ rather than the ✓
  a finished step shows.
- **Budgets warn and block.** `PUT /api/budgets` stored a head's daily budget and
  nothing on the turn path read it, so a `block` head kept serving turns. Each head now weighs its
  turns against its budget per day of the operator's local midnight, priced from the model's rate card.
  Earlier spend loads in the background after restart, so admission can
  miss it until that read finishes. `block` refuses a turn once the available tally reaches the
  limit: a 403 `permission_error` naming the head, the spend,
  the limit and the reset at local midnight, with an `error:budget-blocked` perf row. `warn` tells the
  operator once per day and per limit through the saved webhook (the test send's `{"text"}` body)
  and a `[head][budget]` log line, and serves the turn. A turn on a model with no rate card is not
  counted and is named in the log. The `desktop` alert flag still delivers nothing: the daemon has
  no desktop.
- **A wrapped `claude` launches.** Wrap symlinks `claude` to the launch shim, the
  shim asks for the head named `claude`, and no head carries that name, so every wrapped `claude`
  got a 404 and exited until unwrap. While the wrap is in place `claude` launches the splice-owned
  Claude head over the vanilla `~/.claude`, with the real binary the wrap recorded as `argv[0]`,
  and never writes a stored splice login over the operator's own.
- **`splice doctor --json` reports the state dir.** The report now carries
  `state_dir_usage`: size, file count, the oldest file (redacted, relative to the state dir) and
  its age, and the entries it could not read. Symlinks are not followed.
- **Any head joins any session again.** A session started on one head resumed on any
  other because every head's `projects` tree was the shared `~/.claude/projects`. The
  2026-09-17 head-isolation change read the ruling that head configuration must never leak as
  covering transcripts too, gave every head a private tree, and left cross-head resume only as an
  explicit `-r SESSION_ID` copy, while the operator's `splice.toml` still asked for `projects` in
  its share list. Transcripts are shared session state, not head configuration: a head whose
  policy shares `projects` has its private tree merged into the vanilla one (the same files, never
  copies, so a live session keeps appending) and linked, so the `-r` picker on every head lists
  every session again. A head that isolates `projects` keeps a private tree and reaches a foreign
  session by name, as before.
- **A session resumed from another head no longer fails every turn on its old thinking.**
  Resuming on `claude-splice` a session written on `claude-kimi` moved its rows onto Fable and kept
  their thinking blocks, which carry splice's stand-in signature (Kimi signs nothing). Claude Code
  sent them back, Anthropic answered every turn with `Invalid signature in thinking block`, and each
  retry sent the same history. Claude Code strips thinking and retries on that error only when it
  arrives as an HTTP 400, and a head's stream has already answered 200 by then. The rows that move
  to another head's model now leave their `thinking` and `redacted_thinking` blocks behind, in the
  session and in its subagent transcripts. A row that held only thinking keeps its place with the
  same `[Thinking removed]` text Claude Code writes when it strips a message bare itself. Rows on a
  model the head serves are untouched.
- **A resumed session follows the resuming head's model.** A transcript carries the model
  id of the head that wrote it, and Claude Code refuses to restore a model the head does not serve
  ("Session model X could not be restored"). Splice now moves the transcript's assistant rows that
  sit on a model the resuming head does not serve onto its model; a row on a model the head serves is left
  byte-identical. At launch, for `-r SESSION_ID` and for `-c` (which resumes this head's own newest session in that
  folder), a session with rows to move goes on as a copy beside it under a new session id: its rows, its subagent
  files included, are retagged to that id, only the copy's models move, the client is handed `-r <new id>`, and the
  original stays as its head wrote it, so another head resuming it sees it unchanged. A session with nothing to move
  resumes in place, untouched, and a copy that fails is removed and the launch refused with the cause. Continue on
  follows the copy and opens it. A session chosen in the `-r` picker is moved where it lies, since the client has
  picked it before splice hears of it. The `-r` picker uses a SessionStart hook in each
  head's `settings.json` to tell the daemon which session was chosen; wrapped `claude` does not carry that hook. The hook authenticates with the
  turn key from `<state>/turn-auth-header`, never from the session's environment, may only touch
  transcripts under that head's tree, and never blocks a session: every refusal answers 200 and
  lands one line in the daemon log.
- **A third system prompt mode, `strip`, edits the client's system field in place.**
  A head or a project layer can now delete paragraphs from the client's own system field instead
  of replacing it: the layer's text is a pattern list (one regex per line, `#` comments), and
  every blank-line-separated paragraph of the client's system text that a pattern matches is
  removed at the wire, on every dialect. The other paragraphs, block order and `cache_control`
  breakpoints ride through byte-identical, so the prompt cache still warms
  from turn two, which `replace` could not offer. A pattern that is not a regex is a config error
  at load. Nothing is stripped unless an operator writes a strip layer, splice ships no pattern
  list, and `splice doctor` shows an information row for every strip layer, as it does for
  replace, because it changes the instructions Claude Code sends.
- **Every head records its full request/response trace by default.**
  `[heads.KEY.overrides] trace = "false"` turns it off for that head after a restart. The files are
  `<state>/trace/KEY-YYYY-MM-DD.jsonl`, recording every request it receives (method, path, headers, exact
  body), every upstream attempt as it actually left (the body byte for byte, the headers with every
  credential-class value redacted, the status and headers that came back or the transport failure
  that ended it, the raw response text), every frame it streamed back, the outcome, the round and
  attempt counts and the perf marks. The trace sits INSIDE the retry loop, so a backoff retry, a
  refresh's free retry, a torn-stream reissue and an amended resend are each their own record; a
  WebSocket round is one too. `splice trace <head>` reads the files with no daemon (a table per
  turn; `--turn ID` for one turn in full; `--session`, `--last`, `--json`; `--purge` deletes them
  and says what went). The directory is owner-only, whole day files older than
  `traceRetentionDays` are deleted once you set it, shorter or longer than your history window (left unset,
  trace follows `historyRetentionDays`), and a body past `traceMaxBodyChars` is cut and flagged.
  `PUT /api/heads/{head}/capture` also turns a head's trace off, from the next request. Turning it off
  stops new writes, and the next daemon start deletes that head's kept days; kept data can also be deleted separately.
  `splice doctor` names heads whose trace is off.
  To opt out, use the head's existing key in place of `example`, then restart:

  ```toml
  [heads.example.overrides]
  trace = "false"
  ```
- **See what splice sent upstream, once you ask it to keep it.** A proxy that cannot show
  the request it sent cannot be audited, and nothing kept one: the perf row records how many bytes
  went upstream, never which. `[heads.KEY.overrides] wireTap = "N"` now makes that head keep its last
  N upstream request bodies, in memory only, and `splice wire <head>` prints them exactly as they
  left, for every round: a folded or re-anchored turn is several requests. It is off by default and
  opt-in on purpose: a body carries the whole conversation it was sent for, so no head keeps one
  unless its operator named a count, nothing is ever written to disk, a restart forgets them, the
  route on the head's port answers only to the management key (a client-auth head's own callers
  cannot read it), and `splice doctor` shows an information row naming the head on every run while it is on.
- **Strip mode fixes.** Paragraphs split correctly on Windows line endings and double blank
  lines. On the OpenAI Responses wire, strip edits both `instructions` and every developer
  instruction item, so an append layer followed by a strip layer no longer leaves the client's own
  instructions untouched. The turn's statistics record how many system prompt layers were set and how many changed the
  request, and a layer deleted by a later strip is not counted as carried. `splice doctor` gives strip its own remedy and warns
  when no pattern list is set. An empty pattern file is a load error, a system field sent as a
  bare string stays one, and each pattern list is compiled once.
- **Slot affinity follows its server, costs a turn nothing, and says when it is off.** The slot count is re-read from `/props` in the background whenever a turn finds the last reading at least 10 seconds old: a llama-server restarted with a different `-np` silently wraps an out-of-range `id_slot` onto a slot another conversation holds, and splice kept pinning to the old count. A turn no longer waits on that read (it ran on the request path, holding a process-wide permit, for up to the probe timeouts against an unreachable server). Every head on one runtime now shares one slot table, which also survives a config reload, so two heads, or a reload with turns in flight, can no longer pin two conversations to one slot. A runtime that gives no slot count (router mode, a non-llama server, a keyed `/props`) is logged once with its reason instead of leaving slot affinity off without a word, and `splice doctor --json` shows `slot_affinity` and `stream_usage` among a provider's settings when set. A compaction retry routed to a different slot now still matches its recording: the replay key leaves out `id_slot`, which routes a request and is no part of it.
- **Hosted MCP servers can be reclaimed, and are capped when the host declares a place for them.** Each hosted child's `oom_score_adj` is set to -100 at spawn and read back. A hosted server used to inherit splice's own out-of-memory protection, so on a machine that shields splice from the out-of-memory killer, hosted servers were shielded too. splice sets no such protection on itself, and changes only the hosted server's value. Placement follows the same rule: a child runs inside the memory-capped slice when one is declared for it, and when none is (systemd will happily name a slice nobody defined, with no ceiling at all) splice says so once in the log instead of reporting containment it does not have.
- **A hosted MCP server keeps working across a daemon restart, an idle reap and an eviction.** When splice did not recognise a client's `Mcp-Session-Id`, it answered 404 and the client's subsequent calls kept failing. An id it does not know is now adopted: the child is initialized under the id the client already holds, and the restart is invisible. A session the client explicitly ended still answers 404, and so does one whose notification stream overflowed, because that one must reinitialize to re-list what it missed. An adopted session accepts the protocol version the client negotiated before the restart, since refusing it would only trade the 404 for a 400.
- **A connection that failed is named for what actually failed.** The JDK client wraps every connect failure in the same `ConnectException`, so a host that does not resolve and a host with no route were both told as `connection refused … nothing is listening there` and sent the operator to start a server; each is now named from the link that says what happened (`cannot resolve the host of …`, `could not connect to …: No route to host`), and only the client's real refusal is called one. A connect timeout's detail no longer carries the request's full URL, and the per-attempt retry lines in the log use the same words as the ending instead of an empty message. A 429 whose text mentions tokens (a per-minute token quota) is a rate limit again rather than an overflow: it takes the cooldown, and Claude Code is no longer told to compact a conversation that fits. On the OpenAI chat dialect, an in-band error's own numeric code is no longer read as an HTTP status, and an error the vendor typed or gave a status, which the same bytes reproduce, is no longer advertised to Claude Code as retryable; an in-band error with neither keeps the retryable wire it had.
- **A local model server's errors say what happened, and an in-band chat error keeps its class.** llama-server's three failures reached Claude Code as the bare text it sent, and the banner printed "API error" over them: a 503 `Loading model` now reads as the local server still loading its weights; `exceed_context_size_error` becomes `prompt is too long: N tokens > M maximum`, rather than an unclassified server failure; and the mid-decode `Context size has been exceeded.` is named as the shared KV pool being full, retried, never reported as an overflow, because the request fits and the other conversations hold the pool. The OpenAI chat dialect's in-band `error` event now goes through the same classifier as every other path instead of being treated as a generic upstream error whatever it said, so overflow and rate-limit events keep their distinct classifications; an event with no recognisable shape keeps the wire it had. In offline tests with Claude Code 2.1.285 and 2.1.286, a `prompt is too long` HTTP 400 after a tool result triggered compaction; a first-exchange refusal and the plain in-band overflow control did not. splice sends that shape for its own early refusal (the measured GPT models on a ChatGPT sign-in, and an OpenAI Responses row that sets `compaction_reserve_tokens`) and for an overflow the provider reports before both the first model frame and splice's own visible progress line; an overflow after either has opened the stream stays in-band. A failed connection names what happened and where instead of `upstream connection failed (no detail)`: the JDK client's refused connect carries no message, so a local server that was simply not running read as nothing at all; it now reads `connection refused by 127.0.0.1:8099: nothing is listening there; the server is down or still starting`, and connect timeouts, DNS failures, read timeouts, TLS failures, resets and early closes are each named with the endpoint's host and port (never its path or query). A context overflow answered with a non-429 4xx is sent upstream once rather than retried: the same bytes overflow the same window, and every re-send only delayed the compaction (on the bonsai head, with the old retry setting raised to ten, one 1.4 MB request went out ten times over 43 s). The same offline clients also compacted when a post-tool overflow arrived after splice's visible thinking progress, before any model answer text: the streamed HTTP 200 carried an in-band `invalid_request_error` containing `prompt is too long`, and the next client request was the summary, not a non-stream retry. Both clients recovered even with the fake reporting only 100 input tokens and its token-count endpoint fixed at 100; neither called that endpoint. This recovery was triggered by the overflow, not the reported usage threshold.
- **Only failures a retry can heal are advertised as retryable.** A failure arriving before any content now reaches Claude Code as the one in-band error it retries (`overloaded_error`) instead of a terminal `api_error` it reads as the end of the session. A failure that an identical re-send reproduces exactly (a model refusal, a content-filtered turn, a vendor `invalid_parameter`, an unparseable base URL) is exempt and keeps its real type: it is not a retry but a bill, up to 300 client re-sends, each spending splice's own upstream retries. A buffered (`stream:false`) failure is untouched by the rule and keeps its real status: a 429 stays 429 with its rate-limit headers, an api_error stays 502.
- **A rate limit or spent account can be retried before answer content reaches the client.** A persistent 429 before that point reaches Claude Code as the one in-band error it retries (`overloaded_error`, rate-limit words kept) instead of a terminal `rate_limit_error`, except on a native Claude head, which hands Claude Code the provider's own 429 reply with its rate-limit headers once splice's own wait is spent; every launched client runs in persistent retry mode (`CLAUDE_CODE_RETRY_WATCHDOG=1`, native head included) and sleeps until the reset splice already sends; and a spent account (grok `spending-limit` 403, any 402) is a 429 to every layer, including cooldown, account pool, classifier and admission, rather than an `invalid_request_error`.
- **An empty model turn is retried with backoff.** A turn that ends with nothing for the client,
  such as Muse reasoning its whole budget away, ends as `overloaded_error` with the explanation
  kept, so the client backs off and retries instead of stalling. A model that closes an empty
  message still ends cleanly.

- **The kimi usage probe never ran.** The usage probe only knew how to send a bearer token,
  and Kimi signs in with an `x-api-key` header, so no Kimi head showed a usage window. The probe now sends whatever headers the credential
  carries, and each head's probe is pinned by a test that asserts the exact header map.
- **Every codex and kimi quota probe carried xAI's headers.** `x-grok-client-mode`,
  `x-grok-client-version` and `X-XAI-Token-Auth` were built into the shared probe, so they rode
  on requests to ChatGPT and to Moonshot as well. Each head now owns its probe, its URL and its
  parser. The shared part sends the credential's own headers plus ChatGPT's account id when the
  credential carries one; only a ChatGPT login carries that id.
- **A malformed reset date killed the quota poller.** A vendor window whose `resets_at` did not
  parse threw past the poller's own error handling and ended the loop for the daemon's lifetime,
  with no bar and no log after it. The date parse is caught per vendor, and the poll loop carries
  the same completion guard the auth probe loop uses: it restarts up to five times in ten minutes
  and says so.
- **Api-key responses heads sent ChatGPT's internal lite marker, and the whole lite request body
  with it.** The responses-lite model regex defaulted to `gpt-5.6|gpt-6` on the shared dialect, so
  an OpenRouter head pinned to `openai/gpt-6` or `openai/gpt-6-mini` (or any id containing those substrings) emitted
  ChatGPT's internal marker on every turn, compaction included, and built the
  ChatGPT-internal lite input: tools as an `additional_tools` item, empty top-level `instructions`,
  plus `parallel_tool_calls`, `reasoning.context` `all_turns`, `text.verbosity` and
  `client_metadata`. Grok heads were never affected in practice: their ids are `grok-4.7`,
  `grok-4.6` and `grok-build-0.1`, which do not contain those substrings. But they inherited the same default and
  would have the day a grok id matched. The regex and the header name are now a pair a provider
  must declare together; only the ChatGPT/codex profile sets them. A third-party endpoint no
  longer inherits the marker or the lite body. After upgrading, an OpenRouter head on
  `openai/gpt-6` sends a non-lite request: `instructions` at top level, tools not in
  `additional_tools`, and those extra fields dropped.
- **An api-key head on a Gemini id silently refused effort max, and any id containing spark lost
  its reasoning summaries.** Pinning `google/gemini-2.5-pro` (or any model id containing `mini`)
  made `effort` `max` clamp without a word; pinning a spark-named model dropped reasoning
  summaries. Both shipped in 0.3.0 and are present in released v0.3.2. The clamp and the drop now
  apply only to the vendor they were written for.
- **Code mode now reaches Sol.** Eligibility matched `gpt-6-(astra|sol)`, a Sol id the catalog never
  had; the real `gpt-5.6-sol` and with it every sonnet/haiku-tiered subagent ran without the runner
  (measured 2026-09-20: advertised on 938 of 7,070 calls in one session). Code mode now runs on every model the ChatGPT backend lists as `code_mode_only`,
  plus any named in `code_mode_models`; a hidden one such as `codex-auto-review` runs it only when named there.
- **A failed cell says why.** The JavaScript harness dropped the rejection reason, so a rejected tool
  call, a syntax error or an oversized `console.log` all surfaced as a bare `Code execution failed`
  and the model reran every call directly. The error now carries the reason (a SyntaxError keeps only
  its position line, never the source) and the output logged before the failure; output past the
  64 KiB text ceiling is cut behind a `[truncated N chars]` marker instead of failing the cell.
- **An unplaceable record is abandoned once.** A running script whose history no longer placed was
  marked LOST but stayed findable by its client call ids, so every later turn of the conversation
  found it, failed to place it and logged `abandoned record` again (82 lines for one record on
  2026-09-20). It now retires with its interruption evidence on the first abandonment.
- **The usage warning reads the plan windows the `usageWarnPct` setting describes.** The setting
  says "warn when a plan window passes this share", but the level read only rate-limit token
  headers, which a subscription head never sends. A head at 99% of its 7-day window reported
  `ok`. `/api/usage`'s `warn` now takes the fuller
  plan window whose reset is still ahead (a window past its reset is history, not a limit). It
  names the window as `quota_5h` or `quota_7d` and gives the reset as an ISO-8601 instant. When a
  head reports both, the worse of the rate-limit and plan signals wins, and the 5-hour token count
  stays the fallback. A `usageWarnPct` of 0 silences `warn`, never `critical`.
- **The compaction counts say which days they cover.** `/api/compact` counted every
  row each head still kept, with no dates, so failures from weeks ago read as a current failure rate. `stats.by_outcome_7d` sums the
  last seven days across heads, and `stats.heads.<key>` gives each head's `total`, `by_outcome`,
  `by_outcome_7d`, `first_ts` and `last_ts`. `splice doctor` prints the last turn failure's age in
  the largest whole unit (`20d ago`, not `28801m ago`), as `splice status` already did.
- **Concurrent launches are no longer refused.** The launch request states its length and a busy answer is tried again,
  so several `claudex` starts at once are not turned away as chunked bodies. An installed launcher older than this is
  flagged stale by doctor.
- **The first launch on a fresh install works.** `claudex` before any daemon had run was refused with "cannot establish
  this launcher's owning HOME". A launcher installed inside the HOME now owns it, and the launch starts splice.
- **First-run words are plain.** A browser that did not open says so and points at the link, the daemon start reads
  "splice: starting splice.service…", and the PATH note appears only when the bin directory is not on PATH.
- **A key ledger splice cannot read is kept beside the new one,** never written over. The README names the unreadable
  copy.
- **Teams says what its request counts cover:** since joining on a slot, since members joined on the team.
- **Requests names a key by its provider,** "OpenRouter", and adds the variable, such as `OPENROUTER_API_KEY`, only
  when two keys feed the same command, so the row says which one it sent; two menu entries that read the same are told apart as "OpenRouter 1" and "OpenRouter 2", and an account from before splice
  tracked keys reads "OpenRouter, before key tracking". A door to one request opens it, and Requests' search keeps its
  filters.
- **An open Settings card taller than its pane says "More below"** on its lower edge until the scroll reaches the end, and
  the day or night theme holds across pages and reloads.
- **Continue on is honest about what it can do.** It says a command is not installed, with its `splice install` line,
  before the press; drops a plan window whose reset has gone by; and, with reading off, still knows the model a session
  is on.
- **`replay_reasoning = false` keeps an earlier turn's reasoning out of the request.** The gateway cache put it back before
  every client call and code mode re-emitted it; the one place that builds the request now drops reasoning from before the
  last real prompt, and the current turn keeps its own. A compaction follows the same rule.
- **A round the model answers with nothing is re-sent once, with the same body.** On `claude-splice` the client's own
  re-send came back with an answer in 52 of 84 cases, so the identical bytes heal most of them. A second empty answer ends as
  before, a finished empty message is never re-sent, and the first round's billed input reaches the request's row.
- **A thinking block replayed with its token estimate no longer fails every retry.** Claude Code stores a counter from a
  streamed thinking delta and replays it, and the vendor refuses it with a 400 that named it. The key is dropped from thinking
  blocks, and a 400 that names a path into `messages`, `system` or `tools` is sent once and answered with the vendor's own
  message, not re-sent four times.
- **Upgrading works where there is no user systemd.** The console's Upgrade starts on macOS, in a container and in a session
  without a bus: when `systemd-run --user` fails or is absent, the same run starts detached from the daemon, and
  the reason both failed is said when both do. `splice upgrade` on an install no unit supervises comes back on the new release,
  not the old one, and systemd-run's complaint about a missing user bus goes to its own log, not the run's output.
- **A Projects folder typed the way people type paths works.** `~` and `~/work` are the home's folders, in Save, at boot and in
  the check.
- **The MCP timeout says whose reply it waits for:** "Give up on a server's reply after" is the budget for one request splice forwards
  to an MCP server.
- **"Waited in line" counts only a request the gate held because its command's limit was full.** A pass through an open gate
  records a wait of a millisecond or so, and on a busy day over a thousand of them read as waiting under a peak of 33 and
  a limit of 100. A held request now records how long it was held, and the peak and the Requests filter read that.
- **A client that sends its session only in the request body still gets its session.** The session id is read from the
  body's `metadata.user_id` first and the header second, and a compaction finds its project from it.
- **A session whose transcript is gone is never a door to nothing.** Open session no longer leads to a Sessions page
  reading "No sessions".
- **Every page marks its own nav item** whatever address it was opened at.
- **A request splice ended before any attempt shows no token tiles.** Attempts 0 stands alone, like Cost.
- **Requests reads as its own shape arriving** instead of a dashed "Reading" box, so loading no longer looks like an
  empty day.
- **A plan request's "≈$X at API prices" sits on the top line before "On your plan"** in Highest price.
- **A project, head or project-head prompt keeps one place in the input on the OpenAI Responses dialect,** so its
  tokens hit the prompt cache again on claudex, where an append-mode system prompt had been moving it to the end of the input each turn.
- **Usage and Requests load on a week of history.** A read asks the login files once per distinct recorded account,
  not once per row.
- **Teams' account picker lists your logins,** reading the real shape the accounts route answers.
- **Usage's No project shows only requests with no project.**
- **Compare shows which account the next turn uses,** read from where the daemon publishes it.
- **Removing a command is no longer refused over another command's finding,** and a refused remove says why.
- **Models: a command on its default model shows that model** and lets you set its window up to the provider's maximum.
- **The Usage summary and Highest price read a week of requests faster:** each request is priced once, not once per
  comparison.
- **A failed request that reported no usage counts as failed,** not unpriced, in the economics and budget figures.
- **A planned restart no longer logs as a stalled turn path.** The probe counts no failure while the daemon drains
  for a stop, and /health stays ok.
- **`splice console`, when no browser opens, prints a page that signs you in.** It used to print the bare address,
  which opened the console signed out. The key stays in a private file and is never printed.
- **A stop, an answer or a message from any card counts as the person handling the session,** Teams included. A team
  member that came up and later ended starts again instead of answering "closed".
- **One bad pool file refuses only its own login.** A credential signed in for another provider, carrying another
  label or unreadable lists as refused with its reason, and the pool's other logins keep serving. One mislabeled codex
  file used to fail every codex login. A primary credential that declares another provider's kind is refused the same
  way.
- **`splice add` checks a local model's window against what the runtime serves,** read from Ollama and LM Studio as
  boot reads it, and refuses a row by the rules boot applies, such as a model the runtime says cannot call tools.
- **`splice models` agrees with boot on a local model the runtime does not list:** on Ollama, LM Studio and vLLM it
  reads unserved, and a model id with no tag matches its `:latest` tag.
- **An MCP server that asks for its session's project roots is no longer shared,** since a shared copy has no one
  session's roots. Every session starts its own copy, and Settings reads "Asks which project it's in". The verdict is
  kept across restarts as a digest that holds no command, argument or environment value, so the first session after a
  restart starts its own copy instead of losing a shared one mid-work.
- **A local model that is not pulled or loaded ends the turn on the runtime's first answer, in its own words,** such as
  Ollama's "try pulling it first". It used to be retried until the retries ran out.
- **An LM Studio model that is not loaded is held only to its maximum window.** LM Studio loads a model at its own
  default window, so a row checked against the maximum failed once the model loaded smaller. A loaded model is held
  to the window it serves, and doctor says the served window is unknown until the model loads.
- **Doctor gives the runtime's tool answer for every local model it accepts:** "the runtime says it calls tools" or
  "the runtime does not say whether it calls tools". It used to show only on a refused model.
- **A code-mode host with no work releases its worker process.** Once its last session has been idle for the idle
  window, the host leaves the pool and its process exits, and the next session starts a fresh one.
- **Settings › MCP:** a command still on an older copy of the config is listed under Used by with "Next session". A long
  source path ends in an ellipsis inside its column, and a server set up for one project names that project.
- **Adding a second command on a provider already set up starts from the address that provider sends to,** in a field
  you can fix. A failed address check names the address it tried, and Add with a failed check says on the form what it
  waits on. The pane that opens after Add is the new command's own, and a command added beside another names its
  provider.
- **Accounts:** the key in use shows its masked tail, with a date only where its variable held two keys. A window that
  passed reads "Ended", and a default name's plan tag shows only when it says more than the name.
- **Many agents at once › Today reads "Not read" beside a command whose peak was not read,** or "Not answering" when
  splice did not answer.
- **Teams keeps your teams on the page when a reread fails,** with one line saying why, and a refused read always says
  a sentence, never a bare status. A member that did not come up says why on its card.
- **A team message that a member's subagent sent shows its text,** read from the subagent transcripts Claude Code keeps
  beside the session's own. A message whose text was not found reads "Text not found", and a sender outside the team
  is named from history or reads Outside, never an id.
- **Teams' chips draw an ended member's dot hollow,** as they do an unstarted one, and End all no longer moves the
  requests line.
- **Teams shows the day it says.** Only the newest read draws, and a stepped day shows its chat and activity reading
  until its own day lands, never the day before under the new day's name.
- **A team member's model reads as Sessions names it,** never as a raw id its command does not declare.
- **Sessions titles an unnamed session in a linked worktree by the worktree's folder,** not its whole path, which ran
  past the card's edge.
- **A conversation that comes back without its messages reads Not read,** never Reading for good.
- **A move that did not land says when its screen could not be read,** instead of answering as though the terminal
  showed nothing.
- **A provider's own 429, relayed to the client, ends `error:rate-limit-relayed`,** not `error:upstream-failed`. Each
  read as a give-up, so Sessions said Gave up after a run of them. Requests words it Rate limited under the limit's
  glyph and counts it under the limited filter, and no session reads it as giving up.
- **An open rate-limited request says whose limit it was.** One splice held unsent reads Not sent on the provider
  line. A resumed or started-over request says why its first try ended, such as "Started over after a 20 s
  silence", "after the stream broke" or "Resumed 3 times · 45 s of silence".
- **A request that failed before any model answered reads $0,** on Usage and in its detail, with Cost and Attempts
  and no token figures. No price stays for a request a model answered with no rate card.
- **Usage's first open marks each command still being read** with its chip and Not counted yet, above the figures
  it is missing from, so a half-read week never reads as the whole week. A command that could not be read shows
  its chip and Not read.
- **Settings › MCP names a plugin's server by its plugin,** not by its version folder or a half-installed clone,
  and by the place it is really declared when it is declared twice. A server the Servers list keeps one per session
  for its own reason reads under One per session either way with that reason, out of the savings figures.
- **A Claude reply the model ends empty on purpose ends cleanly.** A message that stopped on `end_turn` or a stop
  sequence with no content was graded an empty answer, re-sent, and handed to Claude Code as `overloaded_error`.
  Claude Code's prompt-suggestion requests end that way, so it re-sent each one at about 300k cached tokens. A stream
  with no `message_stop`, or one stopped at `max_tokens`, is still the empty-turn error.
- **A Responses answer that holds only reasoning ends cleanly.** A completed response whose output is all reasoning,
  as Muse sends, was graded an empty answer and failed. It now ends as an empty message, as codex treats it.
- **A code-mode tool call too large to send goes back to the script as an error,** so the turn continues and the
  script can try a smaller call. A second oversized batch from the same script ends it.
- **A refusal the vendor will repeat is not re-sent.** Grok's 400 `invalid_image`, Muse's 403 plan limit and Kimi's
  plan-tier answer each fail at once in the vendor's words, and Kimi's reaches Claude Code as a 403. A Grok 403 for
  an expired sign-in that a refresh did not fix is told as an authentication failure.
- **Requests shows the reset of a 429 whose body echoes `X-RateLimit-Reset`** in epoch milliseconds, as
  OpenRouter's does.
- **Models shows a local head as Not answering when its runtime does not answer,** where it read Ready.
- **Models shows a declared model that resolved to nothing** under its tier, as Not served with the reason.
- **Add on Models never ends in nothing:** a check that stops it shows beside its name, and an answer with no checks says
  Not added.
- **A signed-out command has its act on Models:** Sign in, Add key or Replace key.
- **A forwarded Claude model is capped at its native window:** a head-wide window narrows it, never widens it.
- **Discovery accepts a window up to the provider's published ceiling,** keeps the last list when an answer has no
  readable rows, and each fallback line in the log says why.
- **The Models compaction ladder shows a rule saved in the editor,** where it showed Claude Code's own.
- **An expired Claude token beside its refresh token is not a sign-out.** The card keeps its readings and says Token
  expired, with the one command that renews it. A token with no refresh token still asks for a sign-in.
- **A login held at its plan limit reads as held, with its reset,** not Signed out.
- **A head's last switch and its account log line count a change of login, once per change,** where every new session
  counted its start as one.
- **`splice add deepseek` reads DeepSeek's model list with its key.**
- **Settings' Plan limits names each login by its command and name,** draws no reading from a signed-out login, and lists
  logins that report no limit under No limit reported.
- **Settings' per-command values share one shape,** the chip beside its control and the way back under it, and an own
  value equal to the shared one says Follow every command.
- **Your data's Prompts and answers row claims no more than trace keeps:** "Up to N days, sooner past its size caps,
  oldest day first", or "Until its size caps".
- **A session with no conversation yet is looked for once, not on every read,** where each read of it went through
  every project folder again, 12 s each time for a new manager's composer.
- **A round that only runs code is no longer an empty answer under StructuredOutput:** a reply that held one completed
  `exec` call and no JSON was refused as "empty structured output", so the client got an overloaded error and sent it
  again.
- **A full code-mode pool reclaims idle parked scripts of sessions nobody vouches for after 5 minutes, not 30:** subagents,
  headless clients and ended sessions are not in the Claude Code registry, and their parked scripts held 50 of 53
  engines while every new `exec` was refused. A session the registry lists as live is still never reclaimed.

## splice v0.3.2 — code mode keeps its workers and its evidence, and fails in words - 2026-09-07

### Fixed
- **Code mode no longer runs out of workers behind clients that never came back.** A script whose
  client calls were never answered (the session was abandoned, compacted or killed) kept its worker
  slot indefinitely; with four such cells parked, every new script on every session failed with
  `code-mode runtime failed to start` for the rest of the day. A cell parked longer than 30 minutes
  without results is now closed, and at capacity the oldest cell parked over 2 minutes is evicted
  for the newer script. Both are recorded on the record and logged once under `[<head>][code-mode]`.
- **A lost script's evidence is never too big to report.** The interruption output (results so
  far, unresolved calls, the reason) rides upstream as the outer call's own output, but it was graded
  against the worker's 64 KiB text frame, which it never crosses: any script whose accumulated
  results passed 64 KiB (five `Read`s) poisoned its record, and the same request then failed
  identically on every retry (47 turns over 80 minutes on 2026-09-07). Evidence is now bounded by
  the upstream output ceiling only, and past that each result is cut to an equal share behind a
  `[truncated N chars]` marker instead of the turn failing.
- **A code-mode failure is now readable in Claude Code.** Every gateway failure went out as an
  SSE `error` event. Claude Code 2.1.x re-sends an `api_error` identically until it gives up when
  the event arrives before content, and after content drops the message for a fixed "API Error:
  Server error mid-response" line, so a code-mode verdict that no retry can change (a record it
  cannot resume, a script it cannot admit) surfaced as a retry storm or an unreadable line. Such
  failures now end the turn with the explanation as a `\u26A0 splice:` text block and a clean stop;
  transient upstream faults keep the error event the client is right to retry.
- **Capacity and lost cells report to the model instead of failing the turn.** When no slot can be
  freed, the script's own output tells the model nothing was executed and to call the tools
  directly, and the turn continues. A lost cell retried with the same request likewise completes
  with its evidence (results so far, unresolved calls, the reason) and goes upstream once, rather
  than answering 502 until new user content arrived. The spawn failure's cause is now logged; it was
  swallowed before, which is why the pool being full went undiagnosed for an hour.

## splice v0.3.1 — silent-stream reliability and the code-mode beta - 2026-09-06

### Install

Install this exact version (requires an authenticated GitHub CLI; run `gh auth login` once):

```bash
curl -fsSL https://github.com/torad-labs/splice/releases/download/v0.3.1/install.sh \
  | env SPLICE_VERSION=v0.3.1 bash
```

### Fixed
- **Code mode no longer refuses a conversation whose environment moved.** A completed script's
  history baseline is now measured on the conversation alone; the lite preamble (the eager tool
  list and the base instructions) is excluded. Claude Code grows its tool list mid-conversation
  (ToolSearch loading a deferred schema, an MCP reconnect), and one such growth after a completed
  script made every later Astra/Sol turn fail with `code-mode logical history does not match its
  persisted baseline` until the session compacted. The same growth during a script's own resume
  turn was misread as new user content and interrupted the script.
- **History that cannot be placed degrades instead of failing.** A record that no longer lines up,
  a record past its 24-hour retention, a result from another session or model, or a running script
  whose history moved underneath it now continues upstream on the client's own history, where its
  client calls are ordinary tool calls. Each degradation logs once per conversation under
  `[<head>][code-mode]`. Persisted records from 0.3.1 carry the old measurement and are omitted the
  same way, so an already-stuck conversation recovers on its next turn.

### Added
- **Default-off JavaScript code mode for ChatGPT providers.** Set `code_mode = true` in a
  `chatgpt-oauth` + `openai-responses` provider's quirks to enable the bundled GraalJS runner and
  orchestration guidance together on eligible GPT-6 Astra/Sol turns. Custom head names work;
  compaction, toolless turns and forced named-tool choices retain the ordinary path. No Node or
  Codex installation is required. Real operations remain Claude Code's permission-checked client
  tool calls; JavaScript has no direct shell, filesystem, network or MCP access.
- **Bounded execution with honest recovery.** Worker, time, heap and wire limits bound each cell.
  Durable ownership and history retain completed evidence, but a lost worker's JavaScript is never
  rerun automatically. New user content interrupts before further execution, infrastructure faults
  remain failures, and cancellation retains only known completed-round usage. Graal community is
  not an OS-hardened sandbox against same-user attackers.
- **An explicit beta switch, not a silent default change.** False or omitted disables both the
  runner and its guidance. The provider setting applies to every head using it and is read at
  daemon boot: finish pending work before changing TOML, then perform a full `splice restart`.
  A head restart alone does not reload topology. Bounded synthetic comparisons support the beta;
  they do not establish general output-quality or efficiency gains.

### Fixed
- **Silent streaming turns send real SSE ping events.** Keepalive traffic is recognizable to
  Claude Code during long upstream silences. Optional, default-on `progressLine` messages identify
  themselves as splice-authored status, not model reasoning. Every streaming head uses the same
  mechanism; disabling progress lines does not disable pings. The switch requires a restart.
- **Progress respects the stream lifecycle and accounting.** Separate progress and model writers
  share block indexes without letting a blocked model write silence keepalives. Progress waits for
  the published opener, stops at terminal sealing, and never counts as model output or first-delta
  timing. Rejected pre-opener writes do not consume the introductory status line.

### Changed
- **Every OAuth head signs in on its own credential file.** `chatgpt-oauth`, `grok-oauth` and
  `kimi-oauth` now default to `~/.config/splice/auth/{codex,grok,kimi}.json` (kimi's `device_id`
  beside it), written by `splice login <head>` on whichever account you choose there, which may
  differ from the account the vendor's own CLI or desktop app uses. The apps' files
  (`~/.codex/auth.json`, `~/.grok/auth.json`, `~/.kimi/credentials/kimi-code.json`) are never read
  unless `auth.file` names one. Sharing a file was a trap: a refresh rotates the refresh token, so the
  app and splice signed each other out, and the head had no credential while the other side rewrote
  the file (24 failed turns in one 16-second rotation on 2026-09-05). Existing configs that name an
  app's file keep working; `splice doctor` now warns about them, and the fix is to drop `file` and run
  `splice login <head>`. The `CODEX_AUTH_PATH` / `GROK_AUTH_PATH` overrides still apply.

### Fixed
- **A compaction outlives its client.** Claude Code abandons an auto-compaction at 600 s and
  retries the same bytes minutes later, and every abort used to cancel the upstream turn (Astra
  compactions run 5-10 minutes; 7 were cut off this way on 2026-09-05). A compact stream turn now
  runs on a scope the call's cancellation cannot reach and records its frames: a lost client
  detaches, the turn finishes, and the byte-identical retry is served from the recording (or
  follows the turn live if it is still running), with no second upstream turn. A head stop ends
  the compactions still driving; the scope itself survives a restart (review: the first cut
  cancelled it, and the first compaction after a restart came back empty with its slot leaked).
- **Claude Code's activity-label side query is answered locally.** Every 30 s during a subagent
  turn the client re-sends the whole transcript asking for a 3-5 word present-tense label
  (294M input tokens in a day at 48% cache hit, 2026-09-05). The head recognises the query and
  answers it from the transcript's last tool call, with no upstream turn.
- **codex-rs's session and routing headers ride every turn** (`session-id`, `thread-id`,
  `x-codex-routing-hint`, and the WebSocket handshake's `x-client-request-id`), so a reconnect
  can land on the same backend replica and its prompt cache.
- **A session's learned client window survives a daemon restart** (`<head>-client-windows.json`
  in the state dir), so the first turn after a restart is scaled against the right window instead
  of reported raw. A rejected request body is now logged with its byte counts and failure class.
- **An auto-compaction no longer re-reads the whole transcript cold.** Claude Code compacts
  between a tool call and its execution, so the compaction body ends at the previous tool result
  and the call the backend just emitted is never answered. Chained over the WebSocket, the backend
  refused every such turn ("No tool output found for function call …", 4 of 4 on 2026-09-05) and
  the round fell back to a cold SSE send with the prefix cache lost (0-37% hit on 200k-token
  compactions, 5-10 minutes each). The chaining state now remembers the calls a response left
  open, a turn that answers none of them full-sends on the same socket instead (prefix cache
  kept), and the fallback line names the backend's failure terminal (type, code, message) so the
  next refusal of this kind is diagnosable from `daemon.log` alone.
- **A compaction is built byte-identical to a turn, so it hits the prompt cache.** Every dialect
  used to reshape the compaction request (a directive appended to the instructions or system,
  tools and `tool_choice` stripped, tool results folded to text, images dropped, the lite shape
  off, cached reasoning left out, an effort pin), and the backend's exact-prefix cache missed the
  whole transcript on every compaction (`cached_tokens=0` on every model, 2026-09-05). The request
  now carries the session's model, reasoning, tools and history unchanged; `compact` only reaches
  the response side. The `compact_effort` quirk is retired and a config that sets it fails at load.
- **A running session's counts are scaled against ITS window, never a guessed one.** Its process
  divides by the window it was launched with, so the head learns each session's real window from
  its status-line post (`session_id` + `context_window_size`) and scales that session's counts
  against it. A session that has not posted yet is assumed on the pinned row's current window,
  i.e. exact. For one morning on 2026-09-05 every launch planted a constant 1e6 instead, and the
  sessions launched before it were scaled 2.5-3.7x against a window their process never had:
  each compacted at a third of its row's window, forever (eight compactions in forty minutes on
  one session, every one immediately re-triggered). Live, no relaunch.

### Changed
- **A context window edited in `splice.toml` now reaches running sessions.** A launch plants the
  pinned row's window (`CLAUDE_CODE_MAX_CONTEXT_TOKENS`), every other row is usage-scaled on the
  wire, and a session keeps compacting at the row's CURRENT window after an edit because its
  counts are scaled against the window it reports on its status line. Before, lowering the pinned
  row's number (the 2026-09-05 move of the codex rows to 272k, under OpenAI's 2x long-context
  price line) changed nothing for the six sessions already running until each was relaunched. Now
  `splice restart` is enough. Ids starting with `claude-` (a passthrough head's own models, a
  discovery-wrapped tier) ignore that env in Claude Code and keep reporting raw counts.

## splice v0.3.0 — plan usage on every head, GPT-6 Astra on claudex, and a proxy that matches its reference client - 2026-09-04

### Added
- **GPT-6 Astra is a row on `claudex`.** OpenAI shipped GPT-6 Astra on 2026-09-03 and it is
  included in the ChatGPT plan allowance. The example config carries it as `gpt-6-astra` (label
  "Codex 6 Astra") and plants it on the head's fable tier, so `/model` lists it beside the 5.6 rows
  and the default pin is untouched. Codex's own catalog serves it responses-lite with efforts up
  to `max`, so the lite gate now matches the gpt-6 family as well as gpt-5.6.
- **Every head shows its plan usage the way the native Claude head does.** The daemon tracks each
  head's 5-hour and 7-day windows (ChatGPT's usage endpoint and its `x-codex-*` round headers,
  Kimi's usage endpoint, SuperGrok's billing period, Anthropic's own unified headers on the
  passthrough head), stamps them onto every response as the `anthropic-ratelimit-unified-*`
  headers Claude Code reads into its `rate_limits`, and draws them on the status line as the 5h
  and 7d bars with the reset time once a bar is worth acting on, beside effort and session spend.

- **The plan-usage poll is stated plainly, and it has an off switch.** To draw those bars before
  the first turn, a subscription head asks its own provider every five minutes for the daemon's
  life, with that head's own bearer: `claudex` reads `chatgpt.com/backend-api/wham/usage`,
  `claude-kimi` reads `<kimi base>/v1/usages`, and `claude-grok` reads
  `cli-chat-proxy.grok.com/v1/billing`. API-key and client-auth heads poll nothing. This is new
  outbound traffic since the beta, so it is named here rather than left implicit;
  `CLAUDEX_QUOTA_POLL=off` stops every poller (daemon restart), and the bars then draw only from
  the rate-limit headers each round already carries.

- **Heads that see each other.** Every wrapper's `sessions` directory is linked at the one registry
  under `~/.claude/sessions` on first launch (created if plain `claude` never ran on the machine), so
  claudex, claude-grok, claude-kimi, claude-openrouter, claude-splice and plain `claude` sessions all
  appear in each other's `ListAgents` and can message each other with `SendMessage`: a session on one
  backend can orchestrate a session on another. On by default through `[claude].share`;
  `isolate = ["sessions"]` walls a head off. (Shipped in this release without a changelog entry;
  recorded here.)

### Changed

- **The shipped example config now matches the daily-driven one.** `claudex` ships with the
  Responses WebSocket transport, zstd request bodies and the deferred tool surface (LSP deferred)
  on, plus the inflight ceiling that account has sustained; `claude-kimi` offers Kimi K3 at 256k and
  Kimi K2.7 Code beside the 1M row; `claude-openrouter` offers GLM 5.3 beside Llama 4 Maverick.
- **Every perf row and every client-abort line names the client session.** The daemon now stamps
  the Claude Code session id on every dialect's turn (only the Responses dialect kept it before),
  writes its short tag into the perf JSONL and the perf log line, and the "client gone" line names
  the session and the failure class instead of `keepalive write failed: null`. A client abort in
  the log is now one grep away from the session that hung up and the transcript that says why.

- **A WebSocket end of stream reports what was observed instead of naming a culprit.** The
  transport logged "socket closed by the server (status=1006)" twelve times a day without saying
  which socket. Status 1006 is reserved by the WebSocket RFC and can never be sent by a peer. Our
  own client synthesises it whenever the stream ends with no close frame, so the origin, a load
  balancer and the network are indistinguishable from here, and the old wording asserted an actor
  the client cannot see. The line now says the stream ended with the actor unknown, and carries the
  socket, its age, whether a round was in flight, the last frame, the last peer ping and the open
  socket count. A real close frame still names the peer, its code and its reason. Read that way,
  six of one day's twelve were sockets left idle in our own pool for three to twenty-five minutes,
  and eleven of the twelve ended no round at all.

- **Every daemon log line now carries its own date, at a fixed width.** `daemon.log` rotates by
  size and never by day, so a single file spans as many days as 64MB buys — 265,321 lines over four
  of them in the 2026-09-02 audit — while each line was stamped with a bare wall clock. A line read
  on its own could not say which day it belonged to, and a reader attributed a full day of watchdog
  stalls to the build running the next morning; they were the previous day's, on code already
  replaced. The same audit found the stamp was not even fixed-width: `LocalTime.toString()` drops
  the seconds field when it is zero, which had stamped 4,339 live lines `[13:47]` and quietly broke
  column-oriented reads. Lines now read `[2026-09-02 13:47:00]`.

- **A watchdog-ended turn says which tier fired, the limit it held, and the silence it measured.**
  The stall message printed the configured idle cap whatever tier had actually tripped, so a
  compaction killed at its first-output tier read as a mid-output stall and the log could not break
  the tie. The turn line now carries the fired sentinel's own three numbers
  (`watchdog=idle(tier=… limit=… idle=…)`); a turn the watchdog did not end prints byte-identically
  to before.

- **The mid-stream stall detector now matches the reference client instead of guessing tighter.**
  `streamIdleMs` judged a stream that had already begun flowing and aborted it after 180s of
  silence. codex-rs sets its only stream timer to 300s and applies it to the receive side alone
  (`DEFAULT_STREAM_IDLE_TIMEOUT_MS`, model-provider-info/src/lib.rs:26). Against the same backend
  our tighter number ended 129 compactions in one day, each already mid-output and each costing a
  full transcript re-read. The default is now 300s, equal to `firstByteTimeoutMs`, so one number
  judges a stream before and after its first frame. Heads that want a tighter stall still set it.

- **The socket gets five attempts before the turn falls back, matching the reference client.**
  A retryable mid-stream failure re-anchored at most twice before the turn dropped to the SSE
  transport. SSE is a fallback, not a co-equal second path: reaching it discards the socket's
  cache key and re-uploads the whole body, which on today's traffic means a median 618KB and a
  p99 of 5.2MB sent twice. codex-rs retries a retryable stream five times
  (`DEFAULT_STREAM_MAX_RETRIES`) before it switches transport, and this controller exists to be
  the proxy-side answer to that loop, so the budget is now five. Turn recovery and its cooldown
  backoff are unchanged; only the number of times they may run has widened.


- **The watchdog turn-line tests pin what production does.** A test claimed a compaction's
  first-output tier is lifted to the whole-turn cap; production switches that tier off
  (`WatchdogBudget.forCompact`), so only the total cap ends a silent compaction. The arm now pins a
  non-compact first-output fire and a compact total-cap fire, and two new `TurnFinish` arms fire
  real pollers on virtual ticks and assert the rendered line carries the sentinel through the
  production hop.
- **`CLAUDEX_QUOTA_POLL=off` has an effect test.** `ManagedHeadFactory` exposes a poller-start
  seam; assembling a real ChatGPT subscription head with the knob off starts nothing, with `auto`
  starts one.
- **The stable pin recipe is exercised.** `checks/release/accept.sh` now installs with
  `SPLICE_VERSION` pinned to the jar's own stable tag as well as a synthetic prerelease, and fails
  when `install.sh` ignores the pin.
- **Changelog bookkeeping.** "Heads that see each other" landed after `v0.3.0-beta.1` and now sits
  in the v0.3.0 section, so the beta.1 list matches its tag.

### Fixed
- **An empty answer is an answer, not an API error.** When the model closes a message with no
  text in it, the turn now ends clean, the way codex ends it (only a tool call asks for a
  follow-up there). Found on GPT-6 Astra: after its final answer, a project Stop hook echoed an
  end-of-turn report back as a continuation, Astra correctly had nothing to add, and the empty-turn
  gate graded that `empty_model`, so Claude Code retried the identical 120k to 320k-token request
  eleven times per incident, thirteen times in one evening. A round with no message item at all
  is still the honest error it was, a compaction that returns nothing is still an error, and the
  clean case is tagged `empty_message` in the perf row and names the shape the backend sent
  (`reasoning(summary=0,enc=1700)`, `message(output_text:0)`) in the log.

- **Compactions no longer die on the ChatGPT backend's capacity signal.** An in-stream
  `server_is_overloaded` (or `slow_down`) used to classify as a non-retryable `api_error`, so the
  proxy neither reissued nor salvaged the turn and Claude Code reported the compaction failed. Any
  overload-shaped error code on an in-stream or server-side (5xx) failure is now a transient
  `overloaded_error`, the type Claude Code retries with backoff, matching codex's own handling of
  the same two codes; a 4xx keeps its deterministic verdict whatever its code spells.
- **Claude Code now waits out the proxy's own whole-turn wall.** Every wrapper plants
  `API_TIMEOUT_MS` from the head's `upstreamTimeoutMs` plus a minute of grace (960 s on the
  default 900 s cap). With Claude Code's 600 s default, every compaction longer than ten minutes
  ended as a client abort while the daemon was still streaming the summary.
- **A silent compaction is ended by the whole-turn wall alone.** The compact budget used to raise
  the first-output tier to the wall instead of switching it off, leaving two pollers on one
  deadline; on a loaded runner the idle poller could win the tick and end a round as "first-output
  cap" (salvage invited) where the wall should have ended the turn. The tier is now off for
  compactions; `streamIdle` still reaps a stall once output has begun.
- **A torn perf row no longer swallows the row after it.** When the disk filled mid-append
  (2026-08-25) a short write left a fragment with no newline and the next row fused onto it, so
  readers lost both. The JSONL sink now heals a torn tail before appending.
- **The status line follows the picked model row.** Claude Code fixes its context window per
  process and splice scales the counts it reports so any other row compacts at its own window,
  which left the bar showing the session's window and scaled counts however the operator switched
  (`grok-4.6[500k]` on a 256k grok head still read `…/256k`). The daemon's status line now renders
  the picked row's label, its declared window and the real counts.

- **`splice restart` no longer loses the new daemon to the old one's last second.** The restart
  spawns the new daemon the moment the old one's ports are free, but the old process releases the
  daemon lock only after its engines' stop grace and log drain, up to a second later on a loaded
  box. The new daemon tried the lock once, conceded to "the winner" that was already leaving, and
  exited, so the restart reported "did not come up" with nothing serving. The loser now waits out
  the old daemon's whole teardown floor and yields only to a peer that answers `/health`.

- **A torn compaction restarts inside the proxy instead of failing the turn.** Every other round
  already re-anchored on a stream that ended without `response.completed` or on a transient server
  error; compaction alone was excluded on the belief that the pre-stream retry covered it, and the
  daemon log shows it did not (five compactions and three `server_is_overloaded` events surfaced
  as `overloaded_error`, each re-sent cold by Claude Code). A compaction's partial is usage-only,
  so its restart is the verbatim whole request with backoff, the same retry Codex CLI performs for
  its remote compaction. Deterministic verdicts (`cyber_policy`, refusals, the content filter) are
  still never re-POSTed.

- **A large WebSocket frame is no longer killed for being large.** The send budget was a flat 10s,
  sized when the biggest frame on the wire was 1.5 MB; frames now reach 7.7 MB, and every "send
  failed stalled" of a morning (13) landed while a 5–6.5 MB frame was in flight — a healthy socket
  poisoned and the same megabytes re-sent over SSE. The budget is now the floor plus the frame's own
  transfer time at 100 KB/s, and the stall line names both the budget and the frame size.


- **The `/model` picker lists each model once.** Every head showed a slotted model twice (Sol
  twice on `claudex`; Kimi K3 (256k) and Kimi K2.7 Code twice on `claude-kimi`). Claude Code draws
  one row per planted tier (`ANTHROPIC_DEFAULT_{OPUS,SONNET,HAIKU,FABLE}_MODEL`) and dedupes rows
  by their alias, so two tiers on one model drew it twice: Fable shares the frontier with Opus, and
  on a two-model head Haiku shares Sonnet. A tier cannot be left unset, because its alias then
  resolves to Claude Code's built-in model, which the head rejects. A repeated tier is now planted
  under the head's discovery-wrapped spelling (`claude-codex--gpt-5.6-sol`): the head routes it
  like the bare id, and the picker's allowlist hides the row. Cache rows are unchanged; they are
  where the Default line's label comes from.

## splice v0.3.0-beta.1 — native Claude auth and a hardened multi-head gateway - 2026-08-30

### Added

- **`claude-splice`, the native-auth Claude head.** Claude Code keeps its own Anthropic login and
  sends the caller credential through the local passthrough head; splice never stores, reads,
  refreshes, or logs that credential. The management key is not reused on this route.

- **Per-model context windows in the live model picker.** Each configured row reports its effective
  window without spelling a model above its real backend ceiling, so `/model` can switch windows
  without restarting Claude Code.

- Provider OAuth sign-in plans for ChatGPT, Grok, and Kimi now resolve from the configured auth kind,
  with deterministic matrix coverage for every supported head.

### Changed

- Provider-native readable reasoning remains visible as thinking blocks, while
  `mirror_reasoning` is locked off after every configuration layer. TOML, state, environment, runtime
  PATCH, and direct construction cannot enable synthetic transcript reinjection.

- The release pipeline now accepts SemVer prerelease tags and marks versions containing `-` as GitHub
  prereleases. Beta installs use a version-pinned URL; the stable `latest` installer remains stable-only.

### Fixed

- **Codex compaction no longer dies at the idle cap while the model is still reading.** The stall
  watchdog switched to its short `streamIdleMs` tier on the first upstream byte, and on the
  Responses API that byte is the `response.created` handshake, not output — so a compaction that
  reasoned silently over a large transcript for longer than 180s was aborted and re-sent cold by the
  client, in a loop (109 stalls on one head in a single day). The tier now follows the first client
  content frame: until the client has seen output the silence is judged on `firstByteTimeoutMs`,
  after it on `streamIdleMs`, on both the SSE and WebSocket transports. A compact turn's pre-output
  silence is bounded by `upstreamTimeoutMs` alone (compactions on the corrected tier still
  died silent at the 300s cap). The stall message names the tier that actually fired.

- **A content-policy refusal is terminal, not retried.** ChatGPT's `cyber_policy` flag (and the
  Responses API's documented prompt refusals: `invalid_prompt`, `bio_policy`,
  `image_content_policy_violation`) reached Claude Code as a retryable `api_error`, so every refusal
  became a backoff storm of the same multi-megabyte transcript. They now surface as
  `invalid_request_error` with the vendor's own remedy text, matching the HTTP 400 the vendor returns
  for the same refusal pre-stream.

- Refresh failures for Codex, Grok, and Kimi no longer risk logging vendor response bodies, and
  KeyStore values containing `#`, quotes, or backslashes round-trip without corruption.

- Request-body torn wakeups become an Anthropic-shaped HTTP 400 without swallowing genuine coroutine
  cancellation; chat and Responses stream translators also stop draining runaway producers.

- A newly created Responses WebSocket can no longer evict itself while older pooled sockets are busy.

- Session-registry migration now preflights destination collisions, rolls back earlier transfers after
  a later failure, preserves stale links when replacement fails, and retains cross-filesystem support.

- OAuth callback paste handling no longer double-encodes URLs, and stopped auth-probe loops cannot
  restart themselves after shutdown.

- Repeated statusline ticks reuse a bounded branch cache instead of spawning an uncached Git process
  every time.

- The release gate now rejects invalid SemVer tags and a mutated prerelease flag; the concentration
  gate rejects masked commands and contradictory ratchet modes; the head-E2E gate rejects unmatched
  head selectors and duplicate stream terminals. All previously reported false green.

### Security

- Client-auth providers reject configured `Authorization` and `x-api-key` headers case-insensitively,
  preventing a splice-held upstream credential from sharing the local management-gate bypass.

- OAuth wrapper overrides are restricted to portable command names; paths, shell syntax, whitespace,
  blank names, and option-like names are rejected before launch.

- The transitive netty floor is raised to 4.2.17.Final (GHSA-8c42-7qj2-3j46, CORS `Vary` cache
  poisoning in `netty-codec-http`); the constraint stays a floor, so a newer ktor-shipped netty still
  wins.

## splice v0.2.0 — reasoning continuity, the cache-drain fix, and every-head login - 2026-08-02

### Changed — BREAKING

- The OpenRouter head's wrapper command is renamed `claudeor` -> `claude-openrouter`, matching
  `claude-grok` / `claude-kimi`. **Existing installs keep a stale `claudeor` symlink**: `install
  --all` links the topology's commands but never prunes one whose name disappeared, so the old
  wrapper survives and resolves to no head. Remove it once: `rm ~/.local/bin/claudeor`.

### Added

- **Gateway-held reasoning cache** (codex provider, default on): each turn's
  `reasoning.encrypted_content` envelopes are held in memory per conversation and replayed on tool
  round-trips, restoring the reasoning continuity the codex CLI gets natively. Retention is
  activity-based with hard caps (256 rounds / 64 MB across a head, whole-conversation eviction);
  the envelopes are opaque ciphertext, never written to disk, and scoped to their conversation so
  concurrent sessions can never receive each other's. `quirks = { reasoning_cache = false }`
  disables it. Documented in SECURITY.md.
- **Deferred tool surface**: responses-dialect heads advertise a small eager slice of the tool
  surface and defer the rest behind a gateway-answered `tool_search` — the model asks, the gateway
  answers from the deferred inventory, and the continuation round is invisible to the client. Cuts
  tens of KB from every upstream request; across a full daemon log, well under 1% of turns needed a
  search round.
- **Responses WebSocket transport** (opt-in, `websocket = true`, default off): rides the upstream
  v2 WebSocket with `previous_response_id` chaining, sending per-round deltas instead of the full
  replay. Cuts wire bytes and prefix drift. Measured NOT to reduce billed input tokens — the
  receipt (`gateway/spikes/results/responses-websocket.md`) is explicit — so this is a latency and
  robustness lever, not a quota one.
- **Mid-stream re-anchoring**: a turn torn by a provider brownout after frames were already
  forwarded is re-anchored upstream and continued instead of failed.
- **Turn-scoped summary dedup**, and continuation rounds no longer re-request reasoning summaries:
  detailed reasoning on every round with zero duplicated summary text.
- **Loop guard**: a circuit breaker for the identical-failed-tool-call pathology, which previously
  burned rounds repeating a call that could never succeed.
- **API-key store and token capture** for api-key heads: `splice key`, and paste-to-store during
  `/login` for providers whose token shape splice knows (today: OpenRouter).
- `/login` now reports its outcome back INTO the session. The sign-in runs detached, so everything
  it printed was lost and the session never learned whether it worked; it writes a one-line receipt
  that the head's `/login` hook reads and consumes on the next prompt. This is the only channel
  that can confirm a kimi login at all — an RFC 8628 device flow has no browser redirect to render
  a page in, which is why opencode and Kilo Code both confirm in-client rather than via a callback.
  Failures are reported too, which is the case that previously said nothing at all.
- The browser login accepts a PASTED redirect URL (or a bare code) on stdin, racing the loopback
  callback. A loopback can simply never arrive — browser on another machine, SSH, a container
  without shared localhost — and the only outcome was a silent five-minute timeout. xAI's own CLI
  accepts both channels for this reason.

### Fixed

- **The prompt-cache drain.** The reasoning cache expired envelopes on ACTIVE conversations, which
  rewrote the replayed prompt prefix mid-conversation and invalidated the provider's prefix cache
  turn after turn — measured at 350,920,932 wasted input tokens across 7,056 turns in one window,
  a 66.6% hit rate against 98.0% (grok) and 96.3% (kimi) on the same daemon
  (`gateway/spikes/results/prompt-cache-drain.md`). Envelopes now expire wholesale on idle
  conversations only; the measured hit rate recovered to ~90%.
- responses-lite turns send `tool_choice=auto` — fixes broken tool-calling on gpt-5.6.
- `tool_search_call.arguments` is emitted as a JSON object, not a string.
- Catalog membership recognizes `[1m]`-suffixed models — unbreaks kimi k3.
- The paste-capture hook is installed ONLY while an api-key head's key is missing. On a configured
  head it was pure downside: a bare `sk-or-…` message was swallowed and stored, silently
  overwriting a working credential, and the message never reached the model — so merely discussing
  a key by pasting one broke the session's auth. The key-missing advertiser was already gated this
  way; the hook that acts on the paste was not.
- `install.sh` now detects a stale `claudeor` symlink left by the rename and prints the one command
  that clears it. It does not delete anything: that bin dir holds links splice did not create. The
  notice is scoped to a symlink pointing at splice's own launch shim, so a user's unrelated
  `claudeor` script is never mentioned.
- The OAuth callback page said "close this tab and head back to your terminal", but `/login` is
  usually invoked from inside a session where there is no terminal to return to. It now names the
  destination, matching what xAI's CLI does ("You can close this window and return to Grok Build").
- `/login` on an api-key head promised "a masked terminal prompt is asking for your key" while
  spawning `<command> login` DETACHED with output to `/dev/null`. Detached means no TTY, so
  `System.console()` was null, the CLI printed its pipe-instead hint into the void, and the
  promised prompt could never appear — the user waited on nothing. A head that can capture a
  pasted token is now told the path that actually works (paste it as a message; splice stores it
  and blocks it before it reaches the model), the residual is stated plainly (the session log on
  disk still records the pasted line), and nothing is spawned.
- `/login` still works for EVERY head — each has its own sign-in path, and being in the topology
  is what makes it supported. Only the wording differs: a head whose token shape splice knows
  gets the in-session paste path; one it does not gets pointed at `<command> login` in a terminal,
  with the reason stated. Capture patterns stay deliberately one-provider-at-a-time (today:
  OpenRouter) — that scoping applies to CAPTURE, never to whether `/login` exists.
- The frozen migration oracle's `--check` mode never compared against the committed fixtures, so
  no behaviour drift could fail it despite being wired as a verification gate. It now diffs fixture
  bytes, the vendored mock's checksum, and the scenario roster in both directions.
- Three ast-grep walls were narrower than their own messages claimed: the cancellation wall
  accepted a type check without the rethrow it demands; the `pkill` wall fired on unrelated string
  concatenation in exec arguments; the silent-`Result`-collapse wall missed `var` bindings.

### Security

- **Wall grants are signed.** The write-time gate that protects `.rules/`, `.claude/hooks/`,
  `.claude/settings.json` and `sgconfig.yml` trusted `.claude/state/walls-grant.json` on sight,
  and that path was not itself walled — so an assistant could write its own grant record and open
  every wall in a single tool call, leaving no git trace (the file is gitignored). "Operator-only
  by construction" held for *issuing* a grant and not for the record the gate *trusts*, which is
  the half that matters.

  Grants are now HMAC-SHA256 signed and the signature is verified before the expiry field is read.
  The key lives outside the repo (`~/.local/state/splice/walls-grant.key`, mode 0600) and is
  created only by `dev/walls-grant/install.sh` — never by the gate, since a verifier that can mint
  its own key proves nothing. The grant record is refused as a tool-write target unconditionally,
  so a grant cannot extend itself. Grants may now be scoped to specific wall paths, and the issuing
  session id is part of the signed payload, so the audit record says who opened a wall and from
  where rather than only when it expires. Every failure mode — missing key, bad signature, expired,
  out of scope — leaves the wall shut.

  Known limitation, deliberately recorded rather than implied away: `Bash` tool calls do not pass
  through the write-time hook, so a key readable by the same process it defends raises the bar
  without sealing it. See the header of `.claude/hooks/lib/walls_grant.py`.

- The `/grant` installer no longer reports a repo carrying the pre-signature gate as already
  installed; it refuses loudly rather than silently leaving a forgeable gate in place. Re-running
  it also no longer revokes an active grant out from under the operator.
- Closed every open CodeQL alert: a measured ReDoS, an unescaped OAuth-callback echo, error
  de-leaking, and the legacy Node log-tail endpoints reflecting exception text — errno plus the
  absolute host path — back to clients. Detail goes to stderr, a fixed string to the wire; the
  Kotlin gateway's own log endpoint was never affected.
- Dependency floors: netty 4.2.16.Final and jackson on the Gradle plugin classpath — the alerts
  Dependabot could not raise PRs for.
- The secret-scan allowlist is now GENERATED from a TOML source. The three `grep -vEf` hazards
  that blinded the scan during review (an unanchored entry, prose acting as a live regex, an
  invalid ERE breaking the whole pattern file) are inexpressible rather than merely detected, and
  a canary self-test guards the generator's output in the gate.

## splice v0.1.1 — release integrity and supported defaults - 2026-07-21

### Fixed

- Launching a head by its wrapper command now works when the command differs from the topology
  key — the starter's supported route (`openrouter` head, `claudeor` command) failed its very
  first launch with "head not launchable". `/launch`, `login`, `install <head>`, and
  `uninstall <head>` accept either name now.
- The release installer now fetches and verifies both the fat JAR and launch shim, fails on
  missing or mismatched assets, rejects dangling wrapper links, and is safe when piped through
  stdin. CI and publication run the same hermetic staged-bundle install test.
- Fresh installs now materialize a supported OpenRouter API-key topology. Codex, Grok, and Kimi
  OAuth implementations in that release require an explicit provider configuration.
- `splice doctor` now reports management-key coverage: a missing mgmt-key while the daemon runs is
  a failure ("admin endpoints will 401") rather than "Everything checks out", and the state dir and
  `daemon.lock` paths are shown for orientation.
- `splice doctor`'s split-brain check no longer vanishes silently when the daemon is up but its
  side can't be read (no mgmt-key or `/api/auth` unreachable) — it now emits an explicit warning
  instead of quietly skipping exactly when the daemon is busiest.
- `splice restart` no longer false-fails when the daemon drops the shutdown connection during a
  graceful teardown: the health poll, not the POST result, decides whether the daemon stopped.
- `splice doctor`/`restart` no longer render a foreign listener's `{"version": null}` as the
  literal string "null" (JsonNull-filtered read).
- `splice doctor`'s daemon and auth sections now resolve the control port and probe `/health` once
  through the injected environment reader, so the hermetic tests no longer depend on an ambient
  local daemon.
- `experiments/cache-replay/real-ab.sh` derives its repo root from its own location instead of a
  hardcoded personal path (the last stray reference to the project's pre-rename directory).

### Added

- The installer now preflights the whole machine before doing anything: platform detection
  (Linux/macOS native, Windows pointed at WSL2 with exact guidance), Java 21+ as a hard
  requirement, and every runtime dependency (curl, python3, node, Claude Code) verified with the
  exact per-package-manager fix — offered interactively with consent, printed otherwise. The
  install finishes by running `splice doctor`, so it ends on a verified state, not a hopeful one.
- README: a requirements table with per-OS fixes, three install paths (release one-liner, from
  source, and a copy-paste prompt that lets a coding agent drive the whole install-and-verify
  loop against `splice doctor`'s fix lines).
- `splice doctor` now actually diagnoses: five sections (prerequisites, installation,
  configuration, daemon, auth) with an actionable fix line under every failing check, and exit 1
  only on real failures. It detects the exported-after-boot trap — an API key visible in the
  shell but not to the running daemon — by comparing both sides.
- `splice restart` — stop the daemon (stale or current) and cold-start it with the invoking
  shell's environment; the documented fix for a key exported after the daemon booted.
- Launching a head whose upstream credentials are absent now warns, naming the missing env var
  (or login command) and the fix, instead of failing silently upstream on the first request.
- The release installer preflights `gh` presence and authentication before downloading anything —
  provenance verification needs an authenticated GitHub CLI, and learning that after the download
  was the worst first-run moment.
- Release bundles and the shaded JAR include the project license, third-party notices, provenance,
  a CycloneDX 1.6 SBOM, and an exact runtime dependency-license inventory. Publication fails on
  unresolved licenses or sidecar/JAR/checksum drift.
- CodeQL, dependency review, artifact provenance attestations, release-version validation, and
  bounded/concurrent CI release jobs.
- Gateway hardening: an 8 MB cap on incoming request bodies, rejected with HTTP 413 when exceeded;
  a bounded request-materialization gate limiting how many requests can be decoded/translated
  concurrently; SSE frame-size limits on data read from upstream; and a cap on upstream
  error-response bodies (64 KB) before they're surfaced to the client.
- Ceilings on configuration values — ports, fold rounds/tier, and max inflight/queued — so
  out-of-range operator or environment input can no longer reach the runtime uncapped.

### Changed

- The ChatGPT, Grok, and Kimi subscription routes are now presented as what they are: the
  primary routes splice was built for, rather than "experimental" afterthoughts. The API-key
  routes remain the zero-config starter.
- Public reasoning language now describes provider-generated summaries without implying access to
  raw, private, or exact chain-of-thought.
- Reasoning replay now ships off. Measurement showed that replay encouraged reuse of thin prior
  thinking; `CLAUDEX_REPLAY_REASONING=1` remains available as an explicit cache-warmth trade-off.
- A rate-limited (429) turn now terminates immediately instead of retrying in-gateway, so the
  client re-sends; a real 429 arms a shared per-account cooldown so concurrent turns fail fast
  together instead of each burning its own retries against the same limited account.

## splice — codex-proxy v35, claudithos removed, renamed from "mythos" - 2026-07-15

Public release under the new name **splice** (was "mythos", which collided with Anthropic's
model line). Two functional changes ship alongside the rename.

### Fixed

- **Compaction re-read the whole transcript cold and drained quota (codex-proxy v35).** The
  stream idle-watchdog was reaping big-context compaction PREFILLS: a ~160k compaction is
  silent for minutes while the backend prefills before its first byte, and the watchdog's
  `streamIdleMs` treated that silence as a zombie and aborted — so every compaction died
  mid-prefill and retried, re-reading the transcript uncached each attempt. The idle abort now
  uses `firstByteTimeoutMs` until the first byte arrives; `streamIdleMs` applies only once
  streaming has actually started. Compaction also inherits the session's own model AND reasoning
  effort — a mismatch on either invalidates the prompt cache.

### Removed

- **The `claudithos` head** (a Claude-on-Claude memory-architecture experiment, port 3098): the
  launcher arm, proxy branch, auth panel, `claudithosMode` config knob, and its tests are gone.
  The stack is now the `claudex`/codex head plus a scaffolded Grok head.

## splice — control server v1 (spliced) - 2026-07-15

Split the dashboard out of the proxies into a centralized control plane. Each head
(codex, grok later) used to serve its own single-head `/dashboard` +
`/mgmt`; now a loopback control server (`spliced`, :3096) hosts ONE dashboard over
an aggregated `/api/*` spanning every head. The heads keep `/mgmt` as their machine
interface but no longer serve a dashboard.

### Added

- **`spliced` control server** (`src/control-server.mjs` + `src/control/api.mjs`,
  loopback :3096, `controlPort`). Bearer-guarded `/api/*` sharing the proxies'
  mgmt-key: `GET /api/status`, `GET /api/heads` + `POST /api/heads/:head/{start,
  stop,restart}` (full lifecycle), `GET|PATCH /api/config`, `GET /api/usage`,
  `GET /api/auth` + `POST /api/auth/:head/{refresh,login}`, `GET /api/compact`,
  `GET /api/logs/:head`. Serves the dashboard at `/`. Mints the mgmt-key at boot.
- **Shared head lifecycle** (`launcher/heads.mjs`): the head registry + health /
  spawn / kill / start / stop / restart, used by BOTH the CLI launcher and the
  control server so process logic is never forked. Control-side spawns strip the
  config env (`CONFIG_ENV_NAMES`) so `config.json` — the dashboard's source of
  truth — wins over a stale inherited value.
- **Soft-warn usage caps** (`src/usage/warn.mjs`; `usageWarnPct` /
  `usageWarnTokens5h`): never blocks. Classifies each head's headroom ok / warn /
  critical from the rate-limit remaining, with a 5h output-token cap as fallback.
  Feeds a dashboard banner and a subtle statusline `⚠` that stays hidden until near
  the cap.
- **`claudex dashboard`**: ensures spliced is up and
  opens the browser; the launcher also best-effort-starts spliced alongside any
  head launch (non-blocking).
- **Multi-head dashboard** (`webui/`, FSD React): a fleet of instrument head-plates
  (live status + start / stop / restart with a two-step confirm on the destructive
  actions + a per-head usage meter tinted by warn level), per-head auth cards
  (codex Sign-in-with-ChatGPT + refresh, claude plain-claude + refresh), and a
  shared config editor with layer provenance and guided enum dropdowns. Retired the
  single-head models / reasoning / proxy-status surfaces.

### Changed

- `codex-proxy.mjs` no longer serves `/dashboard` (it
  moved to spliced); it keeps `/mgmt`. Dashboard config changes reach a running
  head through a `PATCH /mgmt/config` fan-out (the runtime layer, which beats the
  launcher's env pin), falling back to writing the config file when no head is up.

## splice — codex-proxy v31 - 2026-07-14

Codex-parity prompt-cache warmth for the claudex head. Native Codex keeps the
backend prompt cache hot with three coupled mechanisms
(`codex-rs/core/src/client.rs`): `include=["reasoning.encrypted_content"]`,
`store=false`, and a stable `prompt_cache_key = session_id`. claudex sent none of
them, so the growing conversation prefix went cold every turn (no cached-input
discount, higher latency) — acute once account-level limits made cache hits
load-bearing. This ships all three, on by default, without abandoning the mirror.

### Added

- **Reasoning replay (default on, `replayReasoning`).** The backend's encrypted
  reasoning rides through the transcript as a `redacted_thinking` block
  (`reasoning/replay.mjs`, tag `splice-reasoning` v1) and decodes back into a
  Responses `reasoning` input item, so the reasoning KV / prompt-cache prefix
  stays byte-stable across the agent loop. Emitted on both response paths — the
  stream path via the sole SSE emitter (`addRedactedThinking`), the non-stream
  path via `translateResponse`. Never on compact. Opt out with
  `CLAUDEX_REPLAY_REASONING=0` to run the pure distillation loop.
- **`prompt_cache_key` (always on).** `splice-<sha256(first user message)[:32]>`
  — keyed on the first user message: stable for the whole conversation and immune
  to per-turn system-reminder drift (keying on the system prompt would bust it
  every turn). Routes every turn of one conversation to the same cache shard.
- Both run ALONGSIDE the mirror (L2), unchanged: the mirror carries the reasoning
  SUMMARY to the model as readable text; replay carries the ENCRYPTED reasoning to
  the backend. Different channels — they compose, they don't compete.

### Changed

- **L1 retired.** The former locked invariant "no reasoning-item replay" (the bet
  that per-turn amnesia beat replay on the hardest multi-day work) is overturned:
  the power came from the mirror, not from dropping replay. The
  `l1-no-reasoning-replay` wall + rule-test are removed; the L1 behavioral test is
  replaced by a replay round-trip / gating / cache-key / both-channels-coexist
  suite; orchestrator routing tests re-pointed to L3. The pure-amnesia A/B is
  preserved behind the flag.
- `replayReasoning` is a hot-applicable config knob (defaults ← file ← env
  `CLAUDEX_REPLAY_REASONING` ← runtime PATCH) — toggle the A/B live from the
  dashboard, no restart.

### Gates

- server 87/87 (10 new), gate:rules 10 rules green, test:hooks 13/13, webui
  lint+test+build green, `webui/dist` byte-unchanged.

## splice — codex-proxy v30 - 2026-07-13

Productization: the 1783-line `codex-proxy.mjs` v29 (which diverged through six
local versions in two days inside a forked npm package) becomes this repo —
npm workspaces `server/` + `webui/`, 18 server modules, walls-first ast-grep
policy, a bearer-guarded management plane, and a committed single-file
dashboard. All three autocompact locks fixed as part of the move:

### Fixed

- **Autocompact trigger never fired** (binary trace, Claude Code v2.1.207):
  Claude Code hard-skips autocompact when it cannot resolve an explicit
  context window for the model; no claudex model matches its tables and only
  `CLAUDE_CODE_AUTO_COMPACT_WINDOW` un-gates it. The launcher now sets it
  (resolved window, floor 100k) alongside the kept
  `CLAUDE_CODE_MAX_CONTEXT_TOKENS` and `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=85`.
- **Overflow on the SSE path** (Eli P0): the "prompt is too long" rewrite
  existed only on the HTTP non-ok path; live failures arrive via SSE
  `response.failed` and became raw `api_error` (hard error, no compaction).
  ONE `classifyUpstreamFailure(kind, text, status)` now serves both
  transports; overflow order also fixes v29's auth-regex shadowing of
  wordings containing "tokens".
- **Compaction detection inverted** (Eli P0/P1 + trace): real compaction
  requests DO carry tools; v29's `tools.length>0 → false` guard rejected the
  real shape, and a tooled compaction could answer with `tool_use`, gating
  the promote-to-text net off. `classifyCompact` is now a tools-agnostic
  positive-marker classifier (the verbatim summarizer prompt); on detect the
  builder strips tools upstream. A shadow classifier logs
  `{has_marker, tool_count, sys_len}` on EVERY request, and a canary test
  pins the marker sentence.
- Dead claude-* passthrough is an honest error; every `listen()` binds
  127.0.0.1 explicitly; context windows resolve exact-match + explicit prefix
  rules (no substring fuzz); `body.__claudex*` magic props replaced by the
  pure `{req, meta}` translation contract; mirror/promote/honesty thresholds
  named in one place; kill-stale is a pgrep/lsof loop excluding own PID
  (never `pkill -f`), and a surviving wrong-version proxy is a loud failure,
  never the EADDRINUSE silent-exit version-loop.

### Added

- Layered hot config (defaults ← state file ← env ← runtime PATCH) read per
  request; `/mgmt/*` management plane on both proxies (status, config
  round-trip, usage, compact + shadow, auth + refresh, logs, models);
  `/dashboard` serving the committed single-file WebUI (React 19 + Zustand,
  FSD lint-enforced, Torad tokens, Reasoning + Compaction instrument pages).
- Launchers: `ensure-proxy` (health/version handshake), `assemble-env`
  (section-aware TOML replacing the sed that leaked [profile] values;
  models_cache + ceiling resolution), `prepare-config` (config-dir isolation
  half of claudex-prepare), thin `bin/claudex` exec-env shim. Proxy logs move to
  `~/.claude-codex/logs/` (out of /tmp).
- Walls: single Python hook orchestrator routing every write-time policy to
  ast-grep rules (L1/L2/L3 structural invariants, loopback bind, magic
  props, pkill, FSD fetch gate, em-dash copy gate, CSS token scales), same
  rules re-run by `npm run gate:rules` and CI.

### Left behind (deliberate)

`claude-wrapper` (pinned proxy v6 vs real v29 — abandoned), `set-model-mode`
(SMELTER-coupled; only the pure config-isolation helpers were extracted),
`build-codex-server` (bundled a nonexistent file), `lib/auto-update` (npm
self-update + network call per launch), `bin/claude-codex`.

---

## Inherited history (codex-for-claude-code local fork)

> Provenance + external upstream license clearance for this inherited lineage: see [PROVENANCE.md](PROVENANCE.md).

## local codex-proxy v29 - 2026-07-13

### Fixed

- **Large-context and compaction requests aborted mid-prefill** ("operation was aborted", 31× in one session vs 5 genuine over-window). The v25 first-byte timeout was 90s, but a near-window prompt or a compaction re-sending the whole transcript legitimately takes minutes to prefill before the first token. Raised to **300s** (`CLAUDEX_FIRST_BYTE_TIMEOUT_MS`), still catching a truly-dead connect. This was the dominant cause of "autocompact not working" — the compaction request itself was being killed before it could respond.

### Changed (launcher)

- **Reverted the reported context window 220k → real 272k, kept autocompact at 85%.** The over-window 502s were caused by Claude Code's **autocompact thrashing guard** (it disables autocompact after the context refills within 3 turns of a compact, 3× in a row — triggered by large tool-result reads), not by the threshold. A *lower* reported window fires autocompact more often, leaving fewer turns before a big read refills it → more thrashing → autocompact disabled → session grows unbounded → 502. Reporting the real 272k fires at ~231k with ~4 turns of headroom, above the 3-turn thrash trigger.

## local codex-proxy v28 - 2026-07-13

### Fixed

- **Only one codex model (the pinned default) showed in the `/model` picker.** `additionalModelOptionsCache` is replaced wholesale on every bootstrap and `ANTHROPIC_CUSTOM_MODEL_OPTION` is singular; the only durable way to list N custom models is **gateway model discovery**. The proxy serves `GET /v1/models` (launcher sets `CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY=1`); Claude Code drops ids not matching `/^(claude|anthropic)/i`, so codex ids are wrapped (`gpt-5.6-luna` → `claude-codex--gpt-5.6-luna`) and unwrapped on the way in. The pinned default is excluded from discovery to avoid a duplicate.

## local codex-proxy v27 - 2026-07-13

### Fixed

- **Claude Code `/effort` picker was ignored.** The picker arrives as `thinking.budget_tokens`; the resolution chain put env (config.toml `model_reasoning_effort = "max"`) above it. Reordered: explicit body effort field > harness picker > env fallback > `high`.
- **`SHOW_REASONING=text` force-raised a deliberate low pick.** The visibility floor now only bumps `none`/`minimal`/absent → `low` and never overrides an explicit selection.

## local codex-proxy v26 - 2026-07-13

### Fixed

- **Compaction "response exceeded N output token maximum"**: the ChatGPT backend rejects token-limit params so generation is uncapped, and reasoning tokens count in `output_tokens` — an undetected max-effort compaction tripped Claude Code's output guard. Reported `output_tokens` is now clamped to the client's `max_tokens`, with a stderr diagnostic on every clamp.

## local codex-proxy v25 - 2026-07-13

### Fixed

- **Multi-part reasoning summaries**: per-part `done` events closed the thinking block after part 1 (protocol violation; visible thinking truncated). Blocks close only on `output_item.done`; parts separated with blank lines in the thinking stream and the mirror.
- **Honest failures**: `response.failed`/`response.error`, idle-aborted streams, and streams ending without `response.completed` emit an SSE `error` event instead of a clean empty `end_turn`. Empty compacts and fully-empty completions are errors too.
- **Wire framing**: `res.end()` in the same tick as a corked SSE write put the terminal chunk before the buffered frame (raw socket capture). All stream ends drain the cork queue first.
- **Client abort**: `AbortController.abort()` replaces `body.cancel()` (which rejects unhandled under an active reader lock and leaves upstream streaming).
- **Non-stream path** collapsed onto the shared translator: fixes per-chunk UTF-8 corruption, `name: undefined` tool calls, and function_calls missing from final-output harvest.
- **Compact detection**: removed the tertiary "huge toolless dump" heuristic (misfired on WebFetch-style utility calls).
- **State files** moved from `$CWD/.smt/state` to `~/.claude-codex/state/`.

### Added

- Image passthrough (incl. images inside `tool_result`); `[document omitted]` markers; 401 single-flight OAuth refresh via the Codex CLI client id; first-byte timeout; `response.incomplete` → `stop_reason: max_tokens`; context-overflow errors rewritten to Anthropic's "prompt is too long" phrasing; the 11-test behavior suite.

### Deliberate non-goals (operator-locked)

- **Reasoning-item replay (`include: ["reasoning.encrypted_content"]`) is intentionally NOT implemented.** The no-replay + mirror configuration forces re-derivation from transcript evidence at every tool boundary while distilled conclusions persist via the mirrored summaries — operator A/B experience shows this outperforming native Codex CLI (which replays) on hard multi-day debugging. Do not "fix" this. Revisit only as an explicit, measured A/B on disposable tasks.
