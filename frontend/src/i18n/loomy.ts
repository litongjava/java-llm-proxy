import type { AppLocale } from './index';

const messages = {
  title: ['Loomy 短信登录', 'Loomy SMS sign-in'],
  hint: ['使用中国大陆手机号登录讯飞 Loomy。登录有效期约 14 天，过期后需重新登录。', 'Sign in to Xunfei Loomy with a mainland China mobile number. Sessions last about 14 days; sign in again after expiry.'],
  open: ['登录 / 添加账号', 'Sign in / Add account'],
  phone: ['手机号', 'Mobile number'],
  code: ['短信验证码', 'SMS code'],
  send: ['发送验证码', 'Send code'],
  sent: ['验证码已发送，请在 5 分钟内完成登录。', 'Code sent. Complete sign-in within 5 minutes.'],
  login: ['登录并保存', 'Sign in and save'],
  success: ['登录成功，账号已保存。', 'Signed in. Account saved.'],
  invalidPhone: ['请输入有效的中国大陆手机号。', 'Enter a valid mainland China mobile number.'],
  invalidCode: ['请先向当前手机号发送验证码，再输入收到的验证码。', 'Send a code to the current number first, then enter the code you receive.'],
  invalidResponse: ['服务端未返回有效的登录结果，请重试。', 'The server returned an invalid sign-in result. Please retry.'],
  failed: ['操作失败，请重试。', 'The request failed. Please retry.'],
} as const;

export function loomyText(key: keyof typeof messages, locale: AppLocale): string {
  return messages[key][locale.startsWith('zh') ? 0 : 1];
}
