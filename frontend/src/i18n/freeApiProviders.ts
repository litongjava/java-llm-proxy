import type { AppLocale } from './index';

const messages = {
  connections: ['接入管理', 'API connections'],
  discover: ['免费模型与额度', 'Free models & credits'],
  title: ['寻找免费 API？', 'Looking for free API access?'],
  description: ['发现免费模型、试用额度与本地推理工具。选择适合你的平台，申请密钥后回到接入管理添加。', 'Explore free models, trial credits and local inference tools. Find a provider, get a key, then add it in API connections.'],
  manage: ['前往接入管理', 'Manage API connections'],
  total: ['个平台与工具', 'providers & tools'],
  all: ['全部', 'All providers'],
  free: ['免费模型 / 免费层', 'Free models / tier'],
  trial: ['试用 / 赠送额度', 'Trial / credits'],
  local: ['本地运行 · 无需账号', 'Local · no account'],
  verify: ['资格待确认', 'Check eligibility'],
  search: ['搜索平台名称或说明…', 'Search providers or descriptions…'],
  results: ['个结果', 'results'],
  clear: ['清除筛选', 'Clear filters'],
  empty: ['没有找到匹配的平台', 'No matching providers'],
  emptyHint: ['试试其他名称，或清除当前筛选。', 'Try another name or clear your filters.'],
  apply: ['访问官网', 'Visit website'],
  download: ['安装与使用', 'Install & get started'],
  policy: ['目录来自源项目，不代表所有平台都永久免费。模型、地区、资格和额度以官网当前政策为准；本地工具仍需要自己的硬件。', 'This directory comes from the source project; not every provider is always free. Models, regions, eligibility and limits follow current provider policies. Local tools require your own hardware.'],
  failed: ['无法打开浏览器，请使用下方官网链接。', 'Could not open the browser. Use the official link below.'],
} as const;

export function freeApiProviderText(key: keyof typeof messages, locale: AppLocale): string {
  return messages[key][locale.startsWith('zh') ? 0 : 1];
}
