import { useEffect, useState } from 'react';
import { servedPage } from '../api/client';
import { useHealth } from '../api/queries';
import { fingerprint, loadedFingerprint, pageStale } from '../lib/stale-page';
import { C } from './copy';

/** How long a daemon that has just booted is given before the page is asked for, and how many times a refusal is asked again. */
const SETTLE_MS = 1_500;
const ASKS = 6;

/** The page the daemon serves now, as its fingerprint; null when it did not answer. */
export async function servedFingerprint(): Promise<string | null> {
  const html = await servedPage();
  return html === null ? null : fingerprint(html);
}

/** Compare each boot's served page with the fingerprint embedded in this tab's loaded document.
 *  It says nothing while either identity is missing. */
export function useStalePage(): boolean {
  const boot = useHealth().data?.bootedAtEpochMillis ?? null;
  const [opened] = useState(loadedFingerprint);
  const [seen, setSeen] = useState<number | null>(null);
  const [stale, setStale] = useState(false);
  useEffect(() => {
    if (opened === null || boot === null || seen === boot) return;
    let live = true;
    let timer: number | undefined;
    const ask = (left: number): void => {
      void servedFingerprint().then((served) => {
        if (!live) return;
        if (served === null && left > 1) { timer = window.setTimeout(() => ask(left - 1), SETTLE_MS * 2); return; }
        setStale(pageStale(opened, served));
        setSeen(boot);
      });
    };
    timer = window.setTimeout(() => ask(ASKS), SETTLE_MS);
    return () => { live = false; window.clearTimeout(timer); };
  }, [boot, seen, opened]);
  return stale;
}

/** The offer to reload a tab whose code the daemon has replaced. */
export function StaleBanner() {
  return (
    <div className="stale-page" role="status">
      <span>{C.stale}</span>
      <button type="button" className="btn go sm" onClick={() => window.location.reload()}>{C.reload}</button>
    </div>
  );
}
