// Add a backend from the console: `splice add` over HTTP (V4-220 item 3, AddRoutes.kt, #291).
//
// THE DAEMON DECIDES, THE FORM ASKS. Every step is one of the CLI's, run by the daemon on one open
// add: the profile's candidate (AddPrepare), the sign-in, the CLI's own checks (AddChecks), and the
// save, which runs the checks again, writes splice.toml, links the wrapper and takes the restart
// through the console's draining restart. Every answer is the add's whole view, so the form holds
// that view and never infers a state the daemon did not report.
//
// ONE FLOW, THREE STATES: the profile and what it asks, then the open add (who it is, how it signs
// in, its checks, save or discard), then what the save wrote and the restart it took. An open add is
// read again while the form is up, since a sign-in or a stored key lands off the form. An add the
// form leaves unsaved is the daemon's to evict (AddConsole keeps the newest 64), so closing the
// panel sends nothing; Discard closes one on purpose and prints a failure like any write.
//
// A key-signed head's key is stored with the Accounts key form itself (features/api-key), and a
// login's code or link prints with the account login's own ticket: one control per job.
import { useEffect, useRef, useState } from 'react';
import { discardAdd, fetchAddProfiles, openAdd, readAdd, saveAdd, signInAdd, verifyAdd } from '@entities/add';
import type { AddChecksFailed, AddCheck, AddProfile, AddView } from '@entities/add';
import { fetchKeys, useKeys } from '@entities/auth';
import { fetchHeads, useHeads } from '@entities/heads';
import { runPlayground } from '@entities/playground';
import { LoginTicket, pollDuringLogin, useLoginPage } from '@features/account-login';
import { ApiKeyForm } from '@features/api-key';
import { Blank, Choice, Confirm, Copy, Fault, Input, Key } from '@shared/controls';
import { ABSENT, poll } from '@shared/lib';
import { Badge, InfoTip, KeyValue, Section } from '@shared/ui';
import { EMPTY_ROW, autoSaveTarget, draftFor, firstDraft, live, ready, requestOf, tryReply } from './model';
import type { AddDraft, ModelRow } from './model';
import { H, S, SIGN_IN_BY } from './strings';
import './add-backend.css';

/** An open add's credential and sign-in move off the form, so it is read again this often. */
const POLL_MS = 2500;

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/** The check rows a verify or a save ran, each passed or failed with the daemon's detail. */
export function CheckRows({ checks }: { checks: readonly AddCheck[] }) {
  return (
    <ul className="myx-add-checks">
      {checks.map((check) => (
        <li key={check.name} className="myx-add-check">
          <Badge tone={check.ok ? 'ok' : 'danger'} quiet>{check.ok ? S.passed : S.failed}</Badge>
          <span className="myx-add-check-name">{check.name}</span>
          <span className="myx-add-check-detail">{check.detail}</span>
        </li>
      ))}
    </ul>
  );
}

function ModelRows({ rows, onChange }: { rows: readonly ModelRow[]; onChange: (rows: ModelRow[]) => void }) {
  const set = (at: number, next: ModelRow) => onChange(rows.map((row, index) => (index === at ? next : row)));
  return (
    <div className="myx-add-models">
      {rows.map((row, at) => (
        // A row is its position: its id is typed into it, so the id cannot key it.
        <div className="myx-add-row" key={at}>
          <Input label={S.modelId} value={row.id} onChange={(id) => set(at, { ...row, id })} w={24} />
          <Input label={S.window} value={row.window} onChange={(window) => set(at, { ...row, window })} numeric w={10} />
        </div>
      ))}
      <div className="myx-add-row">
        <Key onClick={() => onChange([...rows, EMPTY_ROW])}>{S.addModel}</Key>
      </div>
    </div>
  );
}

