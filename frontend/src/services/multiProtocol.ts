import { providerText } from '../i18n/providerTemplate';

export const protocolLabels = {
  openai: 'OpenAI Chat Completions',
  responses: 'OpenAI Responses',
  anthropic: 'Anthropic Messages',
  gemini: 'Gemini',
} as const;
export type MultiProtocol = keyof typeof protocolLabels;
export type ProtocolDraft = { protocolEndpoints?: Partial<Record<MultiProtocol, string>>; defaultProtocol?: string };

export function protocolConfiguration(draft: ProtocolDraft) {
  const endpoints = draft.protocolEndpoints ?? {};
  const selected = Object.keys(endpoints);
  if (!selected.length || !draft.defaultProtocol || !selected.includes(draft.defaultProtocol)) {
    throw new Error(providerText('protocolRequired'));
  }
  const normalized: Record<string, string> = {};
  for (const [protocol, base] of Object.entries(endpoints)) {
    try {
      const url = new URL(base.trim());
      if (!(protocol in protocolLabels) || !['http:', 'https:'].includes(url.protocol)
          || url.username || url.password || url.search || url.hash) throw new Error();
      normalized[protocol] = base.trim().replace(/\/+$/, '');
    } catch { throw new Error(providerText('endpointRequired')); }
  }
  return { 'protocol-endpoints': normalized, 'default-protocol': draft.defaultProtocol,
    'base-url': normalized[draft.defaultProtocol] };
}

export function protocolEndpointPreview(protocol: MultiProtocol, base: string) {
  let prefix = base.trim().replace(/\/+$/, '');
  if (!prefix) return '';
  if (protocol === 'anthropic' && !prefix.endsWith('/v1')) prefix += '/v1';
  return prefix + { openai: '/chat/completions', responses: '/responses', anthropic: '/messages',
    gemini: '/{model}:generateContent' }[protocol];
}
