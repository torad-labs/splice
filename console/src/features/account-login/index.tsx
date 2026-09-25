// The account actions: start a login, finish it, pin the next account, relabel, remove, refresh.
//
// Buttons here are raw <button> elements styled from this feature's own CSS rather than the old
// `Btn`/`ConfirmBtn` primitives. That is deliberate and matches CONTRACTS.md section 2: those
// exports "keep working until M2 removes its last consumer", so an M2 page is exactly the consumer
// they are waiting to lose. The shell's own features/views takes the same route.
//
// Nothing here is modal. A destructive action arms in place and disarms on its own, the way
// ConfirmBtn did, because the brief rules out a dialog over the room.
import { useEffect, useReducer, useState } from 'react';
import { fetchLoginStatus, refreshAuth, relabelAccount, removeAccount, startLogin, switchAccount, unpinAccount } from '@entities/auth';
import type { LoginStartPayload } from '@entities/auth';
import { Empty, FieldBox, Reveal } from '@shared/ui';
import { poll } from '@shared/lib';
import { Copy } from '@shared/controls';
import { IDLE, LOGIN_PENDING_EMPTY, canStart, next, stepMessage } from './model';
import type { LoginEvent } from './model';
import { fetchAccounts } from '@entities/account';
import { H, S } from './strings';
import './account-login.css';

const POLL_MS = 2000;


const LOGIN_STATUS = 'login-status';
const LOGIN_START = 'login';

async function beginLogin(
  head: string,
  label: string,
  dispatch: (event: LoginEvent) => void,
): Promise<void> {
  dispatch({ kind: 'start' });
  try {
    const outcome = await startLogin(head, label);
    if ('pending' in outcome) {
      dispatch({ kind: 'pending', row: outcome.pending });
      return;
    }
    if (outcome.action === LOGIN_START) dispatch({ kind: 'started', payload: outcome.result });
  } catch (err) {
    dispatch({ kind: 'failed', note: err instanceof Error ? err.message : String(err) });
  }
}

async function pollLogin(
  head: string,
  loginId: string,
  dispatch: (event: LoginEvent) => void,
): Promise<void> {
  try {
    const outcome = await fetchLoginStatus(head, loginId);
    if ('pending' in outcome) {
      dispatch({ kind: 'pending', row: outcome.pending });
      return;
    }
    if (outcome.action === LOGIN_STATUS) dispatch({ kind: 'status', payload: outcome.result });
  } catch (err) {
    dispatch({ kind: 'failed', note: err instanceof Error ? err.message : String(err) });
  }
}

/** What the operator needs to finish a login somewhere else: the code and its link, or a URL. */
function LoginTicket({ start }: { start: LoginStartPayload }) {
  if (start.flow === 'browser') {
    return (
      <div className="myx-acct-ticket">
        {start.browser_url === undefined ? null : (
          <>
            <a className="myx-acct-link" href={start.browser_url}>{start.browser_url}</a>
            <Copy value={start.browser_url} label={S.copy} />
          </>
        )}
      </div>
    );
  }
  return (
    <div className="myx-acct-ticket">
      {start.user_code === undefined ? null : (
        <>
          <span className="myx-acct-code">{start.user_code}</span>
          <Copy value={start.user_code} label={`${S.copy} ${S.code}`} />
        </>
      )}
      {start.verification_uri === undefined ? null : (
        <>
          <a className="myx-acct-link" href={start.verification_uri}>{start.verification_uri}</a>
          <Copy value={start.verification_uri} label={`${S.copy} ${S.link}`} />
        </>
      )}
    </div>
  );
}

/** Start a login for a new account on one head. The form stays behind a Reveal so the bay is a
 *  rack of strips and not a form with strips in it. */
export function AccountLogin({ head }: { head: string }) {
  const [state, dispatch] = useReducer(next, IDLE);
  const loginId = state.start?.login_id;

  useEffect(() => {
    if (state.step !== 'awaiting' || loginId === undefined) return;
    return poll(() => {
      void pollLogin(head, loginId, dispatch);
    }, POLL_MS);
  }, [state.step, loginId, head]);

  if (state.step === 'pending') {
    return <Empty text={LOGIN_PENDING_EMPTY.text} source={LOGIN_PENDING_EMPTY.source} />;
  }

  const message = stepMessage(state);

  return (
    <Reveal label={S.add}>
      <div className="myx-acct-form">
        <FieldBox
          label={S.label}
          value={state.label}
          provenance="state file"
          hot
          onChange={(value) => dispatch({ kind: 'label', value })}
        />
        <div className="myx-acct-row">
          <button
            type="button"
            className="myx-acct-btn"
            disabled={!canStart(state)}
            onClick={() => void beginLogin(head, state.label.trim(), dispatch)}
          >
            {S.start}
          </button>
          {state.step === 'idle' ? null : (
            <button type="button" className="myx-acct-btn" onClick={() => dispatch({ kind: 'reset' })}>
              {S.cancel}
            </button>
          )}
        </div>
        {state.start === null ? null : <LoginTicket start={state.start} />}
        {message === null ? null : <p className="myx-acct-note" role="status">{message}</p>}
      </div>
    </Reveal>
  );
}

