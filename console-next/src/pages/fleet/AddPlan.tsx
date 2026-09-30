import * as Dialog from '@radix-ui/react-dialog';
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { useKeyStore, usePutKey } from '../../api/auth';
import { failureText } from '../../api/client';
import { useAddProfiles, useAddView, useDiscardAdd, useOpenAdd, useSaveAdd, useSignInAdd, useVerifyAdd } from '../../api/usage';
import { EMPTY_ROW, asksAnything, autoSaveTarget, draftFor, loginRunning, planChoices, planLabel, ready, requestOf } from '../../lib/add';
import type { AddDraft, ModelRow, PlanChoice } from '../../lib/add';
import { AD } from '../../lib/words-add';
import type { AddChecksFailed, AddProfile, AddView } from '../../types/add';
import { Button, Check, Close } from '../../ui';
import { LoginTicket } from '../shared/LoginTicket';
import { useSignInTab } from '../shared/useSignInTab';

function Fault({ text }: { text: string }) {
  return <p className="hint alert" role="alert">{AD.failedTo} {text}</p>;
}

function Choices({ choices, onPick }: { choices: readonly PlanChoice[]; onPick: (choice: PlanChoice) => void }) {
  return (
    <ul className="plan-choices">
      {choices.map((choice) => (
        <li key={choice.id}>
          <button type="button" className="plan-choice" disabled={choice.profile === null} onClick={() => onPick(choice)}>
            <b>{choice.label}</b>
            <span>{choice.profile === null ? AD.unavailable : choice.why}</span>
          </button>
        </li>
      ))}
    </ul>
  );
}

function Details({ draft, profile, onDraft }: { draft: AddDraft; profile: AddProfile; onDraft: (next: AddDraft) => void }) {
  const setRow = (at: number, row: ModelRow): void => onDraft({ ...draft, models: draft.models.map((each, index) => (index === at ? row : each)) });
  return (
    <>
      {profile.asks.includes('name') ? (
        <label className="field">
          <span className="eyebrow">{AD.name}</span>
          <input className="input" value={draft.name} onChange={(event) => onDraft({ ...draft, name: event.target.value })} placeholder={profile.head_key} autoFocus spellCheck={false} />
        </label>
      ) : null}
      {profile.asks.includes('base_url') ? (
        <label className="field">
          <span className="eyebrow">{AD.baseUrl}</span>
          <input className="input" value={draft.baseUrl} onChange={(event) => onDraft({ ...draft, baseUrl: event.target.value })} spellCheck={false} />
        </label>
      ) : null}
      {profile.asks.includes('models') ? (
        <fieldset className="model-rows">
          <legend className="eyebrow">{AD.models}</legend>
          {draft.models.map((row, at) => (
            // a row is its position: its id is typed into it, so the id cannot key it
            <div key={at} className="model-row">
              <input className="input" aria-label={AD.modelId} placeholder={AD.modelId} value={row.id} onChange={(event) => setRow(at, { ...row, id: event.target.value })} spellCheck={false} />
              <input className="input" aria-label={AD.window} placeholder={AD.window} inputMode="numeric" value={row.window} onChange={(event) => setRow(at, { ...row, window: event.target.value })} />
            </div>
          ))}
          <Button small onClick={() => onDraft({ ...draft, models: [...draft.models, EMPTY_ROW] })}>{AD.addModel}</Button>
        </fieldset>
      ) : null}
    </>
  );
}

