import { getPluginSupport, pluginsApi } from './services/plugins';
import { isWebMode } from './services/platform';
import { useEffect, useState } from 'react';
import { collectPluginResourceEntries, PLUGIN_RESOURCES_REFRESH_EVENT } from './services/pluginResources';
import {
  readCachedPluginNavEntries,
  toPluginNavEntry,
  writeCachedPluginNavEntries,
  type PluginNavEntry,
} from './services/pluginNavigation';

export function usePluginNavEntries(coreReady: boolean) {
  const webMode = isWebMode();
  const [entries, setEntries] = useState<PluginNavEntry[]>(() => webMode ? [] : readCachedPluginNavEntries());
  const [fetched, setFetched] = useState(webMode);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const refresh = () => setRevision((value) => value + 1);
    window.addEventListener(PLUGIN_RESOURCES_REFRESH_EVENT, refresh);
    return () => window.removeEventListener(PLUGIN_RESOURCES_REFRESH_EVENT, refresh);
  }, []);

  useEffect(() => {
    if (webMode) {
      setEntries([]);
      setFetched(true);
      return undefined;
    }
    if (!coreReady) {
      setFetched(false);
      return undefined;
    }
    let cancelled = false;
    void (async () => {
      try {
        const supported = await getPluginSupport();
        const data = supported ? await pluginsApi.list() : null;
        if (cancelled) return;
        const next = data?.pluginsEnabled ? collectPluginResourceEntries(data.plugins).map(toPluginNavEntry) : [];
        setEntries(next);
        writeCachedPluginNavEntries(next);
        setFetched(true);
      } catch {
        return;
      }
    })();
    return () => { cancelled = true; };
  }, [coreReady, revision, webMode]);

  return { entries, fetched };
}
