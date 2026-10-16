import { useEffect, useRef, useState } from 'react';
import ReactMarkdown, { defaultUrlTransform } from 'react-markdown';
import { createPortal } from 'react-dom';
import { invoke } from '@tauri-apps/api/core';
import { Copy, X } from 'lucide-react';
import { useDialogFocusTrap } from './useDialogFocusTrap';
import { getCurrentLocale } from '../i18n';
import './requestDetail.css';

type RequestDetail = {
  id: string;
  originator?: string;
  session_id?: string;
  thread_id?: string;
  protocol: string;
  method: string;
  path: string;
  status: number;
  requestHeaders: Record<string, unknown>;
  requestBody: string | null;
  upstreamRequestHeaders: Record<string, unknown>;
  upstreamRequestBody: string | null;
  upstreamResponseHeaders?: Record<string, unknown>;
  upstreamResponseBody?: string | null;
  responseHeaders: Record<string, unknown>;
  responseBody: string | null;
  requestTruncated: boolean;
  responseTruncated: boolean;
};
type FormattedRequest = { protocol: string; prompt: string; response: string };
type DetailSection = { id: string; title: string; text: string; formatted?: boolean };

function imageUrl(url: string): string {
  return /^(https?:\/\/|data:image\/(?:png|jpe?g|gif|webp|avif);base64,)/i.test(url) ? url : '';
}

function PreviewImage({ src, alt }: { src?: string; alt?: string }) {
  const [failed, setFailed] = useState(false);
  const zh = getCurrentLocale().startsWith('zh');
  if (!src || failed) return <span className="proxy-image-unavailable">{zh ? '图片无法加载' : 'Image unavailable'}</span>;
  return <img src={src} alt={alt || 'Image'} loading="lazy" referrerPolicy="no-referrer" onError={() => setFailed(true)} />;
}

function pretty(value: unknown): string {
  if (value == null) { return ''; }
  if (typeof value !== 'string') { return JSON.stringify(value, null, 2); }
  try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; }
}

