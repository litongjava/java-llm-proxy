import React from 'react';
import { createRoot } from 'react-dom/client';
import { mockIPC } from '@tauri-apps/api/mocks';
import { I18nProvider } from '../../src/i18n';
import { ApiAccessPage } from '../../src/pages/ApiAccessPage';
import '../../src/styles/index.css';

localStorage.setItem('easy-cli-proxy-api.locale', 'en');
mockIPC((command, args: any) => {
  if (command === 'resolve_api_access_remarks') return args.queries.map(() => '');
  if (command === 'save_api_access_remark' || command === 'set_app_locale') return null;
  if (command === 'management_request') {
    const { request } = args;
    const stored = JSON.parse(localStorage.getItem('multi-protocol-fixture') ?? '[]');
    if (request.path === '/config/api-keys/multi-protocol') {
      if (request.method === 'GET') return stored;
      localStorage.setItem('multi-protocol-fixture', JSON.stringify(request.body));
      return {};
    }
    if (request.path === '/config') return { 'api-keys': { 'multi-protocol': stored } };
    if (request.method === 'GET') return [];
  }
  throw new Error(`Unexpected fixture command: ${command}`);
});
createRoot(document.getElementById('root')!).render(<I18nProvider><div className="app-shell"><aside className="sidebar" /><div className="workspace"><main className="content"><ApiAccessPage /></main></div></div></I18nProvider>);
