import { createRoot } from 'react-dom/client';
import { initSession } from '@entities/session';
import { applyTheme, endThemeCut, initialTheme } from '@features/theme';
import { App } from './App';
import { canonicalHash } from './rows';
import '@shared/tokens.css';
import '@shared/fonts.css';
import './app.css';

initSession();

// Boot, before React paints: the old bare addresses become canonical paths,
// and the room is put on the element. Both are done here rather than in an
// effect so the first frame is already the console the operator chose: an
// effect would paint one frame of the default room first.
const canonical = canonicalHash(window.location.hash);
if (canonical !== window.location.hash) window.location.replace(canonical);

try {
  applyTheme(initialTheme(window.localStorage), document.documentElement);
  endThemeCut(document.documentElement);
} catch {
  /* storage refused at boot: the sheet's own default is already the dark room */
}

const el = document.getElementById('root');
if (el) createRoot(el).render(<App />);
