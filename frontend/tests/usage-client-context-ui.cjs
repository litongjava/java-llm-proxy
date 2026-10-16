const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  fs.mkdirSync('artifacts/client-context', { recursive: true });
  try {
    await page.addInitScript(() => localStorage.setItem('cpa-gui.usage-events-visible-cols.v7', JSON.stringify(['time', 'model', 'provider', 'source', 'key', 'result', 'total', 'cache', 'latency', 'speed', 'cost'])));
    for (const width of [1280, 640]) {
      await page.setViewportSize({ width, height: 900 });
      await page.goto('http://127.0.0.1:1421/tests/fixtures/usage-layout.html?tab=events&locale=en');
      for (const key of ['originator', 'session_id', 'thread_id']) {
        assert.equal(await page.locator(`.usage-th-${key}`).count(), 1);
        assert(await page.locator(`.usage-td-${key}`).first().innerText());
      }
      for (const key of ['source', 'key', 'provider', 'cost']) assert.equal(await page.locator(`.usage-th-${key}`).count(), 0);
      assert.equal(await page.locator('.usage-filter-group .usage-context-filter').count(), 3);
      await page.getByLabel('Originator', { exact: true }).fill('codex-tui');
      await page.getByLabel('Session ID', { exact: true }).fill('session-1');
      await page.getByLabel('Thread ID', { exact: true }).fill('thread-1');
      await page.waitForFunction(() => window.__clientContextQuery?.thread_id === 'thread-1');
      const query = await page.evaluate(() => window.__clientContextQuery);
      assert.equal(query.originator, 'codex-tui');
      assert.equal(query.session_id, 'session-1');
      assert.equal(query.page, 1);
      assert(await page.locator('.usage-filter-group').evaluate(el => el.scrollWidth <= el.clientWidth));
      await page.screenshot({ path: `artifacts/client-context/filters-${width}.png` });
      await page.getByRole('button', { name: 'Clear session filters' }).click();
      await page.waitForFunction(() => !window.__clientContextQuery?.thread_id);
      await page.goto('http://127.0.0.1:1421/tests/fixtures/request-detail.html');
      await page.locator('.proxy-client-context').waitFor();
      assert.equal(await page.locator('.proxy-client-context code').nth(0).innerText(), 'codex-tui');
      assert.equal(await page.locator('.proxy-client-context code').nth(1).innerText(), '01a142df-fc0f-7ac2-9df4-bb63d9d92383');
      assert(await page.locator('.proxy-client-context').evaluate(el => el.scrollWidth <= el.clientWidth));
      await page.screenshot({ path: `artifacts/client-context/detail-${width}.png` });
    }
    assert.deepEqual(errors, []);
    console.log('Client context filters and detail layout passed at 1280px and 640px.');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exit(1); });
