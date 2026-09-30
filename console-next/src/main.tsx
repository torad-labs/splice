import { createRoot } from 'react-dom/client';
import { takeLaunchKey } from './api/client';
import { App } from './app/App';
import { bootTheme } from './lib/theme';
import './styles/fonts.css';
import './styles/tokens.css';
import './styles/base.css';

// Before React paints: the key from the address, then the wall the operator chose, so the first frame is
// already the right room.
takeLaunchKey();
bootTheme();

const root = document.getElementById('root');
if (root !== null) createRoot(root).render(<App />);
