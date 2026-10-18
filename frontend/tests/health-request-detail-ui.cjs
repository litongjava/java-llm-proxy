const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const path = require('node:path');
(async () => {
  const { createServer } = await import('vite');
  const react = (await import('@vitejs/plugin-react')).default;
  const server = await createServer({ configFile: false, root: path.resolve(__dirname, '..'),
    plugins: [react()], logLevel: 'error', server: { host: '127.0.0.1', port: 0, watch: null } });
  let browser;
  try {
    await server.listen();
    browser = await chromium.launch({ channel: 'msedge', headless: true });
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    const base = `http://127.0.0.1:${server.httpServer.address().port}/tests/fixtures/health-request-detail.html`;
    await page.goto(base + '?saved');
    await page.locator('#proxy-section-request-body').waitFor();
    assert.match(await page.locator('#proxy-section-request-body').textContent(), /Reply with OK/);
    assert.match(await page.locator('#proxy-section-response-body').textContent(), /message_stop/);
    assert.equal(await page.getByText(/Only its summary was saved|仅保存了检查摘要/).count(), 0);
    await page.goto(base);
    await page.getByText(/Only its summary was saved|仅保存了检查摘要/).waitFor();
    assert.deepEqual(errors, []);
    console.log('Health detail UI: saved request/response and historical summary passed');
  } finally { await browser?.close(); await server.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
