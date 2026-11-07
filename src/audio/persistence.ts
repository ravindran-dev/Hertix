import { invoke, isTauri } from "@tauri-apps/api/core";
import type { StateStorage } from "zustand/middleware";
import { useApp } from "../stores/app";
let pending: string | undefined;
let writing: Promise<void> | undefined;
export function flushMusicPersistence(): Promise<void> {
  if (writing) return writing;
  writing = (async () => {
    while (pending !== undefined) {
      const data = pending;
      pending = undefined;
      try {
        await invoke("save_music", { data });
      } catch (e) {
        useApp
          .getState()
          .fail(`Could not save your music library: ${String(e)}`);
        throw e;
      }
    }
  })().finally(() => {
    writing = undefined;
  });
  return writing;
}
/** Native metadata uses the existing SQLite Store; the browser preview stays local. */
export const musicStorage: StateStorage = {
  getItem: (name) => {
    if (!isTauri()) return localStorage.getItem(name);
    return invoke<string | null>("load_music")
      .then((stored) => stored ?? localStorage.getItem(name))
      .catch((e) => {
        useApp
          .getState()
          .fail(`Music library could not be loaded: ${String(e)}`);
        return localStorage.getItem(name);
      });
  },
  setItem: (name, value) => {
    if (!isTauri()) {
      localStorage.setItem(name, value);
      return;
    }
    pending = value;
    void flushMusicPersistence().catch(() => {});
  },
  removeItem: (name) => {
    if (!isTauri()) localStorage.removeItem(name);
  },
};
