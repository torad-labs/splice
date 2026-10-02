import { failureText } from '../../api/client';
import { useHeads, useSessions } from '../../api/queries';
import { useNeeds } from '../../api/needs';
import { asOfText, calmOf, ledeOf, listText, unreadOf } from '../../lib/needs-page';
import { N } from '../../lib/words-needs-page';
import { Fault, PageHead } from '../../ui';
import { NeedCard } from './NeedCard';
import './needs.css';

/** The first screen: everything a person has to do, each with its one act. Nothing here that is not a measured signal. */
export function NeedsPage() {
  const now = Date.now();
  const list = useNeeds(now);
  const heads = useHeads();
  const sessions = useSessions();
  const rows = sessions.data?.sessions ?? [];
  const calm = calmOf(list, rows, heads.data?.heads ?? []);
  const unread = unreadOf(list);
  const reading = list.readings.every((reading) => reading.state === 'reading');
  const asOf = asOfText(list);

  if (reading) return <PageHead title={N.title} lede={N.reading} />;
  if (heads.isError && heads.data === undefined) {
    return (
      <>
        <PageHead title={N.title} />
        <Fault message={failureText(heads.error)} onRetry={() => void heads.refetch()} />
      </>
    );
  }

  return (
    <>
      <PageHead title={N.title} lede={ledeOf(list)} />
      <div className="needs-layout">
        <div>
          {list.needs.length === 0 ? null : (
            <ul className="stack">
              {list.needs.map((need) => (
                <NeedCard key={need.key} need={need} row={need.session?.id == null ? null : rows.find((candidate) => candidate.session_id === need.session?.id) ?? null} />
              ))}
            </ul>
          )}
          {unread.length === 0 ? null : (
            <section className="needs-unread" aria-label={N.unreadTitle}>
              <h3>{N.unreadTitle}</h3>
              <ul>
                {unread.map((row) => (
                  <li key={row.input}>{row.text}</li>
                ))}
              </ul>
            </section>
          )}
        </div>
        <aside className="calm">
          <div>
            <div className="n">{calm.working}</div>
            <h3>{N.workingTitle}</h3>
            <p>{N.workingWhy}</p>
          </div>
          <div>
            <div className="n">{calm.serving.length}</div>
            <h3>{N.servingTitle}</h3>
            <p>{calm.serving.length === 0 ? N.servingNone : N.servingWhy(listText(calm.serving))}</p>
          </div>
          <div>
            <h3>{N.notListedTitle}</h3>
            <p>{N.notListedWhy}</p>
          </div>
          {asOf === null ? null : <p className="as-of">{asOf}</p>}
        </aside>
      </div>
    </>
  );
}
