import { describe, expect, it, spyOn } from 'bun:test';
import { nativeQuotaProviders, parseNativeQuota } from '../src/services/nativeQuota';
import { loadQuota, providerForFile } from '../src/services/quotaService';
import { managementApi } from '../src/services/managementApi';

const buddy = (accounts: unknown[]) => ({ code: 0, data: { Response: { Data: { Accounts: accounts } } } });
const pkg = { PackageName: 'Bonus', CycleCapacityRemain: 55, CycleCapacityRemainPrecise: '55.67000031',
  CycleCapacitySize: 100, CapacityRemain: 500, Status: 0 };

describe('native OAuth quota', () => {
  it('recognizes every OAuth provider and keeps Buddy regions separate', () => {
    for (const provider of nativeQuotaProviders) expect(providerForFile({ provider })).toBe(provider);
    expect(providerForFile({ provider: 'codebuddy' })).toBe('buddy');
    expect(providerForFile({ provider: 'unknown' })).toBeNull();
  });

  it('uses precise cycle balances and excludes expired resources', () => {
    for (const provider of ['buddy', 'workbuddy', 'workbuddycn'] as const) {
      const result = parseNativeQuota(provider, buddy([pkg,
        { ...pkg, Status: 3 }, { ...pkg, DeductionEndTime: 100 },
        { ...pkg, ExpiredTime: '2020-01-01T00:00:00Z' },
        { ...pkg, CycleCapacityRemainPrecise: '0' },
      ]), Date.parse('2026-10-16T00:00:00Z'));
      expect(result.rows[0].detail).toBe('55.67 credits');
      expect(result.rows[1].remainingPercent).toBeCloseTo(55.67);
      expect(result.rows[2].detail).toContain('Expired');
    }
  });

  it('distinguishes empty/zero balances from malformed or rejected responses', () => {
    expect(parseNativeQuota('workbuddy', buddy([])).rows[0].detail).toBe('0 credits');
    expect(() => parseNativeQuota('workbuddy', {})).toThrow();
    expect(() => parseNativeQuota('workbuddy', { code: 401, data: buddy([]).data })).toThrow();
    expect(() => parseNativeQuota('workbuddy', buddy([{ Status: 0 }]))).toThrow();
    expect(() => parseNativeQuota('raccoon', { code: 0, data: {} })).toThrow();
  });

  it('parses Cline subscription windows and preserves reset dates', () => {
    const result = parseNativeQuota('cline', { success: true, data: { limits: [
      { type: 'five_hour', percentUsed: 25, resetsAt: '2030-01-01T01:00:00.123456789Z' },
    ] } });
    expect(result.rows[0].remainingPercent).toBe(75);
    expect(result.rows[0].resetAtMs).toBe(Date.parse('2030-01-01T01:00:00.123Z'));
    expect(parseNativeQuota('cline', { success: true, data: { limits: [] } }).status).toBe('unavailable');
  });

  it('parses each original balance shape without adding lifetime or duplicate totals', () => {
    const cases = [
      ['minimax', { base_resp: { status_code: 0 }, details: [{ remaining_amount: '12.5' }], total_count: 1 }, '12.5 credits'],
      ['lobsterai', { code: 0, data: { totalCreditsRemaining: 5297.72, creditItems: [{ label: 'Daily', creditsRemaining: 100 }] } }, '5,297.72 credits'],
      ['raccoon', { code: 0, data: { available_points: 3000, reward_points: 3000 } }, '3,000 credits'],
      ['trae', { user_entitlement_pack_list: [{ entitlement_base_info: { display_desc: 'Daily', quota: { credits_limit: 100 } }, usage: { credits_amount: 25 } }] }, '75 credits'],
      ['qoder', { qoderUsage: { userQuota: { total: 100, used: 40 }, addOnQuota: { remaining: 10 } } }, '70 credits'],
      ['qodercn', { qoderUsage: { userQuota: { total: 100, remaining: 0 } } }, '0 credits'],
      ['codearts', { metrics: [{ name: 'usageTotalPackageCredit', package_credit_remain: 100 }, { name: 'usageBasicPackageCredit', package_credit_remain: 100 }] }, '100 credits'],
      ['loomy', { code: '000000', data: { balance: 30, dailyBalance: 20, availableBalance: 50 } }, '50 credits'],
    ] as const;
    for (const [provider, payload, balance] of cases) {
      expect(parseNativeQuota(provider, payload).rows[0].detail).toBe(balance);
    }
  });

  it('keeps ZCode tokens separate from credits and does not invent granted quota', () => {
    const result = parseNativeQuota('zcode', { code: 0, data: { balances: [
      { show_name: 'GLM', remaining_units: 94539275, total_units: 100000000, unit_type: 'token' },
      { show_name: 'Other', remaining_units: 10, total_units: 20, unit_type: 'credit' },
    ] } });
    expect(result.rows[0].detail).toBe('94,539,275 token · 10 credit');
    expect(parseNativeQuota('zcode', { data: { balances: [] } }).rows[0].detail).toContain('No granted quota');
    expect(parseNativeQuota('qoder', { displayMode: 'enterprise' }).status).toBe('unavailable');
  });

  it('loads through the credential endpoint, detects mismatches, and skips unsupported channels', async () => {
    const post = spyOn(managementApi, 'post').mockResolvedValue({ provider: 'workbuddycn', payload: buddy([pkg]) } as never);
    try {
      const file = { name: 'native-quota-test', provider: 'workbuddycn', auth_index: 'native-quota-index' };
      expect((await loadQuota(file)).status).toBe('success');
      expect(post).toHaveBeenCalledWith('/credentials/quota', { authIndex: 'native-quota-index' }, { timeoutMs: 40_000 });
      expect((await loadQuota({ ...file, provider: 'workbuddy' })).status).toBe('error');
      post.mockClear();
      expect((await loadQuota({ ...file, provider: 'meta' })).status).toBe('unavailable');
      expect((await loadQuota({ ...file, disabled: true })).status).toBe('error');
      expect(post).not.toHaveBeenCalled();
    } finally { post.mockRestore(); }
  });
});
