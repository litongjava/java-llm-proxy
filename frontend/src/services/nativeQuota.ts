import { getCurrentLocale } from '../i18n';
import { isRecord, readString } from './managementApi';
import type { QuotaRow, QuotaState } from './quotaService';

export const nativeQuotaProviders = ['buddy', 'workbuddy', 'workbuddycn', 'cline', 'minimax',
  'lobsterai', 'trae', 'raccoon', 'codearts', 'qoder', 'qodercn', 'zcode', 'loomy', 'meta'] as const;
export type NativeQuotaProvider = typeof nativeQuotaProviders[number];
export const isNativeQuotaProvider = (value: string): value is NativeQuotaProvider =>
  (nativeQuotaProviders as readonly string[]).includes(value);
const text = (zh: string, en: string) => getCurrentLocale().startsWith('zh') ? zh : en;
const record = (v: unknown): Record<string, unknown> => isRecord(v) ? v : {};
const num = (v: unknown): number | undefined => {
  if ((typeof v !== 'number' && typeof v !== 'string') || (typeof v === 'string' && !v.trim())) return undefined;
  const n = Number(v);
  return Number.isFinite(n) ? n : undefined;
};
const required = (v: unknown): number => {
  const n = num(v);
  if (n === undefined) throw new Error(text('额度响应缺少有效数值', 'Quota response omitted a valid amount'));
  return n;
};
const list = (v: unknown): Record<string, unknown>[] => {
  if (!Array.isArray(v) || v.some(item => !isRecord(item))) {
    throw new Error(text('无法识别额度响应', 'Unrecognized quota response'));
  }
  return v;
};
const amount = (v: number) => v.toLocaleString(getCurrentLocale(), { maximumFractionDigits: 2 });
const date = (v: unknown): number | undefined => {
  if (typeof v !== 'string' || !v) return undefined;
  const n = Date.parse(v.replace(' ', 'T'));
  return Number.isFinite(n) ? n : undefined;
};
export const unsupportedNativeQuota = (): QuotaState => ({ status: 'unavailable', rows: [],
  message: text('该渠道暂未接入可靠的额度查询接口，请到官方账户页面查看。',
    'A reliable quota endpoint is not integrated for this provider. Check your official account page.') });

