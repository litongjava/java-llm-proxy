import { getWebToken } from './webToken';

type JsonObject = Record<string, unknown>;

const isRecord = (value: unknown): value is JsonObject =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const asRecord = (value: unknown): JsonObject => isRecord(value) ? value : {};

const textOf = (value: unknown): string =>
  typeof value === 'string' ? value : value === undefined || value === null ? '' : String(value);

function authHeaders(): Headers {
  const headers = new Headers();
  const token = getWebToken();
  if (token) headers.set('Authorization', `Bearer ${token}`);
  return headers;
}

type FetchOptions = RequestInit & { timeoutMs?: number };

async function fetchResponse(path: string, options: FetchOptions = {}): Promise<Response> {
  const controller = new AbortController();
  const timeoutMs = Math.max(1_000, Math.min(300_000, options.timeoutMs ?? 30_000));
  const timer = window.setTimeout(() => controller.abort(), timeoutMs);
  const headers = authHeaders();
  new Headers(options.headers).forEach((value, key) => headers.set(key, value));
  try {
    return await fetch(path, { ...options, headers, signal: controller.signal });
  } finally {
    window.clearTimeout(timer);
  }
}

async function readJsonResponse(response: Response): Promise<unknown> {
  const body = await response.text();
  if (!response.ok) {
    let detail = body.trim();
    try {
      const value = JSON.parse(body) as unknown;
      if (isRecord(value)) {
        detail = textOf(value.error) || textOf(value.message) || textOf(value.msg) || detail;
      }
    } catch {
    }
    throw new Error(`Management API error (${response.status})${detail ? `: ${detail}` : ''}`);
  }
  if (!body.trim()) {
    return null;
  }
  let value: unknown;
  try {
    value = JSON.parse(body);
  } catch {
    return body;
  }
  if (isRecord(value) && value.ok === false) {
    throw new Error(textOf(value.msg) || 'Management request failed');
  }
  if (isRecord(value) && value.ok === true) {
    return value.data ?? null;
  }
  return value;
}

function appendQuery(path: string, query?: Record<string, unknown>): string {
  if (!query) return path;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    if (typeof value === 'boolean') params.set(key, value ? 'true' : 'false');
    else params.set(key, String(value));
  }
  const suffix = params.toString();
  return suffix ? `${path}?${suffix}` : path;
}

async function getJson(path: string, query?: Record<string, unknown>, timeoutMs = 30_000): Promise<unknown> {
  const response = await fetchResponse(appendQuery(path, query), {
    method: 'GET',
    headers: { Accept: 'application/json' },
    timeoutMs,
  });
  return readJsonResponse(response);
}

let settingsPromise: Promise<JsonObject> | null = null;

function loadSettings(): Promise<JsonObject> {
  if (!settingsPromise) {
    settingsPromise = getJson('/api/webui/settings').then(asRecord).catch((error) => {
      settingsPromise = null;
      throw error;
    });
  }
  return settingsPromise;
}

const coreStatus = {
  installed: true,
  running: true,
  ready: true,
  starting: false,
  managed: false,
  processId: null,
  currentVersion: 'web',
  installDir: '',
  binaryPath: null,
  message: 'Web server',
  recovery: null,
};

async function requestImage(path: string): Promise<string> {
  const response = await fetchResponse(appendQuery('/api/usage/image', { path }), {
    method: 'GET',
    headers: { Accept: 'image/*' },
  });
  if (!response.ok) {
    throw new Error(`Image request failed (${response.status})`);
  }
  const blob = await response.blob();
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error ?? new Error('Failed to read image'));
    reader.readAsDataURL(blob);
  });
}

function downloadExport(path: unknown, contents: unknown): void {
  const name = textOf(path).split(/[\\/]/).pop() || 'usage-records.csv';
  const blob = new Blob([textOf(contents)], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = name;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1_000);
}

