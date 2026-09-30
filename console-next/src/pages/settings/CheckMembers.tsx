import { checkFinding } from '../../lib/doctor';
import type { CheckRow } from '../../types/doctor';
import { T } from './copy';

/** What every check folded into one row found. The row's own line speaks for the first; several checks that say the same
 *  thing about different launchers or heads each keep their own finding, and an operator fixing one needs all of them. */
export function CheckMembers({ row }: { row: CheckRow }) {
  if (row.members.length < 2) return null;
  return (
    <details className="members">
      <summary>{T.showAllFindings(row.members.length)}</summary>
      <ul>
        {row.members.map((member, index) => (
          // a member is its place in the report: two checks can share an id and a finding
          <li key={index}>{member.id.includes(':') ? <b>{member.id.slice(member.id.indexOf(':') + 1)} </b> : null}{checkFinding(member)}</li>
        ))}
      </ul>
    </details>
  );
}
