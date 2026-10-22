import { getCurrentLocale } from './index';

const messages = {
  callbackTitle: ['发送 HTTP 回调请求', 'Send HTTP callback request', 'HTTP コールバックを送信'],
  callbackHelp: ['程序运行在服务器上时，先开始授权；浏览器跳转到 127.0.0.1 后，复制完整地址到此处，由服务器发送给自己的 OAuth 回调端口。', 'Start authorization first. If the app runs on a server, copy the complete 127.0.0.1 callback URL from your browser here. The server will send it to its own OAuth callback port.', '認証を開始し、127.0.0.1 に移動した後の完全な URL をここに貼り付けてください。サーバーが自身の OAuth コールバックポートに送信します。'],
  imageUnavailable: ['图片无法加载', 'Image unavailable', '画像を読み込めません'],
  imageLoading: ['加载图片…', 'Loading image…', '画像を読み込み中…'],
  fullTextHint: ['滚动查看全文；超长行分段显示。使用复制按钮获取完整文本。', 'Scroll to read the full text; long lines are split for display. Use Copy for the complete text.', 'スクロールして全文を確認できます。長い行は分割表示されます。コピーで全文を取得できます。'],
  tool: ['工具', 'Tool', 'ツール'],
  toolAliasHint: ['别名仅在所选工具中生效，其他工具保留原始模型。', 'Aliases apply only to this tool. Other tools keep the original models.', 'エイリアスは選択したツールにのみ適用されます。他のツールは元のモデルを使用します。'],
  workbuddyGlobal: ['WorkBuddy（国际版）', 'WorkBuddy (Global)', 'WorkBuddy（グローバル版）'],
  workbuddyChina: ['WorkBuddy（国内版）', 'WorkBuddy (China)', 'WorkBuddy（中国版）'],
  wechatScan: ['微信扫码登录', 'Scan with WeChat', 'WeChat でスキャン'],
} as const;

export function javaProxyText(key: keyof typeof messages, locale = getCurrentLocale()): string {
  return messages[key][locale.startsWith('zh') ? 0 : locale.startsWith('ja') ? 2 : 1];
}