// The Java core serializes 64-bit aggregates as JSON strings; the desktop
// commands expose native numbers, so restore that shape for the shared views.
function numericFields(value: unknown, keys: readonly string[]): JsonObject {
  const record = asRecord(value);
  for (const key of keys) {
    const current = record[key];
    if (current === undefined || current === null || current === '') continue;
    const parsed = typeof current === 'number' ? current : Number(current);
    if (Number.isFinite(parsed)) record[key] = parsed;
  }
  return record;
}

function numericRecords(value: unknown, keys: readonly string[]): JsonObject[] {
  return Array.isArray(value) ? value.map((item) => numericFields(item, keys)) : [];
}

const CATEGORY_NUMERIC_FIELDS = ['requests', 'failures', 'tokens'] as const;
const TOKEN_NUMERIC_FIELDS = [
  'input_tokens', 'output_tokens', 'reasoning_tokens',
  'cache_read_tokens', 'cache_creation_tokens', 'total_tokens',
] as const;

function normalizeUsageOverview(value: unknown): JsonObject {
  const overview = numericFields(value, [
    'totalRequests', 'successCount', 'failureCount', 'canceledCount', 'successRate',
    'inputTokens', 'outputTokens', 'reasoningTokens', 'cacheReadTokens', 'cacheCreationTokens',
    'totalTokens', 'rpm', 'tpm', 'tps', 'tpsSampleCount', 'averageLatencyMs', 'cacheHitRate',
    'estimatedCost', 'pricedRequests',
  ]);
  overview.timeline = numericRecords(overview.timeline, ['requests', 'success', 'failure', 'canceled', 'tokens'])
    .map((point) => {
      point.models = numericRecords(point.models, ['tokens', 'requests']);
      return point;
    });
  return overview;
}

function normalizeUsageAnalysis(value: unknown): JsonObject {
  const analysis = asRecord(value);
  for (const key of ['models', 'providers', 'sources', 'apiKeys', 'upstreams']) {
    analysis[key] = numericRecords(analysis[key], CATEGORY_NUMERIC_FIELDS);
  }
  return analysis;
}

function normalizeUsageEvents(value: unknown): JsonObject {
  const page = numericFields(value, ['total', 'page', 'pageSize', 'totalPages']);
  const items = Array.isArray(page.items) ? page.items : [];
  page.items = items.map((entry) => {
    const record = numericFields(entry, ['latency_ms', 'failure_status']);
    if (record.ttft_ms !== undefined && record.ttft_ms !== null) {
      record.ttft_ms = Number(record.ttft_ms);
    }
    if (isRecord(record.cost)) numericFields(record.cost, ['total']);
    if (isRecord(record.tokens)) numericFields(record.tokens, TOKEN_NUMERIC_FIELDS);
    return record;
  });
  return page;
}

const OAUTH_WEBUI_CALLBACK_PROVIDERS = new Set(['codex', 'claude', 'antigravity', 'xai', 'devin']);

function normalizeOAuthProvider(value: unknown, pluginProvider: boolean): string {
  const key = textOf(value).trim().toLowerCase().replace(/_/g, '-');
  if (!key) throw new Error('Invalid OAuth provider');
  if (pluginProvider) {
    if (!/^[a-z0-9][a-z0-9-]*$/.test(key)) throw new Error('Invalid OAuth provider');
    return key;
  }
  switch (key) {
    case 'anthropic':
      return 'claude';
    case 'anti-gravity':
      return 'antigravity';
    case 'cognition':
      return 'devin';
    case 'muse':
      return 'meta';
    case 'grok':
    case 'x-ai':
    case 'x.ai':
      return 'xai';
    default:
      return key;
  }
}

/** Keeps the byte payload shared with the Tauri upload command. */
function credentialBytes(value: unknown): Uint8Array {
  const data = Array.isArray(value) ? value : [];
  if (!data.every((item) => typeof item === 'number' && Number.isInteger(item) && item >= 0 && item <= 255)) {
    throw new Error('Invalid credential file contents');
  }
  return new Uint8Array(data as number[]);
}

