import * as Dialog from '@radix-ui/react-dialog';
import { useEffect, useReducer, useState } from 'react';
import type { ReactNode } from 'react';
import { failureText } from '../../api/client';
import { isPendingRoute, useLoginStatus, useStartLogin } from '../../api/auth';
import { LOGIN_PENDING_EMPTY, canStart, initialLoginState, next, polling, stepMessage } from '../../lib/login';
import { H, S } from '../../lib/words-login';
import type { ClaudeLoginPlaceId } from '../../types/accounts';
import { Button, Close } from '../../ui';
import { LoginTicket } from './LoginTicket';
import { useSignInTab } from './useSignInTab';

/** The sign-in flow in a dialog: name the account, start the login, finish it in the browser with the code or link the
 *  daemon hands out, then wait for the head to restart with the account. A renewal begins at its existing label. */
export function SignIn({ head, label = '', place, purpose, children }: { head: string; label?: string; place?: ClaudeLoginPlaceId; purpose: 'add' | 'renew'; children: ReactNode }) {
  const initialLabel = label || place || '';
  const [open, setOpen] = useState(false);
  const [state, dispatch] = useReducer(next, initialLoginState(initialLabel));
  const [loginId, setLoginId] = useState<string | null>(null);
  const start = useStartLogin();
  const status = useLoginStatus(head, loginId, polling(state));
  const openSignInTab = useSignInTab(state.status, state.step === 'failed' ? (state.note ?? '') : null);

  useEffect(() => {
    const data = status.data;
    if (data === undefined) return;
    if (isPendingRoute(data)) dispatch({ kind: 'pending', row: data.pending });
    else dispatch({ kind: 'status', payload: data });
  }, [status.data]);

  const submit = async (): Promise<void> => {
    if (!canStart(state)) return;
    openSignInTab();
    dispatch({ kind: 'start' });
    try {
      const answer = await start.mutateAsync({ head, label: state.label.trim(), ...(place === undefined ? {} : { place }) });
      if ('pending' in answer) dispatch({ kind: 'pending', row: answer.pending });
      else if (answer.action === 'login') {
        setLoginId(answer.result.id);
        dispatch({ kind: 'status', payload: answer.result });
      }
    } catch (err) {
      dispatch({ kind: 'failed', note: failureText(err) });
    }
  };

  const message = stepMessage(state, purpose);
  const code = state.status?.user_code ?? null;
  const link = state.status?.verification_uri ?? state.status?.browser_url ?? null;
  const running = state.step === 'starting' || state.step === 'awaiting' || state.step === 'landed';
  return (
    <Dialog.Root
      open={open}
      onOpenChange={(next_) => {
        setOpen(next_);
        if (!next_ && !running) {
          dispatch({ kind: 'reset', label: initialLabel });
          setLoginId(null);
        }
      }}
    >
      <Dialog.Trigger asChild>{children}</Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="scrim" />
        <Dialog.Content className="dialog">
          <div className="dialog-head">
            <Dialog.Title>{purpose === 'renew' ? S.renew : S.add}</Dialog.Title>
            <Dialog.Close asChild>
              <button type="button" className="icon-btn" aria-label={S.cancel}>
                <Close />
              </button>
            </Dialog.Close>
          </div>
          <Dialog.Description className="hint">{H.destination(place, head)}</Dialog.Description>
          {state.step === 'idle' ? <p className="hint">{H.startWhy}</p> : null}
          {state.step === 'pending' ? (
            <p className="hint">{LOGIN_PENDING_EMPTY.source}</p>
          ) : (
            <form
              onSubmit={(event) => {
                event.preventDefault();
                void submit();
              }}
            >
              {place !== undefined || purpose === 'renew' && label !== '' ? null : (
                <label className="field">
                  <span className="eyebrow">{S.label}</span>
                  <input className="input" value={state.label} disabled={running} onChange={(event) => dispatch({ kind: 'label', value: event.target.value })} autoFocus />
                </label>
              )}
              {message === null ? null : <p className={state.step === 'failed' ? 'hint alert' : 'hint'} role={state.step === 'failed' ? 'alert' : 'status'}>{message}</p>}
              <LoginTicket code={code} link={link} />
              {state.step === 'live' ? null : (
                <Button kind="go" type="submit" disabled={!canStart(state)}>
                  {state.step === 'starting' ? H.opening : S.start}
                </Button>
              )}
            </form>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
