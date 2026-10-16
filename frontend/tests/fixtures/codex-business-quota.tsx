import React from 'react';
import { createRoot } from 'react-dom/client';
import { I18nProvider, useI18n } from '../../src/i18n';
import { QuotaCard } from '../../src/pages/QuotaPage';
import { AuthFileQuotaPanel } from '../../src/components/AuthFileQuotaPanel';
import { quotaRowsFor } from '../../src/services/quotaService';
import payload from './codex-business-spend-control.json';
import '../../src/styles/index.css';
import '../../src/pages/AuthFileManagementPage.css';

localStorage.setItem('easy-cli-proxy-api.locale', new URLSearchParams(location.search).get('locale') || 'en');
const file = { name: 'codex-business-fixture.json', provider: 'codex', auth_index: 'business-fixture' };
function BusinessQuotaFixture() {
  useI18n();
  const quota = { status: 'success' as const, rows: quotaRowsFor('codex', payload), plan: payload.plan_type };
  return (
    <main className="content" style={{ width: '100%', maxWidth: 1100, padding: 24 }}>
      <section className="page quota-page">
        <h1>Codex Business quota</h1>
        <div className="real-quota-grid">
          <QuotaCard file={file} quota={quota} onRefresh={() => {}} />
        </div>
        <AuthFileQuotaPanel file={file} quota={quota} disabled={false} onRefresh={() => {}} />
      </section>
    </main>
  );
}

createRoot(document.getElementById('root')!).render(
  <I18nProvider><BusinessQuotaFixture /></I18nProvider>,
);
