import { javaProxyText } from '../i18n/javaProxy';
import { Notice } from './Notice';
import { useState, type FormEvent } from 'react';
import { LoaderCircle, Send } from 'lucide-react';
import { useI18n } from '../i18n';
import { managementApi } from '../services/managementApi';
import './OAuthCallbackForward.css';

export function OAuthCallbackForward() {
  const { locale } = useI18n();
  const zh = locale.startsWith('zh');
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [failed, setFailed] = useState(false);
  const send = async (event: FormEvent) => {
    event.preventDefault();
    if (busy || !url.trim()) return;
    setBusy(true); setMessage(''); setFailed(false);
    try {
      const result = await managementApi.post<{ status?: string; error?: string }>('/oauth/callback/forward', { redirect_url: url.trim() }, { timeoutMs: 70000 });
      if (result.status === 'error') throw new Error(result.error || (zh ? '授权失败' : 'Authorization failed'));
      setUrl('');
      setMessage(result.status === 'ok'
        ? (zh ? '回调已发送，授权已完成。' : 'Callback sent. Authorization completed.')
        : (zh ? '回调已提交，请查看授权状态。' : 'Callback submitted. Check the authorization status.'));
    } catch (error) {
      setFailed(true);
      setMessage(String(error));
    } finally { setBusy(false); }
  };
  return <form className="panel oauth-callback-forward" onSubmit={(event) => void send(event)}>
    <label htmlFor="oauth-forward-url">{javaProxyText('callbackTitle', locale)}</label>
    <p id="oauth-forward-help">{javaProxyText('callbackHelp', locale)}</p>
    <div className="oauth-callback-forward-row">
      <input id="oauth-forward-url" type="url" value={url} onChange={(event) => setUrl(event.target.value)}
        placeholder="http://127.0.0.1:1455/auth/callback?code=...&state=..." required maxLength={32768}
        autoComplete="off" spellCheck={false} aria-describedby="oauth-forward-help" disabled={busy} />
      <button className="primary-button" type="submit" disabled={busy || !url.trim()}>
        {busy ? <LoaderCircle size={16} className="spin" /> : <Send size={16} />}
        {busy ? (zh ? '发送中…' : 'Sending…') : (zh ? '发送请求' : 'Send request')}
      </button>
    </div>
    {message ? <Notice tone={failed ? "error" : "info"} variant="text">{message}</Notice> : null}
  </form>;
}