async function startOAuthLogin(args: JsonObject): Promise<unknown> {
  const pluginProvider = args.pluginProvider === true;
  const provider = normalizeOAuthProvider(args.provider, pluginProvider);
  const query: Record<string, unknown> = { provider };
  if (!pluginProvider && OAUTH_WEBUI_CALLBACK_PROVIDERS.has(provider)) {
    query.is_webui = 'true';
  }
  const data = asRecord(await getJson('/v8/management/oauth/auth-url', query));
  const error = textOf(data.error) || textOf(data.error_message) || textOf(data.msg);
  if (error) throw new Error(error);
  const url = textOf(data.url).trim();
  if (!url) throw new Error('Kernel did not return an OAuth login URL');
  const state = textOf(data.state).trim() || null;
  const userCode = textOf(data.user_code).trim() || null;
  const flow = textOf(data.flow).trim().toLowerCase() || null;
  const expiresIn = typeof data.expires_in === 'number' && data.expires_in > 0 ? data.expires_in : null;
  return { url, state, userCode, flow, expiresIn, opened: false, openError: null };
}

async function getOAuthStatus(args: JsonObject): Promise<unknown> {
  const state = textOf(args.state).trim();
  if (!state) throw new Error('OAuth state cannot be empty');
  const data = asRecord(await getJson('/v8/management/oauth/status', { state }));
  return {
    status: textOf(data.status).trim().toLowerCase() || 'wait',
    error: textOf(data.error).trim() || null,
  };
}

async function submitOAuthCallback(args: JsonObject): Promise<unknown> {
  const pluginProvider = args.pluginProvider === true;
  const provider = normalizeOAuthProvider(args.provider, pluginProvider);
  const redirectUrl = textOf(args.redirectUrl).trim();
  if (!redirectUrl) throw new Error('Callback URL cannot be empty');
  const response = await fetchResponse('/v8/management/oauth/callback', {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider, redirect_url: redirectUrl }),
  });
  await readJsonResponse(response);
  return null;
}

async function uploadAuthFile(args: JsonObject): Promise<unknown> {
  const name = textOf(args.name).trim();
  if (!name) throw new Error('Credential filename is required');
  const response = await fetchResponse(appendQuery('/v8/management/credentials', { name }), {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: new TextDecoder().decode(credentialBytes(args.data)),
  });
  return readJsonResponse(response);
}

async function importVertexCredential(args: JsonObject): Promise<unknown> {
  const name = textOf(args.name).trim();
  if (!name) throw new Error('Credential filename is required');
  let credential: unknown;
  try {
    credential = JSON.parse(new TextDecoder().decode(credentialBytes(args.data)));
  } catch {
    throw new Error('Invalid service account JSON');
  }
  const location = textOf(args.location).trim();
  const response = await fetchResponse(appendQuery('/v8/management/oauth/import', { provider: 'vertex' }), {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, location: location || null, credential }),
  });
  return readJsonResponse(response);
}

async function pluginSupport(): Promise<boolean> {
  try {
    const data = asRecord(await getJson('/v8/management/plugins'));
    const enabled = data.pluginsEnabled ?? data.plugins_enabled;
    return enabled !== false;
  } catch {
    return false;
  }
}

