import { invoke } from '@tauri-apps/api/core';

export type ThinkingAliasEntry = {
  position: ModelAliasPosition;
  sourceModel: string;
  alias: string;
  effort: string | null;
  provider: string;
  kind: string;
  oauthChannel?: string | null;
};

export type ModelAliasPosition = {
  section: string;
  providerIndex: number | null;
  modelIndex: number;
};

export type ThinkingAliasSource = {
  id: string;
  model: string;
  displayName: string | null;
  provider: string;
  kind: string;
  protocol: string;
  reasoningLevels: string[];
};

export type SpeedAliasEntry = {
  position: ModelAliasPosition;
  sourceModel: string;
  alias: string;
  serviceTier: string;
  provider: string;
  kind: string;
  oauthChannel?: string | null;
};

export type AliasListEntry = ThinkingAliasEntry & {
  serviceTier: string | null;
};

export type ModelAliasEditContext = {
  source: ThinkingAliasSource;
  effort: string | null;
  fast: boolean;
  serviceTier?: string | null;
};

export const getThinkingAliases = (args?: { tool?: string }): Promise<ThinkingAliasEntry[]> =>
  invoke<ThinkingAliasEntry[]>('get_thinking_aliases', args);

export const getModelAliasSources = (args?: { tool?: string }): Promise<ThinkingAliasSource[]> =>
  invoke<ThinkingAliasSource[]>('get_model_alias_sources', args);

export const getThinkingAliasSources = (args?: { tool?: string }): Promise<ThinkingAliasSource[]> =>
  invoke<ThinkingAliasSource[]>('get_thinking_alias_sources', args);

export const getSpeedAliases = (args?: { tool?: string }): Promise<SpeedAliasEntry[]> =>
  invoke<SpeedAliasEntry[]>('get_speed_aliases', args);

export const getSpeedAliasSources = (args?: { tool?: string }): Promise<ThinkingAliasSource[]> =>
  invoke<ThinkingAliasSource[]>('get_speed_alias_sources', args);

export const createThinkingAlias = (args: { tool?: string; sourceId: string; alias: string; effort: string; fast: boolean; serviceTier?: 'priority' | 'ultrafast'; originalAlias?: string; originalEntry?: AliasListEntry }): Promise<ThinkingAliasEntry[]> =>
  invoke<ThinkingAliasEntry[]>('create_thinking_alias', args);

export const createSpeedAlias = (args: { tool?: string; sourceId: string; alias: string; serviceTier?: 'priority' | 'ultrafast'; }): Promise<SpeedAliasEntry[]> =>
  invoke<SpeedAliasEntry[]>('create_speed_alias', args);

export const getModelAliasEditSource = (args: { tool?: string; alias: string; entry: AliasListEntry; }): Promise<ModelAliasEditContext> =>
  invoke<ModelAliasEditContext>('get_model_alias_edit_source', args);

export const deleteThinkingAlias = (args: { tool?: string; alias: string; oauthChannel: string | null | undefined; entry: AliasListEntry; }): Promise<ThinkingAliasEntry[]> =>
  invoke<ThinkingAliasEntry[]>('delete_thinking_alias', args);

export const deleteSpeedAlias = (args: { tool?: string; alias: string; oauthChannel: string | null | undefined; entry: AliasListEntry; }): Promise<SpeedAliasEntry[]> =>
  invoke<SpeedAliasEntry[]>('delete_speed_alias', args);
