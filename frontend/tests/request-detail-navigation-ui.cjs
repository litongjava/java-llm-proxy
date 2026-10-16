const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  fs.mkdirSync('artifacts/request-detail', { recursive: true });
  try {
    for (const width of [1280, 640]) {
      await page.setViewportSize({ width, height: 900 });
      await page.goto('http://127.0.0.1:1421/tests/fixtures/request-detail.html');
      const nav = page.getByRole('navigation', { name: 'Request sections' });
      await nav.waitFor();
      assert.equal(await nav.getByRole('button').count(), 8);
      await nav.getByRole('button', { name: 'Request body', exact: true }).click();
      assert.equal(await nav.getByRole('button', { name: 'Request body', exact: true }).getAttribute('aria-current'), 'location');
      const distance = await page.evaluate(() => document.querySelector('#proxy-section-request-body').getBoundingClientRect().top - document.querySelector('.proxy-request-sections').getBoundingClientRect().top);
      assert(Math.abs(distance) < 2);
      await page.getByRole('button', { name: 'Format', exact: true }).click();
      await nav.getByRole('button', { name: 'Formatted prompt', exact: true }).waitFor();
      assert.equal(await nav.getByRole('button').count(), 10);
      await page.waitForFunction(() => document.querySelector('.proxy-formatted-content img')?.naturalWidth > 0);
      assert.equal(await nav.getByRole('button', { name: 'Formatted prompt', exact: true }).getAttribute('aria-current'), 'location');
      assert(await page.locator('.proxy-request-content').evaluate(el => el.scrollWidth <= el.clientWidth));
      await page.screenshot({ path: `artifacts/request-detail/formatted-${width}.png` });
      await nav.getByRole('button', { name: 'Response headers', exact: true }).click();
      assert.equal(await nav.getByRole('button', { name: 'Response headers', exact: true }).getAttribute('aria-current'), 'location');
    }
    assert.deepEqual(errors, []);
    console.log('Request detail navigation, Format updates and embedded image loading passed.');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exit(1); });
