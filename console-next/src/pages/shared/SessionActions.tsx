import * as Menu from '@radix-ui/react-dropdown-menu';
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { failureText } from '../../api/client';
import { useHeads, useStopTurn } from '../../api/queries';
import { fetchResume, useLiveTurnOf } from '../../api/sessions';
import { shellLine } from '../../lib/shell';
import { sessionKey } from '../../lib/sessions';
import type { SessionRow } from '../../types/sessions';
import { UNKNOWN_HEAD } from '../../types/sessions';
import { Button, Chevron } from '../../ui';
import { S } from './copy';

/** The route a session opens at. */
export const sessionPath = (row: SessionRow): string => `/sessions/${encodeURIComponent(sessionKey(row))}`;

/** The one charged act of a card that needs a person: open the session. */
export function OpenLink({ row }: { row: SessionRow }) {
  return (
    <Link className="btn go sm" to={sessionPath(row)}>
      {S.openSession}
    </Link>
  );
}

/** Stops the turn the session is running. A session with no live turn to name (the registry says stuck, the head
 *  runs nothing) has nothing to stop, so its one act is opening it. */
export function StopTurn({ row, fallback }: { row: SessionRow; fallback?: ReactNode }) {
  const head = row.head === UNKNOWN_HEAD ? null : row.head;
  const turn = useLiveTurnOf(head, row.session_id);
  const stop = useStopTurn();
  if (head === null || turn === null) return fallback === undefined ? <OpenLink row={row} /> : <>{fallback}</>;
  return (
    <>
      <Button kind="go" small disabled={stop.isPending} onClick={() => stop.mutate({ head, id: turn })}>
        {stop.isPending ? S.stopping : S.stopTurn}
      </Button>
      {stop.isError ? <span className="hint" role="alert">{S.stopFailed} {failureText(stop.error)}</span> : null}
    </>
  );
}

type Copy = { kind: 'idle' } | { kind: 'copied' } | { kind: 'failed'; message: string };

/** Copies how to resume a session on a head. A session the daemon launched names its head and is resumed there in one
 *  press; a menu beside it offers any other plan, because a plan that has reached its limit is resumed on another. One
 *  the daemon did not launch has no head to name and asks which, because the recipe differs per head. */
export function ResumeCopy({ row }: { row: SessionRow }) {
  const [copy, setCopy] = useState<Copy>({ kind: 'idle' });
  const reset = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(reset.current), []);
  const heads = useHeads();
  const id = row.session_id;
  if (id === null) return null;
  const run = async (head: string): Promise<void> => {
    try {
      const recipe = await fetchResume(id, head);
      await navigator.clipboard.writeText(shellLine(recipe.argv));
      setCopy({ kind: 'copied' });
      window.clearTimeout(reset.current);
      reset.current = window.setTimeout(() => setCopy({ kind: 'idle' }), 2_000);
    } catch (err) {
      setCopy({ kind: 'failed', message: failureText(err) });
    }
  };
  const label = copy.kind === 'copied' ? S.copied : S.copyResume;
  const note = copy.kind === 'failed' ? <span className="hint" role="alert">{S.copyFailed} {copy.message}</span> : null;
  const named = row.head !== UNKNOWN_HEAD;
  const options = (heads.data?.heads ?? []).filter((head) => head.key !== row.head);
  const menu = (trigger: ReactNode) => (
    <Menu.Root>
      <Menu.Trigger asChild>{trigger}</Menu.Trigger>
      <Menu.Portal>
        <Menu.Content className="menu" align="start" sideOffset={6} collisionPadding={8}>
          <Menu.Label className="menu-label">{S.pickHead}</Menu.Label>
          {options.length === 0 ? <div className="menu-empty">{S.noHeads}</div> : null}
          {options.map((head) => (
            <Menu.Item key={head.key} className="menu-item" onSelect={() => void run(head.key)}>
              {head.label}
            </Menu.Item>
          ))}
        </Menu.Content>
      </Menu.Portal>
    </Menu.Root>
  );
  return (
    <>
      {named ? <Button small onClick={() => void run(row.head)}>{label}</Button> : null}
      {menu(named ? <Button small aria-label={S.resumeElsewhere}>{S.otherPlan}<Chevron /></Button> : <Button small>{label}<Chevron /></Button>)}
      {note}
    </>
  );
}