/** How the open add's plan proves who it is, and the one control that does it. */
function Connect({ view, onSignIn, busy }: { view: AddView; onSignIn: () => void; busy: boolean }) {
  const store = useKeyStore();
  const put = usePutKey();
  const [key, setKey] = useState('');
  const env = view.key_env;
  if (view.sign_in_by === 'none') return <p className="hint">{view.profile === 'local' ? AD.localNoKey : AD.forwarded}</p>;
  if (view.sign_in_by === 'key' && env !== null) {
    const stored = store.data?.keys.find((each) => each.name === env)?.stored === true || view.credential.present;
    return (
      <form
        onSubmit={(event) => {
          event.preventDefault();
          put.mutate({ name: env, value: key.trim() }, { onSuccess: () => setKey('') });
        }}
      >
        <p className="hint">{AD.key(env)}</p>
        <label className="field">
          <span className="eyebrow">{AD.keyField}</span>
          <input className="input" type="password" autoComplete="off" value={key} onChange={(event) => setKey(event.target.value)} spellCheck={false} />
        </label>
        {put.isError ? <Fault text={failureText(put.error)} /> : null}
        <div className="acts-row">
          <Button kind="go" type="submit" disabled={key.trim() === '' || put.isPending}>{AD.keySave}</Button>
          {stored ? <span className="saved"><Check />{AD.keyStored}</span> : null}
        </div>
      </form>
    );
  }
  const login = view.sign_in;
  const running = loginRunning(view);
  return (
    <>
      <p className="hint">{AD.login}</p>
      <LoginTicket code={login?.user_code ?? null} link={login?.verification_uri ?? login?.browser_url ?? null} />
      {login?.failure_reason == null ? null : <Fault text={login.failure_reason} />}
      <div className="acts-row">
        <Button kind="go" disabled={busy || running || view.credential.present} onClick={onSignIn}>{running ? AD.signingIn : AD.signIn}</Button>
        {view.credential.present ? <span className="saved"><Check />{view.credential.detail}</span> : null}
      </div>
    </>
  );
}

function SavedView({ view }: { view: AddView }) {
  const saved = view.saved;
  const [copied, setCopied] = useState(false);
  if (saved === null) return null;
  const { restart, wrapper } = saved;
  return (
    <>
      <p className="hint">{AD.saved(planLabel(view.profile))}. {wrapper.linked ? AD.launch : AD.notLinked}</p>
      {wrapper.linked ? (
        <div className="code-row">
          <code className="user-code cmd">{view.command}</code>
          <Button small onClick={() => void navigator.clipboard.writeText(view.command).then(() => setCopied(true))}>{copied ? AD.copied : AD.copy}</Button>
        </div>
      ) : null}
      {wrapper.error === undefined ? null : <Fault text={wrapper.error} />}
      <p className="hint" role="status">
        {restart.status === 'draining' ? AD.draining : restart.status === 'waiting' ? AD.waiting(restart.compactions?.length ?? 0) : AD.restartManually}
      </p>
      {restart.error === undefined ? null : <Fault text={restart.error} />}
    </>
  );
}

/** Add a plan, from choosing it to the restart that makes it reachable, in one dialog. The daemon decides and the dialog asks:
 *  every step answers the add's whole view, and this holds that view and never infers a state the daemon did not report. */
