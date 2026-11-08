import { flushMusicPersistence } from "./audio/persistence";
import { useEffect, useState, useCallback } from "react";
import { listen } from "@tauri-apps/api/event";
import {
  UserRound,
  Home,
  LayoutGrid,
  Search,
  Library as LibraryIcon,
  MoreHorizontal,
  Download,
  History,
  Settings,
  Info,
  Cloud,
  ArrowUpRight,
  X,
  CircleCheck,
  LoaderCircle,
} from "lucide-react";
import { useApp, perform } from "./stores/app";
import { call, desktop } from "./api";
import type {
  Database,
  EngineVersion,
  Page,
  Job,
  Batch,
  Diagnostics,
} from "./types";
import { MusicService, Player } from "./components/Player";
import LibraryPage, { PlayerSettings } from "./pages/Library";
import Dashboard from "./pages/Dashboard";
import DiscoverySearch from "./pages/Search";
import Downloads from "./pages/Downloads";
import SettingsPage, { EnginePanel } from "./pages/Settings";
import { JobDetails, Modal } from "./components/Common";
import { active, terminal } from "./utils/media";

import Account from "./pages/Account";
import { startAccount, useAccount } from "./account/auth";
import { selectAccount, synchronize } from "./account/sync";
import HomePage from "./pages/Home";