/** The profile and what it asks. A blank field sends nothing, so the profile's default holds. */
export function ProfileForm({ profiles, draft, onDraft, busy, onOpen, pinned = false }: {
  profiles: readonly AddProfile[];
  draft: AddDraft;
  onDraft: (draft: AddDraft) => void;
  busy: boolean;
  onOpen: () => void;
  pinned?: boolean;
}) {
  const profile = profiles.find((each) => each.name === draft.profile) ?? null;
  const key = draft.name.trim() === '' ? profile?.head_key ?? '' : draft.name.trim();
  // The wrapper's default: the profile's own command, else `claude-<key>` (AddProfile.command).
  const command = profile === null ? '' : profile.command !== '' ? profile.command : key === '' ? '' : `claude-${key}`;
  return (
    <div className="myx-add">
      <div className="myx-add-row">
        {pinned ? <p className="myx-add-note">{profile?.summary ?? H.unavailable}</p> : (
          <>
            <Choice label={S.profile} value={draft.profile} options={profiles.map((each) => ({ value: each.name, label: each.name }))} onChange={(name) => onDraft(draftFor(name))} w={20} />
            {profile === null ? null : <InfoTip text={profile.summary} label={S.aboutProfile} />}
          </>
        )}
      </div>
      <Input label={S.name} value={draft.name} onChange={(name) => onDraft({ ...draft, name })} placeholder={profile?.head_key ?? ''} w={24} />
      {profile?.asks.includes('base_url') === true ? (
        <Input label={S.baseUrl} value={draft.baseUrl} onChange={(baseUrl) => onDraft({ ...draft, baseUrl })} w={32} />
      ) : null}
      <Input label={S.command} value={draft.command} onChange={(next) => onDraft({ ...draft, command: next })} placeholder={command} w={24} />
      {profile?.asks.includes('models') === true ? (
        <Section title={S.models}>
          <ModelRows rows={draft.models} onChange={(models) => onDraft({ ...draft, models })} />
        </Section>
      ) : null}
      <div className="myx-add-row">
        <Key disabled={busy || !ready(draft, profile)} busy={busy} onClick={onOpen}>{S.open}</Key>
      </div>
    </div>
  );
}

/** How the open add's head proves who it is, and the control that does it. */
function SignIn({ view, busy, onSignIn }: { view: AddView; busy: boolean; onSignIn: () => void }) {
  const keyEnv = view.key_env;
  const keys = useKeys((state) => state.data);
  useEffect(() => {
    if (keyEnv !== null) void fetchKeys();
  }, [keyEnv]);

  if (view.sign_in_by === 'none') return <p className="myx-add-note">{view.profile === 'local' ? H.localNoKey : H.forwarded}</p>;
  if (view.sign_in_by === 'key' && keyEnv !== null) {
    return (
      <>
        <p className="myx-add-note">{H.key(keyEnv)}</p>
        <ApiKeyForm name={keyEnv} stored={keys?.keys.find((each) => each.name === keyEnv)?.stored === true} />
      </>
    );
  }
  const login = view.sign_in;
  const running = login !== null && (login.state === 'starting' || login.state === 'waiting');
  return (
    <>
      <div className="myx-add-row">
        <Key disabled={busy || running || view.credential.present} onClick={onSignIn}>{S.signIn}</Key>
        <InfoTip text={H.login} label={S.aboutSignIn} />
      </div>
      {login === null ? null : <LoginTicket status={login} />}
      {login?.failure_reason == null ? null : <Fault message={login.failure_reason} />}
    </>
  );
}

/** The open add: who it will be, how it signs in, its checks, and save or discard. */
export function OpenAdd({ view, checks, busy, readFault = null, saveFault = null, onSignIn, onVerify, onSave, onDiscard }: {
  view: AddView;
  checks: readonly AddCheck[] | null;
  busy: boolean;
  /** The last poll of this add failed, in the daemon's words: the view below is the last one read. */
  readFault?: string | null;
  saveFault?: string | null;
  onSignIn: () => void;
  onVerify: (liveTurn: boolean) => void;
  onSave: () => void;
  onDiscard: () => void;
}) {
  const present = view.credential.present;
  return (
    <div className="myx-add">
      {readFault === null ? null : <Fault message={readFault} />}
      <KeyValue rows={[
        [S.head, view.key],
        [S.command, view.command],
        [S.signsIn, view.profile === 'local' && view.sign_in_by === 'none' ? S.noKey : SIGN_IN_BY[view.sign_in_by] ?? view.sign_in_by],
        [S.baseUrl, view.base_url ?? ABSENT],
        [S.models, view.models.length === 0 ? ABSENT : view.models.map((model) => model.id).join(', ')],
        [S.credential, view.profile === 'local' && view.sign_in_by === 'none'
          ? S.noKey
          : <Badge key="credential" tone={present ? 'ok' : 'warn'}>{present ? S.present : S.missing}</Badge>],
      ]} />
      {present || view.sign_in_by === 'none' ? null : <p className="myx-add-note">{view.credential.detail}</p>}
      <SignIn view={view} busy={busy} onSignIn={onSignIn} />
      <Section title={S.checks} info={{ text: H.checks, label: S.aboutChecks }}>
        <div className="myx-add-row">
          <Key disabled={busy} onClick={() => onVerify(false)}>{S.runChecks}</Key>
          {view.sign_in_by === 'key' ? (
            <>
              <Key disabled={busy} onClick={() => onVerify(true)}>{S.liveCheck}</Key>
              <InfoTip text={H.live} label={S.liveCheck} />
            </>
          ) : null}
        </div>
        {checks === null ? null : <CheckRows checks={checks} />}
      </Section>
      <div className="myx-add-row">
        {view.sign_in_by === 'login' ? (
          saveFault === null ? (view.credential.present && (view.sign_in === null || view.sign_in.state === 'signed_in' || view.sign_in.state === 'live_after_restart')
            ? <p className="myx-add-note" role="status">{H.finishConnection}</p> : null)
            : <Key disabled={busy} onClick={onSave}>{S.retryConnect}</Key>
        ) : <Confirm label={S.save} confirmLabel={S.saveArmed} busy={busy} onConfirm={onSave} />}
        <Key disabled={busy} onClick={onDiscard}>{S.discard}</Key>
      </div>
    </div>
  );
}

