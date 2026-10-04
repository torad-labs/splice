import { useState } from 'react';
import { failureText } from '../../api/client';
import { isPendingRoute } from '../../api/auth';
import { useStartUpgrade, useUpgrade, useUpgradeRun } from '../../api/upgrade';
import { upgradeVerdict } from '../../lib/doctor';
import { timeAgo } from '../../lib/format';
import { askOf, commandOf, rollbackTarget, versionLede } from '../../lib/upgrade';
import { U } from '../../lib/words-upgrade';
import type { UpgradeRun } from '../../types/doctor';
import { Button, Confirm, State } from '../../ui';
import type { StateTone } from '../../ui';
import { T } from './copy';
import { Row } from './Row';

const TONE: Record<keyof typeof U.verdict, StateTone> = { unknown: 'idle', current: 'work', behind: 'wait' };
const RUN_TONE: Record<UpgradeRun['state'], StateTone> = { running: 'wait', succeeded: 'work', failed: 'stuck', lost: 'quota' };

function RunView({ run, away }: { run: UpgradeRun; away: boolean }) {
  return (
    <section className="run" aria-label={U.run}>
      <p className="run-head">
        <code>{commandOf(run)}</code>
        <State tone={RUN_TONE[run.state]}>{U.state[run.state]}</State>
        <span>{timeAgo(run.started_at_epoch_millis)}</span>
        {run.exit_code === null ? null : <span>{U.exit(run.exit_code)}</span>}
      </p>
      {away ? <p className="hint" role="status">{U.away}</p> : null}
      {run.state === 'succeeded' ? <p className="hint">{U.reload}</p> : null}
      {run.state === 'lost' ? <p className="hint">{U.lost}</p> : null}
      {run.output.length === 0 ? <p className="hint">{U.quiet}</p> : <pre role="log" aria-label={U.output}>{run.output.join('\n')}</pre>}
    </section>
  );
}

/** The release splice runs, and the two acts that change it: upgrade to a release, or go back to the previous one. The run is read,
 *  never assumed: it restarts the daemon, so its record lives on disk and whichever daemon is up answers for it. */
export function Upgrade() {
  const upgrade = useUpgrade();
  const read = useUpgradeRun();
  const start = useStartUpgrade();
  const [version, setVersion] = useState('');
  const data = upgrade.data;
  if (upgrade.isError) return <p className="hint alert row-note" role="alert">{failureText(upgrade.error)}</p>;
  if (data === undefined) return <p className="hint row-note">{T.reading}</p>;
  if (isPendingRoute(data)) return <p className="hint row-note">{U.unserved}</p>;
  const verdict = upgradeVerdict(data);
  const view = read.data;
  const running = view?.run?.state === 'running' || start.isPending;
  const ask = askOf(version);
  const target = rollbackTarget(data);
  const go = (body: Parameters<typeof start.mutateAsync>[0]) => async (): Promise<void> => void (await start.mutateAsync(body));
  return (
    <>
      <Row title={U.title} why={versionLede(data)} control={<State tone={TONE[verdict]}>{U.verdict[verdict]}</State>} />
      <Row
        title={U.upgrade}
        why={U.upgradeNote}
        control={
          <>
            <input className="input release" aria-label={U.release} placeholder={U.latest} value={version} disabled={running} spellCheck={false} onChange={(event) => setVersion(event.currentTarget.value)} />
            <Confirm
              trigger={<Button disabled={running}>{U.upgrade}</Button>}
              title={U.upgradeTitle(ask.to ?? U.latest.toLowerCase())}
              why={U.upgradeWhy}
              act={U.upgrade}
              cancel={U.cancel}
              onConfirm={go(ask)}
            />
            {target === null ? null : (
              <Confirm trigger={<Button disabled={running}>{U.rollback}</Button>} title={U.rollbackTitle(target)} why={U.rollbackWhy} act={U.rollback} cancel={U.cancel} onConfirm={go({ rollback: true })} />
            )}
          </>
        }
      />
      {view?.run == null ? null : <RunView run={view.run} away={view.away} />}
    </>
  );
}
