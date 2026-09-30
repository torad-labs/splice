import { useEffect } from 'react';
import { Link, useParams } from 'react-router';
import { failureText } from '../../api/client';
import { useConfig } from '../../api/queries';
import { SECTIONS, sectionOf } from '../../lib/settings';
import type { Section } from '../../lib/settings';
import { Fault, PageHead } from '../../ui';
import { T } from './copy';
import { Advanced, Conversation, General, Health, Storage, Tools } from './Sections';
import './settings.css';

/** Settings in D: every control is typed (a switch, a choice, a stepper, a slider, a folder, a secret), keys are hidden until asked for,
 *  and every section is on the one page, so the sub-navigation scrolls rather than swaps. */
export function SettingsPage() {
  const { section: param } = useParams();
  const current = sectionOf(param);
  const config = useConfig();
  useEffect(() => {
    if (param !== undefined) document.getElementById(`settings-${current}`)?.scrollIntoView({ block: 'start' });
  }, [param, current, config.isSuccess]);

  if (config.isPending) return <PageHead title={T.title} lede={T.reading} />;
  if (config.isError) {
    return (
      <>
        <PageHead title={T.title} />
        <Fault message={failureText(config.error)} onRetry={() => void config.refetch()} />
      </>
    );
  }
  const data = config.data;
  const body: Record<Section, React.ReactNode> = {
    general: <General config={data} />,
    conversation: <Conversation config={data} />,
    tools: <Tools />,
    storage: <Storage config={data} />,
    health: <Health />,
    advanced: <Advanced />,
  };
  return (
    <>
      <PageHead title={T.title} lede={T.lede} />
      <div className="split">
        <nav className="sub" aria-label={T.sections}>
          {SECTIONS.map((section) => (
            <Link key={section} to={`/settings/${section}`} aria-current={section === current ? 'true' : undefined}>
              {T.section[section]}
            </Link>
          ))}
        </nav>
        <div className="sheets">
          {SECTIONS.map((section) => (
            <section key={section} className="settings-sheet" id={`settings-${section}`} aria-labelledby={`settings-${section}-h`}>
              <h2 id={`settings-${section}-h`}>{T.section[section]}</h2>
              <div className="win flat set">{body[section]}</div>
            </section>
          ))}
        </div>
      </div>
    </>
  );
}