function TryPlan({ head }: { head: string }) {
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ model: string; text: string } | { error: string } | null>(null);
  const tryIt = () => {
    setBusy(true);
    setResult(null);
    void runPlayground(head, H.tryPrompt).then(
      (wire) => setResult(tryReply(wire)),
      (error: unknown) => setResult({ error: messageOf(error) }),
    ).finally(() => setBusy(false));
  };
  return (
    <div className="myx-add-try">
      <Key busy={busy} onClick={tryIt}>{S.tryIt}</Key>
      {result === null ? null : 'error' in result ? <Fault message={result.error} /> : (
        <>
          <KeyValue rows={[[S.modelUsed, result.model], [S.reply, result.text]]} />
          <p className="myx-add-note">{H.tryCost}</p>
        </>
      )}
    </div>
  );
}

/** What the save wrote, the wrapper it linked, and whether that head is actually live. */
export function SavedAdd({ view, live: headLive = false }: { view: AddView; live?: boolean }) {
  const saved = view.saved;
  if (saved === null) return null;
  const { restart, wrapper } = saved;
  const ready = headLive && wrapper.linked;
  const plan = H.planName(view.profile);
  return (
    <div className="myx-add">
      <KeyValue rows={[
        [S.head, view.key],
        [S.written, saved.path],
        [S.wrapper, wrapper.linked ? <Badge key="wrapper" tone="ok">{S.linked}</Badge> : ABSENT],
        [S.restart, <Badge key="restart" tone={restart.status === 'refused' ? 'warn' : 'accent'}>{restart.status}</Badge>],
      ]} />
      {wrapper.linked ? (
        <p className="myx-add-command" role="status">
          <span>{ready ? S.connected(plan) : S.savedPlan(plan)}. {ready ? H.runCommand(view.command) : H.launch}</span>
          <code>{view.command}</code>
          <Copy value={view.command} />
        </p>
      ) : null}
      {wrapper.error === undefined ? null : <Fault message={wrapper.error} />}
      {ready ? <TryPlan head={view.key} /> : restart.status === 'draining'
        ? <p className="myx-add-note" role="status">{H.draining}</p>
        : restart.status === 'waiting' ? <p className="myx-add-note" role="status">{H.waiting(restart.compactions?.length ?? 0)}</p>
          : <>
            <p className="myx-add-note" role="status">{H.restartManually}</p>
            {restart.error === undefined ? null : <Fault message={restart.error} />}
          </>}
    </div>
  );
}

/** One read of the open add, as the poll keeps it: the view it read, which clears the read's fault,
 *  or the read's failure in the daemon's words, over the view the panel holds (V4-306). */
export function readOpenAdd(id: string, onView: (view: AddView) => void, onFault: (fault: string | null) => void): Promise<void> {
  return readAdd(id).then(
    (view) => {
      onView(view);
      onFault(null);
    },
    (err: unknown) => onFault(messageOf(err)),
  );
}

/** A save owns the view only until it settles; failed saves must resume fresh credential reads. */
export function polledAdd(current: AddView | null, next: AddView, saving: boolean): AddView | null {
  return saving || current === null || current.id !== next.id || current.saved !== null ? current : next;
}

