import { createRoot } from 'react-dom/client';
import { mockIPC } from '@tauri-apps/api/mocks';
import { I18nProvider } from '../../src/i18n';
import { OAuthCallbackForward } from '../../src/components/OAuthCallbackForward';
import '../../src/styles/index.css';

localStorage.setItem('easy-cli-proxy-api.locale', 'en');
const fixture = { calls: [] as unknown[], fail: false, release: null as null | (() => void) };
Object.assign(window, { forwardFixture: fixture });
mockIPC(async (cmd, args) => {
  if (cmd !== 'management_request') return null;
  fixture.calls.push(args);
  await new Promise<void>((resolve) => { fixture.release = resolve; });
  if (fixture.fail) throw new Error('Callback does not match a waiting session');
  return { status: 'ok' };
});
createRoot(document.getElementById('root')!).render(<I18nProvider><OAuthCallbackForward /></I18nProvider>);