async function managementRequest(args: JsonObject): Promise<unknown> {
  const request = asRecord(args.request);
  const method = (textOf(request.method).trim().toUpperCase() || 'GET');
  const path = textOf(request.path).trim();
  if (!path || path.includes('://') || path.includes('..')) {
    throw new Error('Invalid management API path');
  }
  const normalized = path.replace(/^\/+/, '');
  const configMutation = (normalized === 'config' || normalized.startsWith('config/')) && method !== 'GET';
  let target = normalized;
  if (method === 'DELETE' && !configMutation) {
    if (normalized === 'credentials') target = 'credentials/delete';
    else if (normalized === 'oauth/session') target = 'oauth/session/cancel';
    else target = `${normalized}/delete`;
  }
  const writeMethod = configMutation || method === 'PUT' || method === 'PATCH' || method === 'DELETE';
  const headers = new Headers({
    Accept: 'application/json',
    'X-Proxy-Method': method,
  });
  let body: BodyInit | undefined;
  const requestBody = request.body;
  if (configMutation) {
    headers.set('Content-Type', 'application/json');
    body = JSON.stringify({ operation: method, value: requestBody });
  } else if (normalized === 'config.yaml' && typeof requestBody === 'string') {
    headers.set('Content-Type', 'application/yaml');
    body = requestBody;
  } else if (requestBody !== undefined && requestBody !== null) {
    headers.set('Content-Type', 'application/json');
    body = JSON.stringify(requestBody);
  }
  const maxTimeoutMs = normalized === 'credentials/refresh' ? 300_000 : 120_000;
  const timeoutMs = typeof request.timeoutMs === 'number'
    ? Math.max(1_000, Math.min(maxTimeoutMs, request.timeoutMs))
    : undefined;
  const response = await fetchResponse(
    appendQuery(`/v8/management/${target}`, asRecord(request.query)),
    { method: writeMethod ? 'POST' : method, headers, body, timeoutMs },
  );
  return readJsonResponse(response);
}

export async function webInvoke<T>(command: string, args: JsonObject = {}): Promise<T> {
  switch (command) {
    case 'get_core_status':
      return coreStatus as T;
    case 'get_gui_settings':
      return asRecord((await loadSettings()).gui) as T;
    case 'get_core_config_settings':
      return asRecord((await loadSettings()).core) as T;
    case 'get_core_tls_settings':
      return asRecord((await loadSettings()).tls) as T;
    case 'get_core_models':
      return await getJson('/api/webui/models') as T;
    case 'get_usage_collector_status':
      return numericFields(await getJson('/api/webui/usage/status'), ['totalRecords']) as T;
    case 'get_usage_overview':
      return normalizeUsageOverview(await getJson('/api/webui/usage/overview', asRecord(args.query))) as T;
    case 'get_usage_analysis':
      return normalizeUsageAnalysis(await getJson('/api/webui/usage/analysis', asRecord(args.query))) as T;
    case 'get_usage_events':
      return normalizeUsageEvents(await getJson('/api/webui/usage/events', asRecord(args.query))) as T;
    case 'get_proxy_request_detail':
      return await getJson(
        textOf(args.format) === 'true' || args.format === true ? '/api/usage/format' : '/api/usage/detail',
        { id: textOf(args.requestId) },
      ) as T;
    case 'get_proxy_request_image':
      return await requestImage(textOf(args.path)) as T;
    case 'save_usage_events_export':
      downloadExport(args.path, args.contents);
      return null as T;
    case 'management_request':
      return await managementRequest(args) as T;
    case 'get_plugin_support':
      return await pluginSupport() as T;
    case 'start_oauth_login':
      return await startOAuthLogin(args) as T;
    case 'get_oauth_status':
      return await getOAuthStatus(args) as T;
    case 'submit_oauth_callback':
      return await submitOAuthCallback(args) as T;
    case 'list_oauth_browsers':
      return [{ id: 'default', label: 'System Default' }] as T;
    case 'check_codex_oauth_login':
      return null as T;
    case 'upload_auth_file':
      return await uploadAuthFile(args) as T;
    case 'import_vertex_credential':
      return await importVertexCredential(args) as T;
    case 'prepare_management_request':
      return (typeof crypto.randomUUID === 'function'
        ? crypto.randomUUID()
        : `${Date.now()}-${Math.random().toString(16).slice(2)}`) as T;
    case 'cancel_management_request':
      return null as T;
    case 'set_app_locale':
      return textOf(args.locale) as T;
    case 'open_external_url':
    case 'open_oauth_url': {
      const url = textOf(args.url);
      if (url) window.open(url, '_blank', 'noopener,noreferrer');
      return null as T;
    }
    case 'list_custom_themes':
      return { files: [], omitted: 0 } as T;
    case 'open_custom_themes_directory':
      return null as T;
    case 'open_auth_files_directory':
      throw new Error('Web mode cannot open the server credential directory');
    default:
      throw new Error(`Web mode: command "${command}" is not available`);
  }
}
