import { useState } from 'react';
import { failureText } from '../../api/client';
import { useRestartDaemon } from '../../api/doctor';
import { Button } from '../../ui';
import { R } from './copy';

/**
 * The one control that restarts the daemon, wherever it appears (a Needs-you card, a setting waiting on a restart). The
 * restart drains turns in flight, so the first press only arms it and says so; the second sends it. A 202 means the drain
 * was taken on, not that the daemon is back, and the control says exactly that.
 */
export function DaemonRestart({ label = R.restart, small = false }: { label?: string; small?: boolean }) {
  const restart = useRestartDaemon();
  const [armed, setArmed] = useState(false);
  if (restart.isSuccess) return <span className="hint" role="status">{R.draining}</span>;
  return (
    <>
      {armed ? (
        <>
          <Button kind="go" small={small} disabled={restart.isPending} onClick={() => restart.mutate()}>
            {restart.isPending ? R.restarting : R.confirm}
          </Button>
          <Button small={small} disabled={restart.isPending} onClick={() => setArmed(false)}>{R.cancel}</Button>
          <span className="hint">{R.warns}</span>
        </>
      ) : (
        <Button kind="go" small={small} onClick={() => setArmed(true)}>{label}</Button>
      )}
      {restart.isError ? <span className="hint alert" role="alert">{R.failed} {failureText(restart.error)}</span> : null}
    </>
  );
}
