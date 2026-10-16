import type { AppLocale } from './index';

const messages = {
  title: ['寻找免费 API？', 'Looking for free API access?'],
  description: ['可到以下平台官网查看免费模型或免费额度，申请 API Key 后在上方添加渠道。', 'Visit these providers to explore free models or free-tier allowances, then add your API key above.'],
  opencode: ['OpenCode Zen 提供精选模型，部分模型可能限时免费；请查看当前模型价格。', 'OpenCode Zen offers selected models, some of which may be free for a limited time. Check current model pricing.'],
  openrouter: ['OpenRouter 提供标记为 :free 的免费模型，调用次数与速率受到平台限制。', 'OpenRouter offers models marked :free, subject to provider request and rate limits.'],
  cerebras: ['Cerebras 提供受速率和用量限制的免费开发者额度，具体可用模型以账号为准。', 'Cerebras offers a developer free tier with rate and usage limits. Available models depend on your account.'],
  apply: ['前往官网申请', 'Get an API key'],
  policy: ['免费模型与免费额度不同；可用模型、资格、额度及有效期以平台当前政策为准。', 'Free models and free-tier allowances are different. Availability, eligibility, limits and expiry follow each provider’s current policy.'],
  failed: ['无法打开浏览器，请使用下方官网链接。', 'Could not open the browser. Use the official link below.'],
} as const;

export function freeApiProviderText(key: keyof typeof messages, locale: AppLocale): string {
  return messages[key][locale.startsWith('zh') ? 0 : 1];
}
