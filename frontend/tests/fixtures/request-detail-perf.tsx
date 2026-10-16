import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import { mockIPC } from '@tauri-apps/api/mocks';
import { RequestDetailDialog } from '../../src/components/RequestDetailDialog';
import '../../src/styles/index.css';
const root = createRoot(document.getElementById('root')!);
localStorage.setItem('easy-cli-proxy-api.locale', 'en');
let current: any;
mockIPC(() => current);
(window as any).runBenchmark = async (raw: string, mode: string) => {
  flushSync(() => root.render(null));
  const startParse = performance.now();
  const envelope = JSON.parse(raw);
  current = envelope.data ?? envelope;
  const parseMs = performance.now() - startParse;
  const startStringify = performance.now();
  JSON.stringify(current);
  const stringifyMs = performance.now() - startStringify;
  if (mode === 'preview') {
    for (const key of ['requestBody', 'upstreamRequestBody', 'upstreamResponseBody', 'responseBody']) {
      current[key] = current[key]?.slice(0, 10000);
    }
  }
  document.getElementById('perf-style')?.remove();
  if (mode === 'nowrap') {
    const style = document.createElement('style'); style.id = 'perf-style';
    style.textContent = '.proxy-request-sections pre {white-space:pre;overflow-wrap:normal}';
    document.head.append(style);
  }
  return await new Promise(resolve => {
    const start = performance.now();
    const observer = new MutationObserver(() => {
      if (!document.querySelector('.proxy-request-sections pre')) return;
      observer.disconnect();
      const committed = performance.now();
      const height = document.querySelector('.proxy-request-sections')!.getBoundingClientRect().height;
      const layout = performance.now();
      requestAnimationFrame(() => requestAnimationFrame(() => resolve({mode,parseMs,stringifyMs,
        mountToCommitMs:committed-start,forcedLayoutMs:layout-committed,
        mountToTwoFramesMs:performance.now()-start,height})));
    });
    observer.observe(document.body, {childList:true,subtree:true});
    root.render(<RequestDetailDialog requestId="benchmark" onClose={() => {}} />);
  });
};
