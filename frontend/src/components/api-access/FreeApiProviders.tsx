import { useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { ExternalLink } from 'lucide-react';
import { useI18n } from '../../i18n';
import { freeApiProviderText } from '../../i18n/freeApiProviders';
import './FreeApiProviders.css';

const providers = [
  { id: 'opencode', name: 'OpenCode Zen', url: 'https://opencode.ai/zen' },
  { id: 'openrouter', name: 'OpenRouter', url: 'https://openrouter.ai/settings/keys' },
  { id: 'cerebras', name: 'Cerebras', url: 'https://cloud.cerebras.ai/platform/api-keys' },
] as const;

export function FreeApiProviders() {
  const { locale } = useI18n();
  const [failedUrl, setFailedUrl] = useState('');
  const text = (key: Parameters<typeof freeApiProviderText>[0]) => freeApiProviderText(key, locale);
  const openExternal = async (url: string) => {
    setFailedUrl('');
    try {
      await invoke('open_external_url', { url });
    } catch {
      setFailedUrl(url);
    }
  };
  return (
    <section className="panel free-api-providers" aria-labelledby="free-api-providers-title">
      <h2 id="free-api-providers-title">{text('title')}</h2>
      <p>{text('description')}</p>
      <ul className="free-api-providers-list">
        {providers.map((provider) => (
          <li key={provider.id}>
            <strong>{provider.name}</strong>
            <p>{text(provider.id)}</p>
            <a href={provider.url} target="_blank" rel="noopener noreferrer" onClick={(event) => {
              event.preventDefault();
              void openExternal(provider.url);
            }}>
              {text('apply')}<ExternalLink size={14} aria-hidden="true" />
            </a>
          </li>
        ))}
      </ul>
      <p className="free-api-providers-policy">{text('policy')}</p>
      {failedUrl ? <p role="alert">{text('failed')} <a href={failedUrl} target="_blank" rel="noopener noreferrer">{failedUrl}</a></p> : null}
    </section>
  );
}
