import { createRoot } from 'react-dom/client';
import { useState } from 'react';
import { mockIPC } from '@tauri-apps/api/mocks';
import { RequestDetailDialog } from '../../src/components/RequestDetailDialog';
import '../../src/styles/index.css';
localStorage.setItem('easy-cli-proxy-api.locale', 'en');
const requestBody = '😀中文 long body '.repeat(300000) + 'REQUEST_END';
const responseBody = '\n'.repeat(1000000) + 'RESPONSE_END';
const imagePath = 'request-images/' + 'a'.repeat(64) + '.png';
(window as any).imageReads = 0;
Object.defineProperty(navigator, 'clipboard', { value: {writeText: async (text: string) => { (window as any).copied = text; }} });
mockIPC((command, args) => {
  if (command === 'get_proxy_request_image') {
    (window as any).imageReads++;
    return 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ1sAAAAASUVORK5CYII=';
  }
  if (args?.format) return {protocol:'responses',prompt:`Hello\n\n![Image](<${imagePath}>)`,response:'Done'};
  return {id:args?.requestId,method:'POST',path:'/v1/responses',protocol:'responses',status:200,
    requestHeaders:{},upstreamRequestHeaders:{},responseHeaders:{},requestBody,upstreamRequestBody:requestBody,responseBody};
});
function App() {
  const [open,setOpen] = useState(true);
  return <><button onClick={() => setOpen(true)}>Open detail</button>
    {open && <RequestDetailDialog requestId="large" onClose={() => setOpen(false)} />}</>;
}
createRoot(document.getElementById('root')!).render(<App />);
