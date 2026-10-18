import { createRoot } from 'react-dom/client';
import { mockIPC } from '@tauri-apps/api/mocks';
import { RequestDetailDialog } from '../../src/components/RequestDetailDialog';
import '../../src/styles/index.css';
const saved = new URLSearchParams(location.search).has('saved');
localStorage.setItem('easy-cli-proxy-api.locale', 'zh-CN');
mockIPC((command, args) => {
  if (command !== 'get_proxy_request_detail' || args?.format) throw new Error('Unexpected management request');
  return { id: args?.requestId, method: 'POST', path: '/v1/chat/completions', protocol: 'desktop-health-check', status: null,
    requestHeaders: {}, upstreamRequestHeaders: {}, responseHeaders: {}, requestBody: saved ? '{"messages":[{"role":"user","content":"Reply with OK."}]}' : null, upstreamRequestBody: null,
    responseBody: saved ? 'data: {"type":"message_stop"}' : null, requestTruncated: false, responseTruncated: false,
    localUsage: { timestamp: '2026-10-18T14:08:00+08:00', model: 'combo:deepseek-flash', provider: 'openai',
      success: true, latency_ms: 1739, ttft_ms: 1620, input_tokens: 0, output_tokens: 0, reasoning_tokens: 0, error: '' } };
});
createRoot(document.getElementById('root')!).render(
  <RequestDetailDialog requestId="desktop_health_check-fixture" onClose={() => {}} />,
);
