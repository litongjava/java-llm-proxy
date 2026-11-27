import { afterEach, beforeEach, describe, expect, it } from 'bun:test';
import { webInvoke } from '../src/services/webBackend';

type FetchCall = { url: string; init: RequestInit };

describe('web mode API access remarks', () => {
  let originalWindow: PropertyDescriptor | undefined;
  let originalFetch: typeof fetch;
  let calls: FetchCall[];
  let response: { status: number; body: unknown };

  beforeEach(() => {
    calls = [];
    response = { status: 200, body: { ok: true, data: null } };
    originalWindow = Object.getOwnPropertyDescriptor(globalThis, 'window');
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      writable: true,
      value: {
        localStorage: { getItem: () => null },
        setTimeout: globalThis.setTimeout,
        clearTimeout: globalThis.clearTimeout,
      },
    });
    originalFetch = globalThis.fetch;
    globalThis.fetch = (async (url: string | URL | Request, init?: RequestInit) => {
      calls.push({ url: String(url), init: init ?? {} });
      return new Response(JSON.stringify(response.body), {
        status: response.status,
        headers: { 'Content-Type': 'application/json' },
      });
    }) as typeof fetch;
  });

  afterEach(() => {
    if (originalWindow) Object.defineProperty(globalThis, 'window', originalWindow);
    else Reflect.deleteProperty(globalThis, 'window');
    globalThis.fetch = originalFetch;
  });

  it('posts resolve queries to the Java backend', async () => {
    response = { status: 200, body: { ok: true, data: ['Primary account'] } };
    const queries = [{
      providerSection: 'codex-api-key',
      providerName: '',
      baseUrl: 'https://api.example.com',
      apiKeys: ['sk-first'],
    }];

    expect(await webInvoke<string[]>('resolve_api_access_remarks', { queries }))
      .toEqual(['Primary account']);
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe('/api/webui/api-access/remarks/resolve');
    expect(calls[0].init.method).toBe('POST');
    expect(JSON.parse(String(calls[0].init.body))).toEqual({ queries });
  });

  it('posts remark updates to the Java backend', async () => {
    const update = {
      providerSection: 'codex-api-key',
      previousRecords: [],
      records: [{ providerName: '', baseUrl: 'https://api.example.com', apiKeys: ['sk-first'] }],
      allRecords: [{ providerName: '', baseUrl: 'https://api.example.com', apiKeys: ['sk-first'] }],
      remark: 'Primary account',
    };

    await webInvoke('save_api_access_remark', { update });
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe('/api/webui/api-access/remarks');
    expect(calls[0].init.method).toBe('POST');
    expect(JSON.parse(String(calls[0].init.body))).toEqual(update);
  });

  it('surfaces backend validation errors', async () => {
    response = { status: 400, body: { ok: false, msg: 'Invalid API access type' } };
    await expect(webInvoke('save_api_access_remark', {
      update: { providerSection: 'invalid', remark: 'x' },
    })).rejects.toThrow('Management API error (400): Invalid API access type');
  });
});
