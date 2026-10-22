import { Notice } from '../components/Notice';
import { useRef, useState } from 'react';
import { Check, LoaderCircle, MessageSquare, X } from 'lucide-react';
import { useI18n } from '../i18n';
import { loomyText } from '../i18n/loomy';
import { isRecord, managementApi, readString } from '../services/managementApi';
import { notifyPluginResourcesChanged } from '../services/pluginResources';
import { Dialog } from '../components/Dialog';
import './LoomyLoginCard.css';

export function LoomyLoginCard() {
  const { locale } = useI18n();
  const [open, setOpen] = useState(false);
  return <>
    <section className="panel surface-panel oauth-card">
      <div className="provider-title-row"><MessageSquare className="provider-logo" aria-hidden="true" /><h2>{loomyText('title', locale)}</h2></div>
      <div className="oauth-card-body"><p className="oauth-hint">{loomyText('hint', locale)}</p></div>
      <div className="button-row management-card-actions">
        <button type="button" className="primary-button" aria-haspopup="dialog" onClick={() => setOpen(true)}>{loomyText('open', locale)}</button>
      </div>
    </section>
    {open ? <LoomyLoginDialog onClose={() => setOpen(false)} /> : null}
  </>;
}

function LoomyLoginDialog({ onClose }: { onClose: () => void }) {
  const { locale, t } = useI18n();
  const text = (key: Parameters<typeof loomyText>[0]) => loomyText(key, locale);
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [challenge, setChallenge] = useState<{ phone: string; msgid: string; expires: number } | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState(false);
  const busy = useRef(false);
  const phoneInput = useRef<HTMLInputElement>(null);

  const request = async (login: boolean) => {
    if (busy.current) { return; }
    setError('');
    if (!/^1[3-9]\d{9}$/.test(phone.trim())) { setError(text('invalidPhone')); return; }
    if (login && (!challenge || challenge.phone !== phone.trim() || challenge.expires <= Date.now() || !/^\d{4,8}$/.test(code.trim()))) {
      setError(text('invalidCode')); return;
    }
    busy.current = true;
    setLoading(true);
    try {
      if (login) {
        const result = await managementApi.post('/oauth/loomy/login', { phone: challenge!.phone, msgid: challenge!.msgid, code: code.trim() });
        if (!isRecord(result) || result.status !== 'ok' || !readString(result, 'name')) { throw new Error(text('invalidResponse')); }
        setSuccess(true);
        setCode('');
        setChallenge(null);
        notifyPluginResourcesChanged();
      } else {
        const result = await managementApi.post('/oauth/loomy/sms', { phone: phone.trim() });
        const msgid = isRecord(result) ? readString(result, 'msgid') : '';
        if (!msgid) { throw new Error(text('invalidResponse')); }
        setChallenge({ phone: phone.trim(), msgid, expires: Date.now() + 300000 });
        setCode('');
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : text('failed'));
    } finally {
      busy.current = false;
      setLoading(false);
    }
  };

  return <Dialog className="config-dialog loomy-login-dialog" labelledBy="loomy-login-title" busy={loading} closeDisabled={loading} initialFocusRef={phoneInput} onClose={onClose} dismissOnBackdrop tabIndex={-1}>
      <div className="config-dialog-heading"><h2 id="loomy-login-title">{text('title')}</h2>
        <button type="button" className="icon-button quiet" disabled={loading} onClick={onClose} aria-label={t('common.close')}><X size={18} aria-hidden="true" /></button>
      </div>
      <form className="loomy-login-form" onSubmit={event => { event.preventDefault(); void request(true); }}>
        <p className="oauth-hint">{text('hint')}</p>
        <label>{text('phone')}<input ref={phoneInput} type="tel" inputMode="numeric" autoComplete="tel-national" maxLength={11} value={phone} disabled={loading || success} onChange={event => {
          setPhone(event.currentTarget.value); setChallenge(null); setCode(''); setError('');
        }} /></label>
        <button type="button" className="secondary-button" disabled={loading || success || !/^1[3-9]\d{9}$/.test(phone.trim())} onClick={() => void request(false)}>{text('send')}</button>
        {challenge ? <Notice tone="info" variant="text">{text('sent')}</Notice> : null}
        <label>{text('code')}<input type="text" inputMode="numeric" autoComplete="one-time-code" maxLength={8} value={code} disabled={loading || success || !challenge} onChange={event => setCode(event.currentTarget.value)} /></label>
        {error ? <Notice className="loomy-login-error" tone="error" variant="text">{error}</Notice> : null}
        {success ? <Notice tone="info" variant="text"><Check size={16} aria-hidden="true" /> {text('success')}</Notice> : null}
        <div className="button-row loomy-login-actions">
          <button type="button" className="secondary-button" disabled={loading} onClick={onClose}>{t('common.close')}</button>
          <button type="submit" className="primary-button" disabled={loading || success || !challenge || !code.trim()}>
            {loading ? <LoaderCircle size={16} className="spin" aria-hidden="true" /> : null}{text('login')}
          </button>
        </div>
      </form>
  </Dialog>;
}
