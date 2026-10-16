import React from 'react';
import { createRoot } from 'react-dom/client';
import { I18nProvider, useI18n } from '../../src/i18n';
import { QuotaCard } from '../../src/pages/QuotaPage';
import { AuthFileQuotaPanel } from '../../src/components/AuthFileQuotaPanel';
import { quotaRowsFor } from '../../src/services/quotaService';
import '../../src/styles/index.css';
import '../../src/pages/AuthFileManagementPage.css';

const params = new URLSearchParams(location.search);
localStorage.setItem('easy-cli-proxy-api.locale', params.get('locale') || 'en');

function AntigravityQuotaFixture() {
  useI18n();
  const scenarios = params.has('exhausted') ? [0] : [0.4, 0];
  return <main className="content" style={{ width: '100%', maxWidth: 1100, padding: 24 }}>
    <section className="page quota-page">
      <h1>Antigravity quota</h1>
      <div className="real-quota-grid">
        {scenarios.map(weekly => {
          const file = { name: weekly === 0 ? 'weekly-exhausted.json' : 'quota-available.json', provider: 'antigravity' };
          const rows = quotaRowsFor('antigravity', { groups: [{
            displayName: 'Gemini Models',
            description: 'Models within this group: Gemini Flash, Gemini Pro',
            buckets: [
              { displayName: 'Weekly Limit Remaining', remainingFraction: weekly },
              { displayName: 'Five Hour Limit Remaining', remainingFraction: 1, resetTime: '2030-01-01T00:00:00Z' },
            ],
          }] });
          const quota = { status: 'success' as const, rows };
          return <div key={file.name}>
            <QuotaCard file={file} quota={quota} onRefresh={() => {}} />
            <AuthFileQuotaPanel file={file} quota={quota} disabled={false} onRefresh={() => {}} />
          </div>;
        })}
      </div>
    </section>
  </main>;
}

createRoot(document.getElementById('root')!).render(
  <I18nProvider><AntigravityQuotaFixture /></I18nProvider>,
);
