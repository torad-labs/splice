import { createRoot } from 'react-dom/client';
import { initSession } from '@entities/session';
import { App } from './App';
import '@shared/tokens.css';
import '@shared/fonts.css';
import './app.css';

initSession();

const el = document.getElementById('root');
if (el) createRoot(el).render(<App />);
