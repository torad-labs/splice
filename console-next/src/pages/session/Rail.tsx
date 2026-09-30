import { Link } from 'react-router';
import { clockTime } from '../../lib/format';
import { plainLine } from '../../lib/message';
import type { Rail as RailFacts, Seat } from '../../lib/rail';
import type { ModelColour } from '../../lib/model';
import { stateWord } from '../../lib/sessions';
import { P } from './copy';

const firstLine = (text: string | null): string => (text === null ? '' : (plainLine(text) ?? ''));
const hue = (colour: ModelColour): React.CSSProperties => ({ '--c': colour === 'none' ? 'var(--tan)' : `var(--${colour})` }) as React.CSSProperties;

/** Who this session works with and what rode between them: the team's seats (lead first), or the sessions it
 *  exchanged hand-offs with when it has no team. */
export function Rail({ rail, colourOf, pathOf }: { rail: RailFacts; colourOf: (seat: Seat) => ModelColour; pathOf: (seat: Seat) => string | null }) {
  const alone = rail.seats.length <= 1 && rail.rides.length === 0;
  return (
    <aside className="rail" aria-label={P.team}>
      <h2>{rail.team ?? P.workingWith}</h2>
      {alone ? (
        <p className="hint">{P.alone}</p>
      ) : (
        <ul className="team">
          {rail.seats.flatMap((seat) => {
            const path = pathOf(seat);
            const label = path === null || seat.here ? seat.label : <Link to={path}>{seat.label}</Link>;
            return [
              <li key={seat.key} className={`seat${seat.here ? ' here' : ''}`} style={hue(colourOf(seat))}>
                <b>{label}</b>
                {seat.role === null || seat.role.toLowerCase() === seat.label.toLowerCase() ? null : <span className="role">{seat.role}</span>}
                {seat.lead ? <span className="tag">{P.lead}</span> : null}
                {seat.state === null ? null : <span className="s2">{stateWord(seat.state)}</span>}
              </li>,
              ...rail.rides
                .filter((ride) => ride.seat === seat.key)
                .map((ride) => (
                  <li key={`${seat.key}-${ride.at}-${ride.direction}`} className="ride" style={hue(colourOf(seat))}>
                    <span>{firstLine(ride.text) || P.handoffMissing}</span>
                    <small>
                      {ride.direction === 'in' ? P.received : P.sent} · {clockTime(ride.at)}
                    </small>
                  </li>
                )),
            ];
          })}
        </ul>
      )}
    </aside>
  );
}