export default function App() {
  const account = useAccount();
  const accountUser = account.user?.id || null;
  useEffect(() => { startAccount(); }, []);
  useEffect(() => { selectAccount(accountUser); void synchronize(); }, [accountUser]);
  useEffect(() => { const timer = window.setInterval(() => void synchronize(), 15000); return () => window.clearInterval(timer); }, []);
  const { db, page, navigate, error, message, clearError, notify, setDb } =
    useApp();
  const [moreOpen, setMoreOpen] = useState(false);
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === ",") {
        e.preventDefault();
        navigate("settings");
      }
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "f") {
        e.preventDefault();
        navigate("search");
        setTimeout(() => document.getElementById("youtube-discovery")?.focus(), 0);
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [navigate]);
  const [selected, setSelected] = useState<string | null>(null);
  const [selectedJob, setSelectedJob] = useState<Job | null>(null);
  useEffect(() => {
    setSelectedJob(null);
    if (selected && !useApp.getState().db?.jobs.some((j) => j.id === selected))
      void perform(async () =>
        setSelectedJob(await call<Job>("get_job", { id: selected })),
      );
  }, [selected]);
  const [versions, setVersions] = useState<EngineVersion[]>([]);
  const [checking, setChecking] = useState(false);
  const [close, setClose] = useState(false);
  const [setup, setSetup] = useState(false);
  const [loading, setLoading] = useState(desktop);
  useEffect(() => {
    if (!moreOpen) return;
    const dismiss = (event: KeyboardEvent) => {
      if (event.key === "Escape") setMoreOpen(false);
    };
    window.addEventListener("keydown", dismiss);
    return () => window.removeEventListener("keydown", dismiss);
  }, [moreOpen]);
  useEffect(() => {
    window.scrollTo({ top: 0, behavior: "instant" });
  }, [page]);
  const refresh = useCallback(async () => {
    if (!desktop) return;
    setChecking(true);
    await perform(async () =>
      setVersions(await call<EngineVersion[]>("engine_versions")),
    );
    setChecking(false);
  }, []);
  useEffect(() => {
    if (!desktop) return;
    let disposed = false;
    const disposers: (() => void)[] = [];
    const load = async () => {
      try {
        const off = await listen<Database>("state:changed", (e) =>
          setDb(e.payload),
        );
        if (disposed) {
          off();
          return;
        }
        disposers.push(off);
        const closeOff = await listen("app:close-requested", () =>
          setClose(true),
        );
        if (disposed) {
          closeOff();
          return;
        }
        disposers.push(closeOff);
        const value = await call<Database>("get_state");
        if (!disposed) {
          setDb(value);
          setSetup(!value.settings.setupComplete);
          void refresh();
        }
      } catch (e) {
        useApp.getState().fail(e);
      } finally {
        if (!disposed) setLoading(false);
      }
    };
    const patches = async () => {
      const off = await listen<Partial<Job> & { id: string }>(
        "state:job",
        (e) => {
          const current = useApp.getState().db;
          if (current)
            setDb({
              ...current,
              jobs: current.jobs.map((j) =>
                j.id === e.payload.id ? { ...j, ...e.payload } : j,
              ),
            });
        },
      );
      if (disposed) off();
      else disposers.push(off);
      const addedOff = await listen<Job[]>("state:jobs-added", (e) => {
        const current = useApp.getState().db;
        if (current) {
          const jobs = [
            ...current.jobs,
            ...e.payload.filter(
              (j) => !current.jobs.some((old) => old.id === j.id),
            ),
          ];
          const finished = new Set(
            jobs
              .filter((j) => terminal(j.status))
              .slice(-200)
              .map((j) => j.id),
          );
          setDb({
            ...current,
            jobs: jobs.filter((j) => !terminal(j.status) || finished.has(j.id)),
          });
        }
      });
      if (disposed) addedOff();
      else disposers.push(addedOff);
      const metricOff = await listen<Diagnostics>("engine:metrics", (e) =>
        useApp.getState().setMetrics(e.payload),
      );
      if (disposed) metricOff();
      else disposers.push(metricOff);
      const errorOff = await listen<string>("engine:error", (e) =>
        useApp.getState().fail(e.payload),
      );
      if (disposed) errorOff();
      else disposers.push(errorOff);
      const batchOff = await listen<Batch>("state:batch", (e) => {
        const current = useApp.getState().db;
        if (current)
          setDb({
            ...current,
            batches: current.batches.map((b) =>
              b.id === e.payload.id ? e.payload : b,
            ),
          });
      });
      if (disposed) batchOff();
      else disposers.push(batchOff);
    };
    void load();
    void patches().catch((e) => useApp.getState().fail(e));
    return () => {
      disposed = true;
      disposers.forEach((f) => f());
    };
  }, [setDb, refresh]);
  useEffect(() => {
    const theme = db?.settings.theme || "dark";
    const media = window.matchMedia("(prefers-color-scheme: light)");
    const apply = () =>
      (document.documentElement.dataset.theme =
        theme === "system" ? (media.matches ? "light" : "dark") : theme);
    apply();
    media.addEventListener("change", apply);
    return () => media.removeEventListener("change", apply);
  }, [db?.settings.theme]);
  const count = db?.jobs.filter((j) => active(j.status)).length || 0;
  const job = db?.jobs.find((j) => j.id === selected) || selectedJob;
  const nav: { page: Page; label: string; Icon: typeof Download }[] = [
    { page: "dashboard", label: "Home", Icon: Home },
    { page: "explore", label: "Explore", Icon: LayoutGrid },
    { page: "library", label: "Library", Icon: LibraryIcon },
    { page: "search", label: "Search", Icon: Search },
  ];
  return (
    <div className="app-shell">
      <MusicService />
      <div className="main-shell">
        <header className="topbar">
          <button className="desktop-brand" onClick={() => navigate("dashboard")} aria-label="Hertix Home">
            <img src="/hertix-logo.png" alt="Hertix" />
          </button>
          <div className="desktop-header-actions">
            <button className="account-avatar" aria-label="Account" title="Account" onClick={() => navigate("account")}>
              {account.user?.user_metadata?.avatar_url || account.user?.user_metadata?.picture ? <img src={String(account.user.user_metadata.avatar_url || account.user.user_metadata.picture)} alt="" /> : account.user ? <span>{String(account.user.user_metadata?.display_name || account.user.email || "U").trim().charAt(0).toUpperCase()}</span> : <UserRound size={19} />}
            </button>
            <div className="desktop-more-anchor">
              <button className={`icon-button more-button ${moreOpen ? "active" : ""}`} title="More" aria-label="More" aria-expanded={moreOpen} onClick={() => setMoreOpen((value) => !value)}><MoreHorizontal size={21} /></button>
              {moreOpen && <div className="desktop-more-menu" role="menu" aria-label="More navigation">
                <div className="desktop-menu-label">HERTIX</div>
                {[
                  ["downloads", "Downloads", Download], ["history", "History", History],
                  ["settings", "Settings", Settings], ["account", "Account", UserRound], ["about", "About Hertix", Info],
                ].map(([target, label, Icon]) => {
                  const ItemIcon = Icon as typeof Download;
                  return <button key={target as string} role="menuitem" className="desktop-menu-item" onClick={() => { navigate(target as Page); setMoreOpen(false); }}><ItemIcon size={17} /><span>{label as string}</span>{target === "downloads" && count > 0 && <small>{count}</small>}</button>;
                })}
                <div className="desktop-menu-divider" />
                <button role="menuitem" className="desktop-menu-item" onClick={() => { void perform(() => call("open_output", { kind: "downloads" })); setMoreOpen(false); }}><Cloud size={17} /><span>Open download folder</span></button>
              </div>}
            </div>
          </div>
        </header>
        <main>
          {!desktop && (
            <div className="browser-notice">
              <Info size={17} />
              Interface preview. Launch the Windows app to connect to the real
              local download engine.
            </div>
          )}
          {error && (
            <div className="alert" role="alert">
              <div>
                <strong>Something needs attention</strong>
                <p>{error}</p>
              </div>
              <button
                className="icon-button"
                aria-label="Dismiss error"
                onClick={clearError}
              >
                <X size={17} />
              </button>
            </div>
          )}
          {message && (
            <div className="notice" role="status">
              <CircleCheck size={17} />
              <p>{message}</p>
              <button
                className="icon-button"
                aria-label="Dismiss notification"
                onClick={() => notify(null)}
              >
                <X size={17} />
              </button>
            </div>
          )}
          {loading ? (
            <div className="loading">
              <LoaderCircle className="spin" size={28} />
              <p>Opening your media hub…</p>
            </div>
          ) : (
            <>
              {page === "account" && <Account />}{" "}
              {page === "dashboard" && <HomePage />} {" "}
              {page === "explore" && <Dashboard onSelect={setSelected} />} {" "}
              {page === "search" && <DiscoverySearch />}
              {page === "library" && <LibraryPage />}
              {page === "playlists" && <LibraryPage playlistPage />}
              {page === "settings" && !db && <PlayerSettings />}
              {page === "downloads" && (
                <Downloads onSelect={setSelected} />
              )}{" "}
              {page === "history" && (
                <Downloads history onSelect={setSelected} />
              )}{" "}
              {page === "settings" &&
                (db ? (
                  <SettingsPage
                    versions={versions}
                    refresh={() => void refresh()}
                    checking={checking}
                  />
                ) : (
                  <div className="card empty">
                    <h2>Desktop settings</h2>
                    <p>
                      Open the Windows application to configure local folders,
                      media engines, and download preferences.
                    </p>
                  </div>
                ))}{" "}
              {page === "about" && (
                <div className="about-page">
                  <div className="about-wordmark">
                    <img src="/hertix-logo-dark.png" alt="Hertix" />
                  </div>
                  <p className="about-tagline">Your music, beautifully in sync.</p>
                  <p>
                    A local Windows media downloader powered by yt-dlp and
                    FFmpeg.
                    <br />
                    Built with Tauri, Rust, React, and TypeScript.
                  </p>
                  <div className="card about-card">
                    <h2>
                      Application version <span className="muted">1.2.0</span>
                    </h2>
                    <EnginePanel
                      versions={versions}
                      busy={checking}
                      refresh={() => void refresh()}
                    />
                  </div>
                  <p>
                    Hertix is licensed under MIT. yt-dlp is released under the
                    Unlicense,
                    <br />
                    with bundled dependencies under their respective licenses.
                    <br />
                    The bundled FFmpeg distribution is GPL; source and license
                    links are in THIRD_PARTY_NOTICES.md.
                  </p>
                  <p className="muted">
                    Only download content you have permission to download and
                    use.
                  </p>
                </div>
              )}
            </>
          )}
          <footer>
            Only download content you have permission to download and use.
            <span>
              <span className="footer-dot" />
              Made for your offline moments
            </span>
          </footer>
        </main>
      </div>
      <nav className="desktop-bottom-nav" aria-label="Main navigation">
        {nav.map(({ page: p, label, Icon }) => (
          <button key={p} className={`desktop-nav-item ${page === p || (p === "library" && page === "playlists") ? "active" : ""}`} aria-current={page === p ? "page" : undefined} onClick={() => navigate(p)}>
            <Icon size={19} strokeWidth={page === p ? 2.3 : 1.9} /><span>{label}</span>
          </button>
        ))}
      </nav>
      <Player />
      <Player mini />
      {job && <JobDetails job={job} onClose={() => setSelected(null)} />}
      {close && (
        <Modal
          title={count ? "Downloads are still active" : "Close Hertix?"}
          onClose={() => setClose(false)}
        >
          <p>
            Keep Hertix in the tray to continue downloading, or exit and retain
            partial files for your next session.
          </p>
          <div className="button-row">
            <button className="secondary" onClick={() => setClose(false)}>
              Keep downloading
            </button>
            <button
              className="secondary"
              onClick={() =>
                void perform(async () => {
                  await call("window_action", { action: "hide" });
                  setClose(false);
                })
              }
            >
              Minimize to tray
            </button>
            <button
              className="primary"
              onClick={() =>
                void perform(async () => {
                  await flushMusicPersistence();
                  await call("window_action", { action: "quit" });
                })
              }
            >
              Exit & pause downloads
            </button>
          </div>
        </Modal>
      )}
      {setup && db && page !== "settings" && (
        <Modal title="Welcome to Hertix" onClose={() => setSetup(false)}>
          <p>
            Everything runs on your computer. Let’s check your media engine
            before the first download.
          </p>
          <EnginePanel
            versions={versions}
            busy={checking}
            refresh={() => void refresh()}
          />
          <div className="destination">
            <Download size={18} />
            <div>
              <small>DOWNLOAD FOLDER</small>
              <span>{db.settings.downloadDir}</span>
            </div>
          </div>
          <div className="button-row">
            <button
              className="secondary"
              onClick={() => {
                setSetup(false);
                navigate("settings");
              }}
            >
              Configure folders & engines
            </button>
            <button
              className="primary"
              disabled={
                checking || versions.length < 3 || versions.some((v) => v.error)
              }
              onClick={() =>
                void perform(async () => {
                  await call("save_settings", {
                    settings: { ...db.settings, setupComplete: true },
                  });
                  setSetup(false);
                })
              }
            >
              Continue <ArrowUpRight size={16} />
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
