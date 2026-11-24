import { invoke as tauriInvoke, isTauri } from '@tauri-apps/api/core';
import { webInvoke } from './webBackend';

// Keep native transport behind the service boundary for desktop, mocks and server Web UI.
export const invoke = <T>(command: string, args?: Record<string, unknown>): Promise<T> =>
  isTauri() ? tauriInvoke<T>(command, args) : webInvoke<T>(command, args);