export function AddPlan({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<AddDraft | null>(null);
  const [addId, setAddId] = useState<string | null>(null);
  const [attempted, setAttempted] = useState<string | null>(null);
  const [failed, setFailed] = useState<AddChecksFailed | null>(null);
  const [fault, setFault] = useState<string | null>(null);
  const profiles = useAddProfiles();
  const opener = useOpenAdd();
  const signIn = useSignInAdd();
  const verify = useVerifyAdd();
  const save = useSaveAdd();
  const discard = useDiscardAdd();
  const polled = useAddView(addId, true);
  const view = polled.data ?? null;
  const profile = profiles.data?.find((each) => each.name === draft?.profile) ?? null;
  const busy = opener.isPending || signIn.isPending || verify.isPending || save.isPending;
  const openSignInTab = useSignInTab(view?.sign_in ?? null, fault);

  const runSave = (id: string): void => {
    setFault(null);
    save.mutate(id, { onSuccess: (out) => setFailed('failed' in out ? out.failed : null), onError: (err) => setFault(failureText(err)) });
  };
  useEffect(() => {
    const target = autoSaveTarget(view, attempted);
    if (target === null) return;
    setAttempted(target);
    runSave(target);
    // runSave closes over stable mutation handles; the attempt is guarded by `attempted`
  }, [view, attempted]);

  const reset = (): void => {
    setDraft(null);
    setAddId(null);
    setAttempted(null);
    setFailed(null);
    setFault(null);
  };
  const close = (next: boolean): void => {
    setOpen(next);
    if (next) return;
    // an add the dialog leaves unsaved is the daemon's to evict; closing on purpose says so
    if (addId !== null && view !== null && view.saved === null) discard.mutate(addId);
    reset();
  };
  const openAdd = (): void => {
    if (draft === null) return;
    setFault(null);
    opener.mutate(requestOf(draft), { onSuccess: (opened) => setAddId(opened.id), onError: (err) => setFault(failureText(err)) });
  };
  const pick = (choice: PlanChoice): void => {
    if (choice.profile === null) return;
    const next = draftFor(choice.id);
    setDraft(next);
    if (!asksAnything(choice.profile)) opener.mutate(requestOf(next), { onSuccess: (opened) => setAddId(opened.id), onError: (err) => setFault(failureText(err)) });
  };

  const checks = failed?.checks ?? view?.checks ?? null;
  const title = view?.saved != null ? AD.saved(planLabel(view.profile)) : draft === null ? AD.title : planLabel(draft.profile);
  return (
    <Dialog.Root open={open} onOpenChange={close}>
      <Dialog.Trigger asChild>{children}</Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="scrim" />
        <Dialog.Content className="dialog add-plan">
          <div className="dialog-head">
            <Dialog.Title>{title}</Dialog.Title>
            <Dialog.Close asChild><button type="button" className="icon-btn" aria-label={AD.cancel}><Close /></button></Dialog.Close>
          </div>
          <Dialog.Description className="hint">{draft === null ? AD.chooseWhy : view === null ? '' : `${view.key} · ${view.command}`}</Dialog.Description>
          {fault === null ? null : <Fault text={fault} />}

          {draft === null ? (
            profiles.isPending ? <p className="hint">{AD.loading}</p> : profiles.isError ? <Fault text={failureText(profiles.error)} /> : <Choices choices={planChoices(profiles.data)} onPick={pick} />
          ) : view === null ? (
            profile !== null && asksAnything(profile) ? (
              <form onSubmit={(event) => { event.preventDefault(); openAdd(); }}>
                <Details draft={draft} profile={profile} onDraft={setDraft} />
                <div className="acts-row">
                  <Button kind="go" type="submit" disabled={!ready(draft, profile) || busy}>{opener.isPending ? AD.opening : AD.next}</Button>
                  <Button onClick={reset}>{AD.back}</Button>
                </div>
              </form>
            ) : <p className="hint">{AD.opening}</p>
          ) : view.saved !== null ? (
            <SavedView view={view} />
          ) : (
            <>
              <Connect view={view} busy={busy} onSignIn={() => {
                openSignInTab();
                signIn.mutate(view.id, { onError: (err) => setFault(failureText(err)) });
              }} />
              {checks === null ? null : (
                <ul className="add-checks" aria-label={AD.checksTitle}>
                  {checks.map((check) => (
                    <li key={check.name}>
                      <span className={`state ${check.ok ? 'work' : 'stuck'}`}><i />{check.ok ? AD.passed : AD.failed}</span>
                      <b>{check.name}</b>
                      <span>{check.detail}</span>
                    </li>
                  ))}
                </ul>
              )}
              {failed === null ? null : <Fault text={failed.error} />}
              {view.sign_in_by === 'login' ? (
                view.credential.present ? <p className="hint" role="status">{AD.finishing}</p> : null
              ) : (
                <div className="acts-row">
                  <Button disabled={busy} onClick={() => verify.mutate({ id: view.id, live: false }, { onSuccess: (out) => setFailed('failed' in out ? out.failed : null), onError: (err) => setFault(failureText(err)) })}>
                    {verify.isPending ? AD.checking : AD.check}
                  </Button>
                  <Button kind="go" disabled={busy} onClick={() => runSave(view.id)}>{save.isPending ? AD.saving : AD.save}</Button>
                </div>
              )}
              {view.sign_in_by === 'login' && fault !== null ? <Button kind="go" disabled={busy} onClick={() => runSave(view.id)}>{AD.retrySave}</Button> : null}
            </>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