export function RequestDetailDialog({ requestId, onClose }: { requestId: string; onClose: () => void }) {
  const zh = getCurrentLocale().startsWith('zh');
  const label = (en: string, cn: string) => zh ? cn : en;
  const dialogRef = useDialogFocusTrap<HTMLDivElement>({ onEscape: onClose });
  const [detail, setDetail] = useState<RequestDetail | null>(null);
  const [formatted, setFormatted] = useState<FormattedRequest | null>(null);
  const [formatting, setFormatting] = useState(false);
  const [prettyJson, setPrettyJson] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [activeSection, setActiveSection] = useState('request-headers');
  const sectionsRef = useRef<HTMLDivElement>(null);
  const currentRequest = useRef(requestId);
  currentRequest.current = requestId;

  function navigate(id: string) {
    const container = sectionsRef.current;
    const section = container?.querySelector<HTMLElement>(`#proxy-section-${id}`);
    if (container && section) {
      container.scrollTop += section.getBoundingClientRect().top - container.getBoundingClientRect().top;
      setActiveSection(id);
    }
  }

  function trackSection() {
    const container = sectionsRef.current;
    if (!container) return;
    const top = container.getBoundingClientRect().top;
    const sections = [...container.querySelectorAll<HTMLElement>('[data-section-id]')];
    const preceding = sections.filter(section => section.getBoundingClientRect().top <= top + 32);
    const active = preceding[preceding.length - 1] ?? sections[0];
    if (active) setActiveSection(active.dataset.sectionId!);
  }

  useEffect(() => { if (formatted) navigate('formatted-prompt'); }, [formatted]);

  useEffect(() => {
    let active = true;
    setDetail(null);
    setFormatted(null);
    setPrettyJson(false);
    setNotice('');
    setActiveSection('request-headers');
    setError('');
    invoke<RequestDetail>('get_proxy_request_detail', { requestId, format: false })
      .then(value => { if (active) { setDetail(value); } })
      .catch(reason => { if (active) { setError(String(reason)); } });
    return () => { active = false; };
  }, [requestId]);

  async function format() {
    setFormatting(true);
    setError('');
    try {
      const result = await invoke<FormattedRequest>('get_proxy_request_detail', { requestId, format: true });
      if (currentRequest.current !== requestId) return;
      setFormatted(result);
      setPrettyJson(true);
    } catch (reason) { setError(String(reason)); }
    finally { setFormatting(false); }
  }

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      setNotice(label('Copied', '已复制'));
    } catch (reason) { setError(String(reason)); }
  }

  const body = (value: string | null) => prettyJson ? pretty(value) : value ?? '';
  const sections: DetailSection[] = detail ? [
    { id: 'request-headers', title: label('Request headers', '请求头'), text: pretty(detail.requestHeaders) },
    { id: 'request-body', title: label('Request body', '请求体'), text: body(detail.requestBody) },
    { id: 'upstream-request-headers', title: label('Upstream request headers', '上游请求头'), text: pretty(detail.upstreamRequestHeaders) },
    { id: 'upstream-request-body', title: label('Upstream request body', '上游请求体'), text: body(detail.upstreamRequestBody) },
    { id: 'upstream-response-headers', title: label('Upstream response headers', '上游响应头'), text: pretty(detail.upstreamResponseHeaders ?? {}) },
    { id: 'upstream-response-body', title: label('Upstream response body', '上游响应体'), text: body(detail.upstreamResponseBody ?? null) },
    { id: 'response-headers', title: label('Response headers', '响应头'), text: pretty(detail.responseHeaders) },
    { id: 'response-body', title: label('Response body', '响应体'), text: body(detail.responseBody) },
  ] : [];
  if (formatted) {
    sections.unshift(
      { id: 'formatted-prompt', formatted: true, title: label('Formatted prompt', '格式化提示词'), text: formatted.prompt },
      { id: 'formatted-response', formatted: true, title: label('Formatted response', '格式化响应'), text: formatted.response },
    );
  }

  return createPortal(<div className="config-dialog-backdrop proxy-request-backdrop" onMouseDown={event => { if (event.target === event.currentTarget) { onClose(); } }}>
    <div ref={dialogRef} className="config-dialog proxy-request-dialog" role="dialog" aria-modal="true" aria-labelledby="proxy-request-title">
      <header className="proxy-request-toolbar">
        <div><h2 id="proxy-request-title">{label('Request Detail', '请求详情')}</h2><small>{requestId}</small></div>
        <button type="button" className="secondary-button" disabled={!detail || formatting} onClick={() => void format()}>
          {formatting ? label('Formatting…', '格式化中…') : label('Format', '格式化')}
        </button>
        <button type="button" className="secondary-button" aria-label={label('Close', '关闭')} onClick={onClose}><X size={18} /></button>
      </header>
      {detail && <p>{detail.method} {detail.path} · {detail.status} · {formatted?.protocol ?? detail.protocol}</p>}
      {detail && <div className="proxy-client-context">
        {(['originator', 'session_id', 'thread_id'] as const).map(field => <div key={field}>
          <span>{field === 'originator' ? 'Originator' : field === 'session_id' ? 'Session ID' : 'Thread ID'}</span>
          <div><code>{detail[field] || '—'}</code><button type="button" className="icon-button" disabled={!detail[field]}
            aria-label={`${label('Copy', '复制')} ${field}`} onClick={() => void copy(detail[field] || '')}><Copy size={14} /></button></div>
        </div>)}
      </div>}
      {error && <p role="alert">{error}</p>}
      {notice && <span role="status">{notice}</span>}
      {!detail && !error && <p>{label('Loading…', '加载中…')}</p>}
      {detail && (detail.requestTruncated || detail.responseTruncated) && <p>{label('Captured body reached the configured size limit.', '捕获内容已达到配置的大小上限。')}</p>}
      <div className="proxy-request-content">
        <nav className="proxy-request-nav" aria-label={label('Request sections', '请求详情导航')}>
          {sections.map(section => <button type="button" key={section.id} aria-controls={`proxy-section-${section.id}`}
            aria-current={activeSection === section.id ? 'location' : undefined} onClick={() => navigate(section.id)}>{section.title}</button>)}
        </nav>
      <div className="proxy-request-sections" ref={sectionsRef} onScroll={trackSection}>
        {sections.map(section => <section key={section.id} id={`proxy-section-${section.id}`} data-section-id={section.id}>
          <div className="proxy-request-section-heading"><h3>{section.title}</h3><button type="button" className="secondary-button" onClick={() => void copy(section.text)}><Copy size={14} />{label('Copy', '复制')}</button></div>
          {section.formatted ? <div className="proxy-formatted-content">
            <ReactMarkdown urlTransform={(url, key) => key === 'src' ? imageUrl(url) : defaultUrlTransform(url)}
              components={{ img: ({ src, alt }) => <PreviewImage key={src} src={src} alt={alt} /> }}>
              {section.text || label('(empty)', '（空）')}
            </ReactMarkdown>
          </div> : <pre tabIndex={0}>{section.text || label('(empty)', '（空）')}</pre>}
        </section>)}
      </div>
      </div>
    </div>
  </div>, document.body);
}
