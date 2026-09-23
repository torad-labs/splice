// The draining restart, as ONE control: the fleet's head detail and the doctor's upgrade section
// both mount this, so the confirmation, the request and the way an answer is printed cannot drift
// between the two places an operator restarts the daemon from.
//
// INLINE, NEVER A DIALOG: the first press arms the key in place and the second sends (the world's
// Confirm). A restart interrupts every head at once, which is exactly the action the brief keeps a
// two-step for, and the armed key's own word says what the second press does.
//
// THE ANSWER IS THE DAEMON'S, VERBATIM. A taken restart prints the status word it answered with
// (`draining`: the drain began, not that the daemon is back). A refused one prints the refusal's own
// sentence in the fault strip, because on a daemon nothing supervises the refusal IS the answer
// (DaemonRoutes.kt: "nothing will restart this daemon ..."), and a console that reported only a
// failure would hide why nothing happened.
import { useState } from 'react';
import { restartDaemon } from '@entities/daemon';
import { Confirm, Fault } from '@shared/controls';
import { S } from './strings';
import './daemon-restart.css';

type Answer =
  | { kind: 'taken'; status: string }
  | { kind: 'refused'; message: string };

export function DaemonRestart() {
  const [busy, setBusy] = useState(false);
  const [answer, setAnswer] = useState<Answer | null>(null);

  const send = () => {
    setBusy(true);
    setAnswer(null);
    restartDaemon().then(
      (taken) => setAnswer({ kind: 'taken', status: taken.status }),
      (err: unknown) => setAnswer({ kind: 'refused', message: err instanceof Error ? err.message : String(err) }),
    ).finally(() => setBusy(false));
  };

  return (
    <div className="myx-drestart">
      <Confirm label={S.restart} confirmLabel={S.confirm} busy={busy} onConfirm={send} />
      {answer === null ? null : answer.kind === 'taken' ? (
        <p className="myx-drestart-note" role="status">{`${S.daemon} ${answer.status}`}</p>
      ) : (
        <Fault message={answer.message} />
      )}
    </div>
  );
}
