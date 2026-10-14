// Open tests/fixtures/usage-response-model.html?scenario=deepseek&locale=zh-CN,
// then execute this read-only assertion using dsb js '@this-file.js'.
return (() => {
  const row = document.querySelector('.usage-events-table tbody tr');
  const model = row?.querySelector('.usage-td-model > strong')?.textContent;
  const provider = row?.querySelector('.usage-td-provider strong')?.textContent;
  const cache = row?.querySelector('.usage-td-cache')?.textContent;
  const tooltip = row?.querySelector('.usage-td-model')?.getAttribute('title');
  if (model !== 'deepseek-flash' || provider !== 'deepseek-1') {
    throw new Error(`Unexpected upstream identity: ${model}, ${provider}`);
  }
  if (!cache?.includes('60K') || !cache.includes('96.15%')) {
    throw new Error(`Unexpected cached tokens: ${cache}`);
  }
  if (!tooltip?.includes('claude-sonnet-4-5')) {
    throw new Error('The requested alias is missing from the diagnostic tooltip');
  }
  return { passed: true, model, provider, cache, fixture: true };
})();
