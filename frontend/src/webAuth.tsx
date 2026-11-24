import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { useI18n } from './i18n';
import { isWebMode } from './services/platform';
import { clearWebToken, getWebToken, setWebToken } from './services/webToken';
import './styles/web-auth.css';

async function checkToken(token: string): Promise<Response> {
  return fetch('/api/status', {
    method: 'GET',
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
    cache: 'no-store',
  });
}

export function WebAuthGate({ children }: { children: ReactNode }) {
  if (!isWebMode()) return <>{children}</>;
  return <WebAuthGateInner>{children}</WebAuthGateInner>;
}

function WebAuthGateInner({ children }: { children: ReactNode }) {
  const { t } = useI18n();
  const [state, setState] = useState<'checking' | 'login' | 'ready'>('checking');
  const [token, setToken] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let active = true;
    const saved = getWebToken();
    if (!saved) {
      setState('login');
      return () => { active = false; };
    }
    void checkToken(saved)
      .then((response) => {
        if (!active) return;
        if (response.ok) {
          setState('ready');
          return;
        }
        if (response.status === 401) clearWebToken();
        setError(t(response.status === 401 ? 'web.auth.invalid' : 'web.auth.failed',
          { status: response.status }));
        setState('login');
      })
      .catch(() => {
        if (!active) return;
        setError(t('web.auth.network'));
        setState('login');
      });
    return () => { active = false; };
  }, [t]);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const value = token.trim();
    if (!value) {
      setError(t('web.auth.tokenRequired'));
      return;
    }
    setSubmitting(true);
    setError('');
    try {
      const response = await checkToken(value);
      if (response.ok) {
        setWebToken(value);
        setState('ready');
      } else if (response.status === 401) {
        setError(t('web.auth.invalid'));
      } else {
        setError(t('web.auth.failed', { status: response.status }));
      }
    } catch {
      setError(t('web.auth.network'));
    } finally {
      setSubmitting(false);
    }
  };

  if (state === 'ready') return <>{children}</>;
  return (
    <main className="web-auth-page">
      <form className="web-auth-card" onSubmit={(event) => void submit(event)}>
        <h1>{t('web.auth.title')}</h1>
        <p>{t('web.auth.description')}</p>
        <label className="web-auth-field">
          <span>{t('web.auth.token')}</span>
          <input
            type="password"
            value={token}
            autoFocus
            autoComplete="current-password"
            placeholder={t('web.auth.tokenPlaceholder')}
            disabled={submitting || state === 'checking'}
            onChange={(event) => setToken(event.currentTarget.value)}
          />
        </label>
        {error ? <p className="web-auth-error" role="alert">{error}</p> : null}
        {state === 'checking'
          ? <p className="web-auth-hint">{t('web.auth.checking')}</p>
          : null}
        <button type="submit" className="primary-button" disabled={submitting || state === 'checking'}>
          {submitting ? t('web.auth.signingIn') : t('web.auth.signIn')}
        </button>
      </form>
    </main>
  );
}
