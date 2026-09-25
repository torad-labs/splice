// The upgrade and its rollback from the console (V4-220 item 4): the same `splice upgrade` the CLI
// runs, started by the daemon out of process (UpgradeRunRoutes.kt), so the run outlives the restart
// it causes.
//
// THE RUN IS READ, NEVER ASSUMED. The start answers with the run, and the form reads GET
// /api/upgrade/run until the run ends: its state, its exit code and its output are the run's own.
// The run restarts the daemon, so a read nothing answers is that restart, said as such, and the
// reads go on until a daemon answers for the run from disk.
//
// INLINE TWO-STEP KEYS, as the daemon restart beside it: an upgrade restarts every head, and the
// armed key names the release the second press asks for.
import { useCallback, useEffect, useState } from 'react';
import { fetchUpgrade, readUpgradeRun, startUpgrade } from '@entities/doctor';
import type { UpgradeAsk, UpgradePayload, UpgradeRun } from '@entities/doctor';
import { Confirm, Fault, Input } from '@shared/controls';
import { poll, timeAgo } from '@shared/lib';
import { Badge } from '@shared/ui';
import { RUN_TONE, askOf, commandOf, rollbackTarget } from './model';
import { H, S } from './strings';
import './daemon-upgrade.css';

/** A run prints a few dozen lines over a minute or two, and restarts the daemon partway. */
const POLL_MS = 2000;

const messageOf = (err: unknown): string => (err instanceof Error ? err.message : String(err));

/** One run: the command, where it stands, and what it printed. [away] is a read nothing answered. */
export function RunView({ run, away }: { run: UpgradeRun; away: boolean }) {
  return (
    <section className="myx-dupgrade-run" aria-label={S.run}>
      <p className="myx-dupgrade-head">
        <code className="myx-dupgrade-command">{commandOf(run)}</code>
        <Badge tone={RUN_TONE[run.state]}>{S.state[run.state]}</Badge>
        <span>{timeAgo(run.started_at_epoch_millis)}</span>
        {run.exit_code === null ? null : <span>{S.exit(run.exit_code)}</span>}
      </p>
      {away ? <p className="myx-dupgrade-note" role="status">{H.away}</p> : null}
      {run.state === 'succeeded' ? <p className="myx-dupgrade-note">{H.reload}</p> : null}
      {run.state === 'lost' ? <p className="myx-dupgrade-note">{H.lost}</p> : null}
      {run.output.length === 0
        ? <p className="myx-dupgrade-note">{H.quiet}</p>
        : <pre className="myx-dupgrade-out" role="log" aria-label={S.output}>{run.output.join('\n')}</pre>}
    </section>
  );
}

export function DaemonUpgrade({ upgrade }: { upgrade: UpgradePayload | null }) {
  const [version, setVersion] = useState('');
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [run, setRun] = useState<UpgradeRun | null>(null);
  const [away, setAway] = useState(false);

  const read = useCallback(async () => {
    try {
      const answer = await readUpgradeRun();
      setAway('away' in answer);
      if ('run' in answer) setRun(answer.run);
    } catch (err) {
      setRefusal(messageOf(err));
    }
  }, []);

  // The newest run on mount, then every POLL_MS while it runs.
  useEffect(() => {
    void read();
  }, [read]);
  const running = run?.state === 'running';
  useEffect(() => (running ? poll(read, POLL_MS) : undefined), [running, read]);

  // A run that ended moved the installed release and the rollback target: the facts above re-read.
  const ended = run !== null && !running;
  useEffect(() => {
    if (ended) void fetchUpgrade();
  }, [ended, run?.id]);

  const start = (ask: UpgradeAsk) => {
    setBusy(true);
    setRefusal(null);
    startUpgrade(ask).then(
      (started) => {
        setAway(false);
        setRun(started);
      },
      (err: unknown) => setRefusal(messageOf(err)),
    ).finally(() => setBusy(false));
  };

  const ask = askOf(version);
  const target = rollbackTarget(upgrade);
  const held = busy || running;
  return (
    <div className="myx-dupgrade">
      <div className="myx-dupgrade-keys">
        <Input label={S.version} value={version} onChange={setVersion} placeholder={S.latest} w={12} disabled={running} />
        <Confirm label={S.upgrade} confirmLabel={S.upgradeTo(ask.to ?? S.latest)} busy={held} onConfirm={() => start(ask)} />
        {target === null ? null : (
          <Confirm label={S.rollback} confirmLabel={S.backTo(target)} busy={held} onConfirm={() => start({ rollback: true })} />
        )}
      </div>
      {refusal === null ? null : <Fault message={refusal} />}
      {run === null ? null : <RunView run={run} away={away} />}
    </div>
  );
}
