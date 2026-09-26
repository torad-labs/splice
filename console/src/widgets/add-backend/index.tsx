// Add a backend from the console: `splice add` over HTTP (V4-220 item 3, AddRoutes.kt, #291).
//
// THE DAEMON DECIDES, THE FORM ASKS. Every step is one of the CLI's, run by the daemon on one open
// add: the profile's candidate (AddPrepare), the sign-in, the CLI's own checks (AddChecks), and the
// save, which runs the checks again, writes splice.toml, links the wrapper and takes the restart
// through the console's draining restart. Every answer is the add's whole view, so the form holds
// that view and never infers a state the daemon did not report.
//
// ONE PANEL, THREE STATES: the profile and what it asks, then the open add (who it is, how it signs
// in, its checks, save or discard), then what the save wrote and the restart it took. An open add is
// read again while the form is up, since a sign-in or a stored key lands off the form. An add the
// form leaves unsaved is the daemon's to evict (AddConsole keeps the newest 64), so closing the
// panel sends nothing; Discard closes one on purpose and prints a failure like any write.
//
// A key-signed head's key is stored with the Accounts key form itself (features/api-key), and a
// login's code or link prints with the account login's own ticket: one control per job.
import { useEffect, useState } from 'react';
import { discardAdd, fetchAddProfiles, openAdd, readAdd, saveAdd, signInAdd, verifyAdd } from '@entities/add';
import type { AddChecksFailed, AddCheck, AddProfile, AddView } from '@entities/add';
import { fetchKeys, useKeys } from '@entities/auth';
import { LoginTicket } from '@features/account-login';
import { ApiKeyForm } from '@features/api-key';
import { Blank, Choice, Confirm, Fault, Input, Key } from '@shared/controls';
import { ABSENT, poll } from '@shared/lib';
import { Badge, InfoTip, KeyValue, Section } from '@shared/ui';
import { EMPTY_ROW, draftFor, live, ready, requestOf } from './model';
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
export function ProfileForm({ profiles, draft, onDraft, busy, onOpen }: {
  profiles: readonly AddProfile[];
  draft: AddDraft;
  onDraft: (draft: AddDraft) => void;
  busy: boolean;
  onOpen: () => void;
}) {
  const profile = profiles.find((each) => each.name === draft.profile) ?? null;
  const key = draft.name.trim() === '' ? profile?.head_key ?? '' : draft.name.trim();
  // The wrapper's default: the profile's own command, else `claude-<key>` (AddProfile.command).
  const command = profile === null ? '' : profile.command !== '' ? profile.command : key === '' ? '' : `claude-${key}`;
  return (
    <div className="myx-add">
      <div className="myx-add-row">
        <Choice label={S.profile} value={draft.profile} options={profiles.map((each) => ({ value: each.name, label: each.name }))} onChange={(name) => onDraft(draftFor(name))} w={20} />
        {profile === null ? null : <InfoTip text={profile.summary} label={S.aboutProfile} />}
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

  if (view.sign_in_by === 'none') return <p className="myx-add-note">{H.forwarded}</p>;
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
        <InfoTip text={H.login} label={S.signIn} />
      </div>
      {login === null ? null : <LoginTicket status={login} />}
      {login?.failure_reason == null ? null : <Fault message={login.failure_reason} />}
    </>
  );
}

/** The open add: who it will be, how it signs in, its checks, and save or discard. */
export function OpenAdd({ view, checks, busy, readFault = null, onSignIn, onVerify, onSave, onDiscard }: {
  view: AddView;
  checks: readonly AddCheck[] | null;
  busy: boolean;
  /** The last poll of this add failed, in the daemon's words: the view below is the last one read. */
  readFault?: string | null;
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
        [S.signsIn, SIGN_IN_BY[view.sign_in_by] ?? view.sign_in_by],
        [S.baseUrl, view.base_url ?? ABSENT],
        [S.models, view.models.length === 0 ? ABSENT : view.models.map((model) => model.id).join(', ')],
        [S.credential, <Badge key="credential" tone={present ? 'ok' : 'warn'}>{present ? S.present : S.missing}</Badge>],
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
        <Confirm label={S.save} confirmLabel={S.saveArmed} busy={busy} onConfirm={onSave} />
        <Key disabled={busy} onClick={onDiscard}>{S.discard}</Key>
      </div>
    </div>
  );
}

/** What the save wrote, the wrapper it linked, and the restart it took. */
export function SavedAdd({ view }: { view: AddView }) {
  const saved = view.saved;
  if (saved === null) return null;
  const { restart, wrapper } = saved;
  return (
    <div className="myx-add">
      <KeyValue rows={[
        [S.head, view.key],
        [S.written, saved.path],
        [S.wrapper, wrapper.linked ? <Badge key="wrapper" tone="ok">{S.linked}</Badge> : ABSENT],
        [S.restart, <Badge key="restart" tone={restart.status === 'refused' ? 'warn' : 'accent'}>{restart.status}</Badge>],
      ]} />
      {wrapper.error === undefined ? null : <Fault message={wrapper.error} />}
      {restart.status === 'draining' ? <p className="myx-add-note" role="status">{H.draining}</p>
        : restart.status === 'waiting' ? <p className="myx-add-note" role="status">{H.waiting(restart.compactions?.length ?? 0)}</p>
        : <Fault message={restart.error ?? restart.status} />}
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

export function AddBackend({ onDone }: { onDone: () => void }) {
  const [profiles, setProfiles] = useState<AddProfile[] | null>(null);
  const [draft, setDraft] = useState<AddDraft | null>(null);
  const [view, setView] = useState<AddView | null>(null);
  const [checks, setChecks] = useState<AddCheck[] | null>(null);
  const [fault, setFault] = useState<string | null>(null);
  const [readFault, setReadFault] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    fetchAddProfiles().then(
      (rows) => {
        setProfiles(rows);
        setDraft(draftFor(rows[0]?.name ?? ''));
      },
      (err: unknown) => setFault(messageOf(err)),
    );
  }, []);

  // Read the open add again while it is unsaved: a failed read keeps the view it has and says so, and
  // the next good read clears it. Its own fault, because a good read must not clear a write's.
  const openId = live(view) && view !== null ? view.id : null;
  useEffect(() => {
    if (openId === null) return;
    return poll(() => readOpenAdd(openId, setView, setReadFault), POLL_MS);
  }, [openId]);

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

  if (profiles === null || draft === null) return fault === null ? <Blank strips={3} /> : <Fault message={fault} />;

  let body;
  if (view === null) {
    body = (
      <ProfileForm
        profiles={profiles}
        draft={draft}
        onDraft={setDraft}
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
        onSignIn={() => run(signInAdd(id).then(setView))}
        onVerify={(liveTurn) => run(verifyAdd(id, liveTurn).then(settle))}
        onSave={() => run(saveAdd(id).then(settle))}
        onDiscard={() => run(discardAdd(id).then(onDone))}
      />
    );
  } else {
    body = <SavedAdd view={view} />;
  }

  return (
    <div className="myx-add-flow">
      {body}
      {fault === null ? null : <Fault message={fault} />}
    </div>
  );
}
