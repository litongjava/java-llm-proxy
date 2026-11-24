import { listen } from '@tauri-apps/api/event';
import { isTauri } from './platform';
import type { CoreStatus } from './coreLifecycle';
import type { AppUpdateTask } from './appUpdates';
import type { CoreInstallTask, VersionSourceSettings } from './coreVersions';
import type { CodexSessionRepairProgress } from './codexSessionState';

type Handler<T> = (event: { payload: T }) => void;

const subscribe = <T,>(event: string, handler: Handler<T>): Promise<() => void> =>
  isTauri() ? listen<T>(event, handler) : Promise.resolve(() => {});

export const onCoreStatusChanged = (handler: Handler<CoreStatus>): Promise<() => void> => subscribe('core-status-changed', handler);
export const onAppUpdateProgress = (handler: Handler<AppUpdateTask>): Promise<() => void> => subscribe('app-update-progress', handler);
export const onCoreInstallProgress = (handler: Handler<CoreInstallTask>): Promise<() => void> => subscribe('core-install-progress', handler);
export const onVersionDownloadSourceChanged = (handler: Handler<VersionSourceSettings>): Promise<() => void> => subscribe('version-download-source-changed', handler);
export const onCodexSessionRepairProgress = (handler: Handler<CodexSessionRepairProgress>): Promise<() => void> => subscribe('codex-session-repair-progress', handler);
export const onConfigFilesChanged = (handler: () => void): Promise<() => void> => subscribe('config-files-changed', handler);
export const onUsageRecordsUpdated = (handler: () => void): Promise<() => void> => subscribe('usage-records-updated', handler);
export const onWindowsCloseRequested = (handler: () => void): Promise<() => void> => subscribe('windows-close-requested', handler);
