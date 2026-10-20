const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');

(async () => {
  const { createServer } = await import('vite');
  const react = (await import('@vitejs/plugin-react')).default;
  const server = await createServer({ configFile: false, root: path.resolve(__dirname, '..'),
    plugins: [react()], logLevel: 'error', server: { host: '127.0.0.1', port: 0, watch: null } });
  await server.listen();
  const browser = await chromium.launch({ headless: false, executablePath: process.env.CHROME_PATH });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } });
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/tests/fixtures/multi-protocol.html`);
    const category = page.locator('.provider-category-panel button').filter({ hasText: 'Multi-Protocol' });
    assert.match(await page.locator('.provider-category-panel button').last().innerText(), /Multi-Protocol/);
    await category.click();
    await page.getByRole('button', { name: 'Add API Key', exact: true }).first().click();
    const dialog = page.getByRole('dialog');
    await dialog.locator('.provider-dialog-basic-grid input').first().fill('Multi Gateway');
    await dialog.getByLabel('OpenAI Responses', { exact: true }).check();
    await dialog.getByLabel('OpenAI Responses Base URL', { exact: true }).fill('https://gateway.example/v1');
    await dialog.getByLabel('Anthropic Messages', { exact: true }).check();
    await dialog.getByLabel('Anthropic Messages Base URL', { exact: true }).fill('https://gateway.example/anthropic');
    await dialog.locator('input[type=password]').first().fill('test-key');
    const assets = path.resolve(__dirname, '../../docs/assets');
    fs.mkdirSync(assets, { recursive: true });
    await page.screenshot({ path: path.join(assets, 'multi-protocol-dialog.png') });
    await dialog.getByRole('button', { name: 'Save', exact: true }).click();
    await dialog.waitFor({ state: 'hidden' });
    await page.reload();
    await page.locator('.real-provider-row').filter({ hasText: 'Multi Gateway' }).getByRole('button', { name: 'Edit', exact: true }).click();
    await page.getByLabel('Default conversion protocol').selectOption('anthropic');
    await page.getByRole('dialog').getByRole('button', { name: 'Save', exact: true }).click();
    await page.getByRole('dialog').waitFor({ state: 'hidden' });
    const stored = await page.evaluate(() => JSON.parse(localStorage.getItem('multi-protocol-fixture')));
    assert.equal(stored[0]['default-protocol'], 'anthropic');
    assert.equal(stored[0]['base-url'], 'https://gateway.example/anthropic');
    assert.deepEqual(errors, []);
    await page.screenshot({ path: path.join(assets, 'multi-protocol-list.png') });
    console.log('PASS: visible browser create, save, reload, edit and screenshots (mock IPC)');
  } finally { await browser.close(); await server.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
