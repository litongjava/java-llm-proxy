const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const assert = require('node:assert/strict');

(async () => {
  const root = path.resolve(__dirname, '../..');
  const directory = fs.mkdtempSync(path.join(root, 'target/multi-tauri-'));
  fs.copyFileSync(path.join(root, 'frontend/src-tauri/target/debug/cpa-gui.exe'), path.join(directory, 'cpa-gui.exe'));
  fs.writeFileSync(path.join(directory, 'config.toml'), 'locale = "en"\nport = 18479\nstart-core-on-launch = false\nrun-on-startup = false\nmanagement-secret-key = "multi-desktop-test"\napi-keys = [{key="multi-client-test"}]\n');
  fs.mkdirSync(path.join(directory, 'cpa-core'));
  const backend = spawn('java', ['-jar', path.join(root, 'target/java-llm-proxy-1.0.0.jar'), '--server.port=18479',
    `--proxy.data-dir=${directory}/cpa-core/data`, `--proxy.config=${directory}/cpa-core/config.yaml`,
    '--proxy.management-token=multi-desktop-test', '--proxy.api-key=multi-client-test'], { windowsHide: true, stdio: 'ignore' });
  const { createServer } = await import('vite');
  const server = await createServer({ root: path.join(root, 'frontend'), server: { host: '127.0.0.1', port: 1420, strictPort: true } });
  await server.listen();
  let app;
  let browser;
  try {
    app = spawn(path.join(directory, 'cpa-gui.exe'), [], { cwd: directory, windowsHide: true,
      env: { ...process.env, WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: '--remote-debugging-port=19279' }, stdio: ['ignore', 'pipe', 'pipe'] });
    app.stderr.on('data', data => process.stderr.write(data));
    app.on('exit', code => console.log('Isolated desktop exit:', code));
    for (let attempt = 0; attempt < 100; attempt++) {
      try { browser = await chromium.connectOverCDP('http://127.0.0.1:19279'); break; }
      catch { await new Promise(resolve => setTimeout(resolve, 200)); }
    }
    assert.ok(browser, 'Tauri WebView remote connection');
    const page = browser.contexts()[0].pages()[0];
    await page.waitForFunction(() => window.__TAURI_INTERNALS__);
    await page.getByText('API Access', { exact: true }).first().click();
    await page.locator('.provider-category-panel button').filter({ hasText: 'Multi-Protocol' }).click();
    await page.getByRole('button', { name: 'Add API Key', exact: true }).first().click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Service Name', { exact: true }).fill('Tauri Multi Gateway');
    await dialog.getByLabel('Anthropic Messages', { exact: true }).check();
    await dialog.getByLabel('Anthropic Messages Base URL', { exact: true }).fill('http://127.0.0.1:18480/anthropic');
    await dialog.locator('input[type=password]').first().fill('isolated-test-key');
    await dialog.getByRole('button', { name: 'Save', exact: true }).click();
    await dialog.waitFor({ state: 'hidden' });
    const response = await fetch('http://127.0.0.1:18479/api/settings', { headers: { Authorization: 'Bearer multi-desktop-test' } });
    const settings = (await response.json()).data;
    assert.equal(settings.providers[0].protocolEndpoints.anthropic, 'http://127.0.0.1:18480/anthropic');
    await page.reload();
    await page.locator('.real-provider-row').filter({ hasText: 'Tauri Multi Gateway' }).waitFor();
    await page.screenshot({ path: path.join(root, 'docs/assets/multi-protocol-tauri.png') });
    console.log('PASS: real Tauri IPC saved Multi-Protocol to isolated Java backend and reloaded it');
  } finally {
    if (browser) await browser.close();
    if (app) app.kill();
    backend.kill();
    await server.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
