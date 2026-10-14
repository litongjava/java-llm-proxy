import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { mockIPC } from '@tauri-apps/api/mocks';
import { I18nProvider } from '../../src/i18n';
import { QuotaCard } from '../../src/pages/QuotaPage';
import { AuthFileQuotaPanel } from '../../src/components/AuthFileQuotaPanel';
import { idleQuota, loadQuota, type QuotaState } from '../../src/services/quotaService';
import '../../src/styles/index.css';

localStorage.setItem('easy-cli-proxy-api.locale', 'zh-CN');
mockIPC((command, args) => {
  if (command === 'management_request') {
    const request = (args as { request: { path: string } }).request;
    if (request.path !== '/requests/api-call') throw new Error('Unexpected management request');
    return { status_code: 200, body: '{}' };
  }
  return null;
});

const file = { name: 'kimi-empty-response.json', provider: 'kimi', auth_index: 'fixture' };
function Fixture() {
  const [quota, setQuota] = useState<QuotaState>(idleQuota);
  const [requests, setRequests] = useState(0);
  const refresh = async () => {
    setQuota({ status: 'loading', rows: [] });
    setQuota(await loadQuota(file));
    setRequests(count => count + 1);
  };
  return <main className="content" style={{ maxWidth: 1000, margin: '32px auto' }}>
    <h1>Kimi 额度查询：空响应回归验证</h1>
    <p>测试数据：HTTP 200，响应体 {'{}'}。不使用真实账号凭据。</p>
    <p data-testid="request-count">查询次数：{requests}</p>
    <div className="real-quota-grid">
      <QuotaCard file={file} quota={quota} onRefresh={() => void refresh()} />
    </div>
    <AuthFileQuotaPanel file={file} quota={quota} disabled={false} onRefresh={() => void refresh()} />
  </main>;
}

createRoot(document.getElementById('root')!).render(<I18nProvider><Fixture /></I18nProvider>);