/** The daemon's own reason when an action answered but did not happen: a refresh reports
 *  `{ ok: false, note }` (AuthStatusRoutes' honesty contract), a switch or an edit `{ ok: false,
 *  error }` inside the action's result. Null when it happened. */
export function refusalOf(outcome: unknown): string | null {
  if (outcome === null || typeof outcome !== 'object') return null;
  const body = 'result' in outcome && outcome.result !== null && typeof outcome.result === 'object' ? outcome.result : outcome;
  if (!('ok' in body) || body.ok !== false) return null;
  const reason = ('error' in body && typeof body.error === 'string' ? body.error : null)
    ?? ('note' in body && typeof body.note === 'string' ? body.note : null);
  return reason ?? H.refused;
}

/**
 * Everything that can be done TO one account. Each action is addressed the way its route is: a
 * switch and a refresh name a HEAD, because selection is per head and several heads can ride the
 * same login; remove and relabel name the KIND, because the pool belongs to the kind.
 */
export function AccountActions({ kind, label, heads, pinned = false }: {
  kind: string;
  label: string;
  heads: readonly string[];
  /** The daemon pinned this account (a manual switch). Only then is there a pin to drop. */
  pinned?: boolean;
}) {
  const [nextLabel, setNextLabel] = useState(label);
  const [armed, setArmed] = useState(false);
  const [note, setNote] = useState<{ text: string; failed: boolean } | null>(null);

  // Every action says what it did, and the accounts are read again at once: a switch used to answer
  // with nothing, and the rack showed the pin only on the next 15 s poll (walkthrough S6). A route
  // this daemon does not serve resolves to a pending marker, which is not a success.
  const run = (work: Promise<unknown>, done: string | null = null) => {
    work.then(
      (outcome) => {
        if (outcome !== null && typeof outcome === 'object' && 'pending' in outcome) {
          setNote({ text: H.unsupported, failed: true });
          return;
        }
        const refused = refusalOf(outcome);
        if (refused !== null) {
          setNote({ text: refused, failed: true });
          return;
        }
        setNote(done === null ? null : { text: done, failed: false });
        void fetchAccounts();
      },
      (err: unknown) => setNote({ text: err instanceof Error ? err.message : String(err), failed: true }),
    );
  };

  return (
    <div className="myx-acct-actions">
      {heads.map((head) => (
        <div className="myx-acct-row" key={head}>
          <button type="button" className="myx-acct-btn" onClick={() => run(switchAccount(head, label), H.switched)}>
            {`${S.switch} ${head}`}
          </button>
          {pinned ? (
            <button type="button" className="myx-acct-btn" onClick={() => run(unpinAccount(head), H.unpinned)}>
              {`${S.unpin} ${head}`}
            </button>
          ) : null}
          <button type="button" className="myx-acct-btn" onClick={() => run(refreshAuth(head), H.refreshed)}>
            {`${S.refresh} ${head}`}
          </button>
        </div>
      ))}

      <div className="myx-acct-row">
        <FieldBox
          label={S.relabel}
          value={nextLabel}
          provenance="state file"
          hot
          onChange={setNextLabel}
        />
        <button
          type="button"
          className="myx-acct-btn"
          disabled={nextLabel.trim() === '' || nextLabel === label}
          onClick={() => run(relabelAccount(kind, label, nextLabel.trim()), H.renamed)}
        >
          {S.relabel}
        </button>
      </div>

      <div className="myx-acct-row">
        {armed ? (
          <>
            <button
              type="button"
              className="myx-acct-btn myx-acct-btn-armed"
              onClick={() => {
                setArmed(false);
                run(removeAccount(kind, label), H.removed);
              }}
            >
              {`${S.remove} ${label}`}
            </button>
            <button type="button" className="myx-acct-btn" onClick={() => setArmed(false)}>
              {S.cancel}
            </button>
          </>
        ) : (
          <button type="button" className="myx-acct-btn" onClick={() => setArmed(true)}>
            {S.remove}
          </button>
        )}
      </div>

      {note === null ? null : <p className="myx-acct-note" role={note.failed ? 'alert' : 'status'}>{note.text}</p>}
    </div>
  );
}

/** The actions that belong to a head rather than to one of its accounts. */
export function HeadActions({ head }: { head: string }) {
  return (
    <div className="myx-acct-actions">
      <div className="myx-acct-row">
        <button type="button" className="myx-acct-btn" onClick={() => void refreshAuth(head)}>
          {S.refresh}
        </button>
      </div>
      <AccountLogin head={head} />
    </div>
  );
}
