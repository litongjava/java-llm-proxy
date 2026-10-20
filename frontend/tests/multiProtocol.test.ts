import { expect, test } from 'bun:test';
import { buildProviderGroupRecord, createProviderDraft, providerDraftFromRecord, providerSectionOrder } from '../src/pages/ApiAccessPage';
import { protocolConfiguration, protocolEndpointPreview } from '../src/services/multiProtocol';

test('Multi-Protocol is last and configuration survives group editing', () => {
  expect(providerSectionOrder.at(-1)).toBe('multi-protocol');
  const draft = createProviderDraft('multi-protocol');
  expect(draft.protocolEndpoints).toEqual({});
  draft.name = 'Gateway';
  draft.protocolEndpoints = { responses: 'https://gateway.test/v1/', anthropic: 'https://gateway.test/anthropic' };
  draft.defaultProtocol = 'responses';
  draft.groupKeys![0].value['api-key'] = 'test-key';
  const record = buildProviderGroupRecord('multi-protocol', draft);
  expect(record['base-url']).toBe('https://gateway.test/v1');
  const restored = providerDraftFromRecord('multi-protocol', record);
  restored.defaultProtocol = 'anthropic';
  const edited = buildProviderGroupRecord('multi-protocol', restored, record);
  expect(edited['base-url']).toBe('https://gateway.test/anthropic');
  expect(edited['protocol-endpoints']).toEqual(record['protocol-endpoints']);
  expect(edited.keys).toEqual(record.keys);
});

test('protocol validation rejects missing selections and invalid prefixes', () => {
  expect(() => protocolConfiguration({})).toThrow();
  expect(() => protocolConfiguration({ protocolEndpoints: { anthropic: 'https://test.example' }, defaultProtocol: 'responses' })).toThrow();
  for (const base of ['', 'relative', 'ftp://test.example', 'https://user:password@test.example', 'https://test.example?key=value']) {
    expect(() => protocolConfiguration({ protocolEndpoints: { anthropic: base }, defaultProtocol: 'anthropic' })).toThrow();
  }
  expect(protocolEndpointPreview('anthropic', 'https://test.example/anthropic/')).toBe('https://test.example/anthropic/v1/messages');
});
