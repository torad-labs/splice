// Shell: hash tab router + theme cycle. Bootstrap only — every surface is a
// page composition; every number on screen comes from the control API.
import { useEffect, useState } from 'react';
import { FleetPage } from '@pages/fleet';
import { AuthPage } from '@pages/auth';
import { ConfigPage } from '@pages/config';
import { LogsPage } from '@pages/logs';
import { CompactionPage } from '@pages/compaction';
import { UnlockMgmt } from '@features/unlock-mgmt';
import { fetchControlStatus, useControlStatus } from '@entities/control-status';

const TABS = [
  { id: 'fleet', label: 'fleet', page: FleetPage },
  { id: 'auth', label: 'auth', page: AuthPage },
  { id: 'config', label: 'config', page: ConfigPage },
  { id: 'logs', label: 'logs', page: LogsPage },
  { id: 'compaction', label: 'compaction', page: CompactionPage },
] as const;

type TabId = (typeof TABS)[number]['id'];

function currentTab(): TabId {
  const hash = window.location.hash.replace('#', '');
  return (TABS.some((t) => t.id === hash) ? hash : 'fleet') as TabId;
}

const THEMES = ['auto', 'paper', 'observatory'] as const;

function applyTheme(theme: string) {
  if (theme === 'auto') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = theme;
}

export function App() {
  const [tab, setTab] = useState<TabId>(currentTab);
  const [theme, setTheme] = useState(() => localStorage.getItem('myx-theme') ?? 'auto');
  const status = useControlStatus((s) => s.data);

  useEffect(() => {
    void fetchControlStatus();
  }, []);

  useEffect(() => {
    const onHash = () => setTab(currentTab());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  useEffect(() => {
    applyTheme(theme);
    localStorage.setItem('myx-theme', theme);
  }, [theme]);

  const cycleTheme = () => {
    const next = THEMES[(THEMES.indexOf(theme as (typeof THEMES)[number]) + 1) % THEMES.length];
    setTheme(next);
  };

  const ActivePage = TABS.find((t) => t.id === tab)?.page ?? FleetPage;

  return (
    <div className="myx-shell">
      <header className="myx-header">
        <h1 className="myx-wordmark">
          mythos
          <span className="myx-wordmark-sub">
            {status ? `control v${status.version} · ${status.heads.length} heads` : 'connecting'}
          </span>
        </h1>
        <nav className="myx-tabs" aria-label="pages">
          {TABS.map((t) => (
            <a
              key={t.id}
              href={`#${t.id}`}
              className="myx-tab"
              aria-current={tab === t.id ? 'page' : undefined}
            >
              {t.label}
            </a>
          ))}
        </nav>
        <button type="button" className="myx-theme-btn" onClick={cycleTheme} aria-label={`theme: ${theme}`}>
          theme: {theme}
        </button>
      </header>
      <main className="myx-main">
        <ActivePage />
      </main>
      <footer className="myx-footer">
        loopback-only; bearer-guarded /api; key at ~/.claude-codex/state/mgmt-key
      </footer>
      <UnlockMgmt />
    </div>
  );
}
