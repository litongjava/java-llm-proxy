const WEB_TOKEN_KEY = 'cpa-gui.web-token.v1';

export function getWebToken(): string | null {
  try {
    return window.localStorage.getItem(WEB_TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setWebToken(token: string): void {
  try {
    window.localStorage.setItem(WEB_TOKEN_KEY, token);
  } catch {
    // A blocked storage backend only affects persistence, not this session.
  }
}

export function clearWebToken(): void {
  try {
    window.localStorage.removeItem(WEB_TOKEN_KEY);
  } catch {
  }
}
