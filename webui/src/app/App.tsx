// Shell: hash tab router + theme cycle. Bootstrap only — every surface is a
// page composition; every number on screen comes from the mgmt API.
import { useEffect, useState } from 'react';
import { DashboardPage } from '@pages/dashboard';
import { ConfigPage } from '@pages/config';
import { ReasoningPage } from '@pages/reasoning';
import { CompactionPage } from '@pages/compaction';
import { AuthPage } from '@pages/auth';
import { LogsPage } from '@pages/logs';
import { ModelsPage } from '@pages/models';
import { UnlockMgmt } from '@features/unlock-mgmt';
import { useStatus } from '@entities/proxy-status';

const TABS = [
  { id: 'dashboard', label: 'dashboard', page: DashboardPage },
  { id: 'config', label: 'config', page: ConfigPage },
  { id: 'reasoning', label: 'reasoning', page: ReasoningPage },
  { id: 'compaction', label: 'compaction', page: CompactionPage },
  { id: 'auth', label: 'auth', page: AuthPage },
  { id: 'logs', label: 'logs', page: LogsPage },
  { id: 'models', label: 'models', page: ModelsPage },
] as const;

type TabId = (typeof TABS)[number]['id'];

function currentTab(): TabId {
  const hash = window.location.hash.replace('#', '');
  return (TABS.some((t) => t.id === hash) ? hash : 'dashboard') as TabId;
}

const THEMES = ['auto', 'paper', 'observatory'] as const;

function applyTheme(theme: string) {
  if (theme === 'auto') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = theme;
}

export function App() {
  const [tab, setTab] = useState<TabId>(currentTab);
  const [theme, setTheme] = useState(() => localStorage.getItem('myx-theme') ?? 'auto');
  const status = useStatus((s) => s.data);

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

  const ActivePage = TABS.find((t) => t.id === tab)?.page ?? DashboardPage;

  return (
    <div className="myx-shell">
      <header className="myx-header">
        <h1 className="myx-wordmark">
          mythos
          <span className="myx-wordmark-sub">
            {status ? `${status.proxy} v${status.version}` : 'connecting'}
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
        loopback-only; bearer-guarded /mgmt; key at ~/.claude-codex/state/mgmt-key
      </footer>
      <UnlockMgmt />
    </div>
  );
}
