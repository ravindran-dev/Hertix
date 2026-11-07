import { create } from "zustand";
import type { Database, Page, Diagnostics } from "../types";
interface State {
  db: Database | null;
  metrics: Diagnostics | null;
  setMetrics: (metrics: Diagnostics) => void;
  page: Page;
  error: string | null;
  message: string | null;
  setDb: (db: Database) => void;
  navigate: (page: Page) => void;
  fail: (error: unknown) => void;
  notify: (message: string | null) => void;
  clearError: () => void;
}
export const useApp = create<State>((set) => ({
  db: null,
  metrics: null,
  setMetrics: (metrics) => set({ metrics }),
  page: ([
    "dashboard",
    "search",
    "downloads",
    "library",
    "playlists",
    "history",
    "settings",
    "about",
  ].includes(localStorage.getItem("ythub-page") || "")
    ? localStorage.getItem("ythub-page")
    : "dashboard") as Page,
  error: null,
  message: null,
  setDb: (db) => set({ db }),
  navigate: (page) => {
    localStorage.setItem("ythub-page", page);
    set({ page });
  },
  fail: (error) =>
    set({ error: String(error instanceof Error ? error.message : error) }),
  notify: (message) => set({ message }),
  clearError: () => set({ error: null }),
}));
export async function perform<T>(
  action: () => Promise<T>,
): Promise<T | undefined> {
  useApp.getState().clearError();
  try {
    return await action();
  } catch (e) {
    useApp.getState().fail(e);
  }
}
