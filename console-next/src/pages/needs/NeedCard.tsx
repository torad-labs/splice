import { Link } from 'react-router';
import { routeOf, toneOf } from '../../lib/needs-page';
import type { Need } from '../../types/needs';
import type { SessionRow } from '../../types/sessions';
import { State, Window } from '../../ui';
import { ResumeCopy } from '../shared/SessionActions';
import { A } from './copy';
import { NeedFix } from './NeedFix';

/** A sentence that ends: a finding with no closing mark gets its full stop before the next sentence starts. */
const closed = (text: string): string => (/[.!?]$/.test(text) ? text : `${text}.`);

/** One thing that needs a person: its state, who it is about, one sentence, one act, and a quiet way to open it. Every card is
 *  an attention window: it drops its hue and keeps the one vermilion button. */
export function NeedCard({ need, row }: { need: Need; row: SessionRow | null }) {
  const details = need.at !== null && !(need.fix.kind === 'open' && routeOf(need.fix.href) === routeOf(need.at)) ? need.at : null;
  return (
    <Window as="li" attention className="need" aria-label={`${need.kind}: ${need.subject}`}>
      <div className="need-body">
        <State tone={toneOf(need)}>{need.kind}</State>
        <h2>{need.subject}</h2>
        <p title={need.finding}>
          {need.session?.said == null ? closed(need.finding) : <>{need.finding}: <b>{need.session.said}</b>{/[.!?]$/.test(need.session.said) ? null : '.'}</>}
          {need.session?.repo == null ? null : <> {A.sessionIn} <b>{need.session.repo}</b>.</>}
        </p>
      </div>
      <div className="need-do">
        <NeedFix fix={need.fix} />
        {details === null ? null : <Link className="btn quiet sm" to={routeOf(details)}>{A.open}</Link>}
        {need.state === 'waiting' && row !== null ? <ResumeCopy row={row} /> : null}
      </div>
    </Window>
  );
}