export function AddBackend({ onDone, initialProfile }: { onDone: () => void; initialProfile?: string }) {
  const [profiles, setProfiles] = useState<AddProfile[] | null>(null);
  const [draft, setDraft] = useState<AddDraft | null>(null);
  const [view, setView] = useState<AddView | null>(null);
  const [checks, setChecks] = useState<AddCheck[] | null>(null);
  const [fault, setFault] = useState<string | null>(null);
  const [readFault, setReadFault] = useState<string | null>(null);
  const [loginError, setLoginError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const saveAttempt = useRef<string | null>(null);
  const saveInFlight = useRef(false);
  const heads = useHeads((state) => state.data);
  const openPage = useLoginPage(view?.sign_in ?? null, loginError);

  useEffect(() => {
    fetchAddProfiles().then(
      (rows) => {
        setProfiles(rows);
        setDraft(firstDraft(rows, initialProfile));
      },
      (err: unknown) => setFault(messageOf(err)),
    );
  }, [initialProfile]);

  // Read the open add again while it is unsaved: a failed read keeps the view it has and says so, and
  // the next good read clears it. Its own fault, because a good read must not clear a write's.
  const openId = live(view) && view !== null ? view.id : null;
  const loginActive = view?.sign_in?.state === 'starting' || view?.sign_in?.state === 'waiting';
  useEffect(() => {
    if (openId === null) return;
    const read = () => readOpenAdd(openId, (next) => {
      const saving = saveInFlight.current;
      setView((current) => polledAdd(current, next, saving));
    }, setReadFault);
    return loginActive ? pollDuringLogin(read, POLL_MS) : poll(read, POLL_MS);
  }, [openId, loginActive]);

  const run = (work: Promise<void>) => {
    setBusy(true);
    setFault(null);
    work.catch((err: unknown) => setFault(messageOf(err))).finally(() => setBusy(false));
  };
  const settle = (answer: { view: AddView } | { failed: AddChecksFailed }) => {
    if ('view' in answer) {
      setView(answer.view);
      setChecks(answer.view.checks);
    } else {
      setChecks(answer.failed.checks);
      setFault(answer.failed.error);
    }
  };

  const autoSave = autoSaveTarget(view, saveAttempt.current);
  useEffect(() => {
    if (autoSave === null || saveAttempt.current === autoSave) return;
    saveAttempt.current = autoSave;
    saveInFlight.current = true;
    setBusy(true);
    setFault(null);
    void saveAdd(autoSave).then(settle, (error: unknown) => setFault(messageOf(error)))
      .finally(() => {
        saveInFlight.current = false;
        setBusy(false);
      });
  }, [autoSave]);

  useEffect(() => {
    if (view?.saved !== null && view?.saved !== undefined) void fetchHeads();
  }, [view?.saved]);

  if (profiles === null || draft === null) return fault === null ? <Blank strips={3} /> : <Fault message={fault} />;

  let body;
  if (view === null) {
    body = (
      <ProfileForm
        profiles={profiles}
        draft={draft}
        onDraft={setDraft}
        pinned={initialProfile !== undefined}
        busy={busy}
        onOpen={() => run(openAdd(requestOf(draft)).then((opened) => {
          setView(opened);
          setChecks(opened.checks);
        }))}
      />
    );
  } else if (view.saved === null) {
    const id = view.id;
    body = (
      <OpenAdd
        view={view}
        checks={checks}
        busy={busy}
        readFault={readFault}
        saveFault={fault}
        onSignIn={() => {
          openPage();
          saveAttempt.current = null;
          setLoginError(null);
          run(signInAdd(id).then(setView, (err: unknown) => {
            setLoginError(messageOf(err));
            throw err;
          }));
        }}
        onVerify={(liveTurn) => run(verifyAdd(id, liveTurn).then(settle))}
        onSave={() => {
          saveInFlight.current = true;
          run(saveAdd(id).then(settle).finally(() => { saveInFlight.current = false; }));
        }}
        onDiscard={() => run(discardAdd(id).then(onDone))}
      />
    );
  } else {
    body = <SavedAdd view={view} live={heads?.some((head) => head.key === view.key && head.running && head.healthy) ?? false} />;
  }

  return (
    <div className="myx-add-flow">
      {body}
      {fault === null ? null : <Fault message={fault} />}
    </div>
  );
}
