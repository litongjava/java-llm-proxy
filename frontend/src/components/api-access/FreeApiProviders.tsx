import { Notice } from '../Notice';
import { EmptyState } from '../EmptyState';
import { useMemo, useState } from 'react';
import { invoke } from '../../services/native';
import { ArrowUpRight, ExternalLink, KeyRound, Monitor, Search, Sparkles, X } from 'lucide-react';
import { useI18n } from '../../i18n';
import { freeApiProviderText } from '../../i18n/freeApiProviders';
import { freeApiProviders, type FreeApiProviderCategory } from '../../data/freeApiProviders';
import './FreeApiProviders.css';

const categories = ['all', 'free', 'trial', 'local', 'verify'] as const;
type Filter = 'all' | FreeApiProviderCategory;

export function FreeApiProviders({ onManage }: { onManage?: () => void }) {
  const { locale } = useI18n();
  const [failedUrl, setFailedUrl] = useState('');
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState<Filter>('all');
  const text = (key: Parameters<typeof freeApiProviderText>[0]) => freeApiProviderText(key, locale);
  const filtered = useMemo(() => {
    const needle = query.trim().toLocaleLowerCase();
    return freeApiProviders.filter(provider => (category === 'all' || category === provider.category)
      && `${provider.name} ${provider.description.join(' ')}`.toLocaleLowerCase().includes(needle))
      .sort((a, b) => ({ free: 0, trial: 1, local: 2, verify: 3 }[a.category] - { free: 0, trial: 1, local: 2, verify: 3 }[b.category]));
  }, [query, category]);
  const clear = () => { setQuery(''); setCategory('all'); };
  const openExternal = async (url: string) => {
    setFailedUrl('');
    try { await invoke('open_external_url', { url }); }
    catch { setFailedUrl(url); }
  };
  return (
    <section className="free-api-providers" aria-labelledby="free-api-providers-title">
      <div className="free-api-hero">
        <div className="free-api-hero-copy">
          <span className="free-api-eyebrow"><Sparkles size={15} aria-hidden="true" />{freeApiProviders.length} {text('total')}</span>
          <h2 id="free-api-providers-title">{text('title')}</h2>
          <p>{text('description')}</p>
          <button type="button" className="primary-button" onClick={onManage}><KeyRound size={16} aria-hidden="true" />{text('manage')}<ArrowUpRight size={16} aria-hidden="true" /></button>
        </div>
        <div className="free-api-hero-mark" aria-hidden="true"><Sparkles size={52} /><span>API</span></div>
      </div>
      <div className="free-api-directory-toolbar">
        <div className="free-api-search"><Search size={18} aria-hidden="true" /><input type="search" value={query} onChange={event => setQuery(event.currentTarget.value)} placeholder={text('search')} aria-label={text('search')} />
          {query ? <button className="icon-button quiet ghost" type="button" onClick={() => setQuery('')} aria-label={text('clear')}><X size={16} /></button> : null}
        </div>
        <div className="free-api-filters" role="group" aria-label={text('discover')}>
          {categories.filter(item => item === 'all' || freeApiProviders.some(provider => provider.category === item)).map(item => <button type="button" key={item} aria-pressed={category === item} className={category === item ? 'primary-button compact-button' : 'secondary-button compact-button'} onClick={() => setCategory(item)}>
            {text(item)}<span>{item === 'all' ? freeApiProviders.length : freeApiProviders.filter(provider => provider.category === item).length}</span>
          </button>)}
        </div>
      </div>
      <div className="free-api-results"><Notice tone="info" variant="text">{filtered.length} {text('results')}</Notice>{query || category !== 'all' ? <button className="secondary-button ghost compact-button" type="button" onClick={clear}>{text('clear')}</button> : null}</div>
      <ul className="free-api-provider-grid">
        {filtered.map(provider => (
          <li key={provider.id} className={`panel free-api-provider-card category-${provider.category}`}>
            <div className="free-api-card-heading">
              <div className="free-api-monogram" aria-hidden="true">{provider.category === 'local' ? <Monitor size={22} /> : provider.name.replace(/[^a-zA-Z0-9]/g, '').slice(0, 2).toUpperCase()}</div>
              <div><h3>{provider.name}</h3><span className="free-api-domain">{new URL(provider.url).hostname.replace(/^www\./, '')}</span></div>
            </div>
            <span className="free-api-badge">{text(provider.category)}</span>
            <p>{provider.description[locale.startsWith('zh') ? 0 : 1]}</p>
            <a href={provider.url} target="_blank" rel="noopener noreferrer" onClick={event => { event.preventDefault(); void openExternal(provider.url); }}>
              {text(provider.category === 'local' ? 'download' : 'apply')}<ExternalLink size={14} aria-hidden="true" />
            </a>
          </li>
        ))}
      </ul>
      {filtered.length === 0 ? <EmptyState icon={<Search size={28} />} title={text('empty')} action={<button type="button" className="secondary-button" onClick={clear}>{text('clear')}</button>}>{text('emptyHint')}</EmptyState> : null}
      <p className="free-api-policy">{text('policy')}</p>
      {failedUrl ? <Notice tone="error" variant="text">{text('failed')} <a href={failedUrl} target="_blank" rel="noopener noreferrer">{failedUrl}</a></Notice> : null}
    </section>
  );
}
