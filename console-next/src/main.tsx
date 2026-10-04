import { createRoot } from 'react-dom/client';
// The shared sheets come first: a page's own sheet (imported through App) must land after them, or `.win` outranks `.need`.
import './styles/fonts.css';
import './styles/tokens.css';
import './styles/base.css';
import './styles/cards.css';
import { takeLaunchKey } from './api/client';
import { App } from './app/App';
import { bootTheme } from './lib/theme';

// Before React paints: the key from the address, then the wall the operator chose, so the first frame is
// already the right room.
takeLaunchKey();
bootTheme();

const root = document.getElementById('root');
if (root !== null) createRoot(root).render(<App />);
