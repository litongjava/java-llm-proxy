import { createRoot } from 'react-dom/client';
import { useState } from 'react';
import { mockIPC } from '@tauri-apps/api/mocks';
import { RequestDetailDialog } from '../../src/components/RequestDetailDialog';
import '../../src/styles/index.css';

localStorage.setItem('easy-cli-proxy-api.locale', 'en');
document.documentElement.dataset.theme = 'light';
const schema = { type: 'object', properties: { count: { type: 'integer', maximum: 9007199254740991 } } };
const request = { model: 'claude-sonnet-4-5', messages: [{ role: 'user', content: 'Layout sample. '.repeat(18000) }],
  tools: [{ name: 'DesignSync', input_schema: schema }] };
mockIPC((command, args) => {
  if (command !== 'get_proxy_request_detail') { throw new Error(`Unexpected command: ${command}`); }
  if (args?.format) { return { protocol: 'anthropic', prompt: 'Layout sample', response: 'Invalid schema for function DesignSync' }; }
  return { id: 'layout-sample', originator: 'codex-tui', session_id: '01a142df-fc0f-7ac2-9df4-bb63d9d92383', thread_id: '01a142df-fc0f-7ac2-9df4-bb63d9d92383', protocol: 'anthropic', method: 'POST', path: '/v1/messages', status: 400,
    requestHeaders: { 'content-type': 'application/json', authorization: '[REDACTED]' }, requestBody: JSON.stringify(request),
    upstreamRequestHeaders: { 'content-type': 'application/json', authorization: '[REDACTED]' }, upstreamRequestBody: JSON.stringify(request),
    upstreamResponseHeaders: { 'content-type': 'application/json' },
    upstreamResponseBody: JSON.stringify({ error: { message: 'Invalid schema for function DesignSync: maximum must be a number' } }),
    responseHeaders: { 'content-type': 'application/json' }, responseBody: '{"error":{"type":"invalid_request_error"}}',
    requestTruncated: false, responseTruncated: false };
});
function Review() {
  const [open, setOpen] = useState(true);
  return <main style={{ padding: 24 }}><h1>Request detail layout review</h1><p>Synthetic data, including a 252 KB request body.</p>
    <button type="button" onClick={() => setOpen(true)}>Open request detail</button>
    {open && <RequestDetailDialog requestId="layout-sample" onClose={() => setOpen(false)} />}</main>;
}
createRoot(document.getElementById('root')!).render(<Review />);
