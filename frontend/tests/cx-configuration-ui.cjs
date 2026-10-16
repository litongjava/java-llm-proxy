const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(String(error)));
    await page.goto('http://127.0.0.1:1421/tests/fixtures/agent-backups.html?client=cx&locale=en');
    await page.waitForFunction(() => document.querySelector('.agent-model-trigger strong')?.textContent === 'gpt-one');
    assert.equal(await page.locator('.agent-client-list button.active strong').textContent(), 'CX');
    await page.locator('.agent-model-trigger').click();
    await page.getByRole('option', { name: 'gpt-two' }).click();
    const buttons = await page.getByRole('button').allTextContents();
    assert(!buttons.some(text => /Restart.*Codex|Native OAuth/.test(text)));
    const update = page.getByRole('button', { name: 'Update Configuration', exact: true });
    await update.click();
    await page.waitForFunction(() => window.fixtureCalls.some(c => ['apply_agent_config', 'update_agent_config'].includes(c.cmd)));
    const writes = await page.evaluate(() => window.fixtureCalls.filter(c => ['apply_agent_config', 'update_agent_config'].includes(c.cmd)));
    assert(writes.length > 0);
    assert(writes.every(c => c.args.client === 'cx' && c.args.oauthConfiguration === false));
    assert.deepEqual(errors, []);
    console.log('PASS: CX is a separate client; model updates target CX and never enable Codex OAuth.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