/** Parsers follow channel-pack's read-only balance endpoints; never infer zero from missing fields. */
export function parseNativeQuota(provider: NativeQuotaProvider, payload: unknown, now = Date.now()): QuotaState {
  if (provider === 'meta') return unsupportedNativeQuota();
  if (!isRecord(payload)) throw new Error(text('无法识别额度响应', 'Unrecognized quota response'));
  const root = payload;
  const code = root.code ?? record(root.base_resp).status_code;
  if ((code !== undefined && Number(code) !== 0) || root.success === false || root.error) {
    throw new Error(readString(root, 'message', 'msg', 'error_msg') || readString(record(root.base_resp), 'status_msg')
      || text('上游额度查询失败', 'Upstream quota query failed'));
  }
  const data = isRecord(root.data) ? root.data : root;
  const rows: QuotaRow[] = [];
  const totals = new Map<string, number>();
  const credits = text('积分', 'credits');
  const remainingLabel = text('剩余', 'Remaining');
  const expiredLabel = text('已过期', 'Expired');
  const add = (label: string, remaining: number, total?: number, unit = credits, expiry?: number, active = true) => {
    const usable = active && !(expiry && expiry <= now);
    if (usable) totals.set(unit, (totals.get(unit) ?? 0) + remaining);
    rows.push({ label, remainingPercent: usable && total !== undefined && total > 0
      ? Math.max(0, Math.min(100, remaining / total * 100)) : null,
    detail: `${usable ? remainingLabel : expiredLabel}: ${amount(remaining)}${total === undefined ? '' : ` / ${amount(total)}`} ${unit}`
      + (expiry ? ` · ${text('到期', 'Expires')}: ${new Date(expiry).toLocaleString(getCurrentLocale())}` : '') });
  };
  const precise = (item: Record<string, unknown>, key: string) => num(item[`${key}Precise`]) ?? required(item[key]);

  switch (provider) {
    case 'buddy': case 'workbuddy': case 'workbuddycn': {
      if (Number(root.code) !== 0) throw new Error('Invalid Buddy quota response');
      for (const item of list(record(record(data.Response).Data).Accounts)) {
        const expiry = num(item.DeductionEndTime) || date(item.ExpiredTime);
        const expiredAt = date(item.ExpiredTime);
        const active = Number(item.Status) !== 3 && !(expiredAt !== undefined && expiredAt <= now);
        add(readString(item, 'PackageName', 'SubProductName', 'PackageCode') || credits,
          precise(item, 'CycleCapacityRemain'), precise(item, 'CycleCapacitySize'), credits, expiry, active);
      }
      break;
    }
    case 'cline': {
      for (const item of list(data.limits)) {
        const used = required(item.percentUsed);
        rows.push({ label: readString(item, 'type'), remainingPercent: Math.max(0, Math.min(100, 100 - used)),
          resetAtMs: date(item.resetsAt), detail: `${text('已用', 'Used')}: ${amount(used)}%` });
      }
      return { status: rows.length ? 'success' : 'unavailable', rows,
        message: rows.length ? undefined : text('该账户没有订阅额度窗口', 'This account has no subscription quota windows'), fetchedAt: now };
    }
    case 'minimax': {
      if (data.details === undefined && num(data.total_count) === undefined) list(undefined);
      for (const item of list(data.details ?? [])) {
        add(readString(item, 'name', 'credit_type') || credits, required(item.remaining_amount));
      }
      break;
    }
    case 'lobsterai': {
      const balance = required(data.totalCreditsRemaining);
      for (const item of list(data.creditItems ?? [])) {
        add(readString(item, 'label', 'type') || credits, required(item.creditsRemaining), undefined, credits, date(item.expiresAt));
      }
      totals.set(credits, balance);
      break;
    }
    case 'raccoon': {
      const balance = required(data.available_points);
      for (const [key, label] of [['reward_points', text('奖励积分', 'Reward credits')],
        ['daily_points', text('每日积分', 'Daily credits')], ['monthly_points', text('会员积分', 'Membership credits')],
        ['topup_points', text('充值积分', 'Purchased credits')]]) {
        if (num(data[key]) !== undefined) add(label, required(data[key]));
      }
      totals.set(credits, balance);
      break;
    }
    case 'trae': {
      for (const item of list(root.user_entitlement_pack_list)) {
        const base = record(item.entitlement_base_info);
        const total = required(record(base.quota).credits_limit);
        if (total <= 0) continue;
        add(readString(base, 'display_desc') || readString(item, 'display_desc') || credits,
          total - required(record(item.usage).credits_amount ?? 0), total, credits, (num(item.expire_time) || 0) * 1000 || undefined);
      }
      break;
    }
    case 'qoder': case 'qodercn': {
      if (root.displayMode === 'enterprise') return { status: 'unavailable', rows: [],
        message: text('企业账户未返回额度数字，请到官方控制台查看。', 'Enterprise quota amounts are available in the official console.'), fetchedAt: now };
      const usage = record(root.qoderUsage);
      const packages = [usage.userQuota, usage.addOnQuota, ...(Array.isArray(usage.dedicatedResourcePackages) ? usage.dedicatedResourcePackages : [])];
      for (const raw of packages) {
        if (!isRecord(raw)) continue;
        const total = num(raw.total);
        const remaining = num(raw.remaining) ?? (total !== undefined && num(raw.used) !== undefined ? total - required(raw.used) : undefined);
        if (remaining === undefined) continue;
        add(readString(raw, 'name', 'id') || (raw === usage.userQuota ? text('套餐额度', 'Plan quota') : credits),
          Math.max(0, remaining), total, credits, date(raw.expiresAt ?? raw.expires_at));
      }
      if (!rows.length) list(undefined);
      break;
    }
    case 'zcode': {
      if (data.displayMode === 'enterprise') return { status: 'unavailable', rows: [],
        message: text('企业账户未返回额度数字', 'Enterprise account did not return quota amounts'), fetchedAt: now };
      for (const item of list(data.balances)) {
        add(readString(item, 'show_name', 'plan_id') || 'ZCode', required(item.remaining_units ?? item.available_units), num(item.total_units),
          readString(item, 'unit_type') || text('单位', 'units'), (num(item.expires_at) || 0) * 1000 || undefined);
      }
      if (!rows.length) return { status: 'success', rows: [{ label: 'ZCode', remainingPercent: null,
        detail: text('当前无已领取额度；可领取活动请到官方客户端查看。', 'No granted quota. Check the official client for claimable offers.') }], fetchedAt: now };
      break;
    }
    case 'codearts': {
      for (const item of list(data.metrics)) {
        const name = readString(item, 'name');
        if (!['usageTotalPackageCredit', 'usageBasicPackageCredit', 'usageOnDemandPackageCredit', 'usageBonusPackageCredit'].includes(name)) continue;
        add(name, required(item.package_credit_remain), num(item.package_credit_amount));
      }
      const total = list(data.metrics).find(item => item.name === 'usageTotalPackageCredit');
      if (total) totals.set(credits, required(total.package_credit_remain));
      if (!rows.length) return { status: 'unavailable', rows: [],
        message: text('该账户未返回积分额度', 'This account did not return credit quota'), fetchedAt: now };
      break;
    }
    case 'loomy': {
      const permanent = required(data.balance);
      const daily = num(data.dailyBalance) ?? 0;
      add(text('永久积分', 'Permanent credits'), permanent);
      add(text('每日赠送', 'Daily credits'), daily, num(data.dailyQuota));
      totals.set(credits, num(data.availableBalance) ?? permanent + daily);
      break;
    }
  }
  if (!totals.size) totals.set(credits, 0);
  const balance = [...totals].map(([unit, value]) => `${amount(value)} ${unit}`).join(' · ');
  rows.unshift({ label: text('可用余额', 'Available balance'), remainingPercent: null, detail: balance });
  return { status: 'success', rows, fetchedAt: now };
}
