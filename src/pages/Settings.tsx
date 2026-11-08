import { PlayerSettings } from "./Library";
import { useState, useEffect, useRef } from "react";
import type { Settings as SettingsType, EngineVersion } from "../types";
import { call, choose, confirmAction } from "../api";
import { useApp, perform } from "../stores/app";
import {
  FolderOpen,
  Save,
  RotateCcw,
  RefreshCw,
  LoaderCircle,
  Check,
  AlertCircle,
} from "lucide-react";
import { bytes } from "../utils/media";
import Diagnostics from "../components/Diagnostics";

export function EnginePanel({
  versions,
  busy,
  refresh,
}: {
  versions: EngineVersion[];
  busy: boolean;
  refresh: () => void;
}) {
  return (
    <>
      <div className="engine-list">
        {versions.map((v) => (
          <div className="engine-row" key={v.name}>
            <span className={`engine-status ${v.error ? "bad" : "good"}`}>
              {v.error ? <AlertCircle size={17} /> : <Check size={17} />}
            </span>
            <div>
              <strong>{v.name}</strong>
              <small>{v.version || v.error}</small>
              <small className="muted wrap">{v.path}</small>
            </div>
          </div>
        ))}
      </div>
      <button className="secondary" disabled={busy} onClick={refresh}>
        {busy ? (
          <LoaderCircle className="spin" size={15} />
        ) : (
          <RefreshCw size={15} />
        )}{" "}
        {busy ? "Checking engines…" : "Check versions"}
      </button>
    </>
  );
}
export default function SettingsPage({
  versions,
  refresh,
  checking,
}: {
  versions: EngineVersion[];
  refresh: () => void;
  checking: boolean;
}) {
  const db = useApp((s) => s.db)!;
  const [s, setS] = useState<SettingsType>(db.settings);
  const [query, setQuery] = useState("");
  const settingsRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const labels = settingsRef.current?.querySelectorAll<HTMLElement>(
      ".setting-field,.setting-toggle",
    );
    labels?.forEach((label) => {
      label.hidden = !label.textContent
        ?.toLowerCase()
        .includes(query.toLowerCase());
    });
  }, [query]);
  const [saving, setSaving] = useState(false);
  const [updating, setUpdating] = useState(false);
  const [free, setFree] = useState<{
    download: number;
    temp: number;
    zip: number;
  } | null>(null);
  const change = <K extends keyof SettingsType>(k: K, v: SettingsType[K]) =>
    setS((prev) => ({ ...prev, [k]: v }));
  const text = (
    key: keyof SettingsType,
    label: string,
    note?: string,
    folder?: boolean,
  ) => (
    <label className="setting-field" key={key}>
      <span>{label}</span>
      <div className="input-action">
        <input
          value={String(s[key])}
          type={key === "proxy" ? "password" : "text"}
          autoComplete="off"
          onChange={(e) => change(key, e.target.value as never)}
        />
        {folder !== undefined && (
          <button
            type="button"
            className="icon-button"
            aria-label={`Choose ${label}`}
            title={`Choose ${label}`}
            onClick={() =>
              void perform(async () => {
                const p = await choose(folder);
                if (p) change(key, p as never);
              })
            }
          >
            <FolderOpen size={16} />
          </button>
        )}
      </div>
      {note && <small>{note}</small>}
    </label>
  );
  const toggle = (key: keyof SettingsType, label: string, note?: string) => (
    <label className="setting-toggle" key={key}>
      <div>
        <span>{label}</span>
        {note && <small>{note}</small>}
      </div>
      <input
        type="checkbox"
        checked={Boolean(s[key])}
        onChange={(e) => change(key, e.target.checked as never)}
      />
    </label>
  );
  const select = (
    key: keyof SettingsType,
    label: string,
    options: [string, string][],
  ) => (
    <label className="setting-field" key={key}>
      <span>{label}</span>
      <select
        value={String(s[key])}
        onChange={(e) => change(key, e.target.value as never)}
      >
        {options.map(([v, l]) => (
          <option key={v} value={v}>
            {l}
          </option>
        ))}
      </select>
    </label>
  );
  const number = (
    key:
      | "concurrency"
      | "retries"
      | "fragments"
      | "socketTimeout"
      | "ffmpegConcurrency"
      | "hostConcurrency"
      | "bandwidthLimit"
      | "retryBackoff"
      | "progressHz"
      | "diskSafetyMb"
      | "fragmentBudget"
      | "prefetch",
    label: string,
    min: number,
    max: number,
  ) => (
    <label className="setting-field" key={key}>
      <span>{label}</span>
      <input
        type="number"
        min={min}
        max={max}
        value={s[key]}
        onChange={(e) => change(key, Number(e.target.value))}
      />
    </label>
  );
  const save = async () => {
    setSaving(true);
    await perform(async () => {
      await call("save_settings", { settings: s });
      useApp
        .getState()
        .notify("Settings saved. New downloads will use these preferences.");
    });
    setSaving(false);
  };
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">MAKE YOURSELF AT HOME</div>
          <h1>
            Settings<span className="accent">.</span>
          </h1>
          <p>Your media workflow, just the way you like it.</p>
        </div>
        <button
          className="primary"
          disabled={saving}
          onClick={() => void save()}
        >
          {saving ? (
            <LoaderCircle className="spin" size={16} />
          ) : (
            <Save size={16} />
          )}{" "}
          {saving ? "Saving…" : "Save changes"}
        </button>
      </div>
      <label className="search-field settings-search">
        <input
          aria-label="Search settings"
          placeholder="Search download and engine settings…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </label>
      <PlayerSettings />
      <div ref={settingsRef}>
        <div className="settings-grid">
          <Diagnostics />
          <section className="card settings-card">
            <h2>General</h2>
            <p className="muted">Appearance, storage, and everyday behavior.</p>
            {select("theme", "Theme", [
              ["dark", "Dark"],
              ["light", "Light"],
              ["system", "Use system preference"],
            ])}
            {text("downloadDir", "Download folder", undefined, true)}
            {text(
              "tempDir",
              "Temporary files folder",
              "Partial files are retained for safe continuation.",
              true,
            )}
            {text("zipDir", "Completed ZIP folder", undefined, true)}
            {number(
              "concurrency",
              "Concurrent downloads",
              1,
              s.advancedConcurrency ? 12 : 6,
            )}
            {number(
              "ffmpegConcurrency",
              "Concurrent conversions / merges",
              1,
              4,
            )}
            {number("hostConcurrency", "YouTube host concurrency", 1, 12)}
            {number(
              "bandwidthLimit",
              "Total download limit (bytes/s; 0 = unlimited)",
              0,
              1000000000,
            )}
            {toggle(
              "adaptiveConcurrency",
              "Adapt concurrency to throttling and resource pressure",
            )}
            {toggle("autoStart", "Start downloads automatically")}
            {toggle("notifications", "Windows notifications")}
            {toggle("startWithWindows", "Start Hertix with Windows")}
            {toggle("startMinimized", "Start in tray at Windows startup")}
            {text(
              "filenameTemplate",
              "Filename template",
              "Example: %(title).150B [%(id)s].%(ext)s. Path separators are not allowed.",
            )}
            {select("duplicate", "If a file already exists", [
              ["skip", "Skip existing (safe default)"],
              ["rename", "Create a new unique filename"],
              ["overwrite", "Overwrite existing"],
            ])}
            <button
              className="secondary"
              onClick={() =>
                void perform(async () => setFree(await call("disk_space")))
              }
            >
              Check free disk space
            </button>
            {free && (
              <p className="field-note">
                Downloads: {bytes(free.download)} · Temp: {bytes(free.temp)} ·
                ZIP: {bytes(free.zip)}
              </p>
            )}
          </section>
          <div className="settings-stack">
            <section className="card settings-card">
              <h2>Audio</h2>
              {select("audioQuality", "Default MP3 quality", [
                ["128", "128 kbps"],
                ["192", "192 kbps"],
                ["256", "256 kbps"],
                ["320", "320 kbps"],
                ["best", "Best available (VBR)"],
              ])}
              {toggle("embedThumbnail", "Embed thumbnail as cover art")}
              {toggle(
                "embedMetadata",
                "Embed title, artist, and available metadata",
              )}
              <p className="field-note">
                Encoding at 320 kbps does not improve the original source
                quality.
              </p>
            </section>
            <section className="card settings-card">
              <h2>Video</h2>
              {select("videoQuality", "Preferred resolution", [
                ["best", "Best available"],
                ["2160", "2160p / 4K"],
                ["1440", "1440p"],
                ["1080", "1080p"],
                ["720", "720p"],
                ["480", "480p"],
              ])}
              {select("container", "Container", [
                ["mp4", "MP4"],
                ["mkv", "MKV"],
              ])}
              {select("codec", "Format selection", [
                ["compatible", "Prefer H.264 + AAC compatibility"],
                ["quality", "Prefer highest source quality"],
              ])}
              <p className="field-note">
                Streams are copied and remuxed where supported. High quality
                codecs may require a compatible player.
              </p>
            </section>
            <section className="card settings-card">
              <h2>Playlists & archives</h2>
              {toggle("preserveOrder", "Preserve playlist order")}
              {toggle("autoZip", "Enable ZIP export by default")}
              {toggle("keepFolder", "Keep extracted folder after ZIP creation")}
              {toggle(
                "zipPartial",
                "Allow incomplete ZIPs",
                "Successful items only; the archive is labeled incomplete.",
              )}
              {text(
                "playlistTemplate",
                "Playlist naming format",
                "Use %(playlist_index)03d to number each item.",
              )}
            </section>
          </div>
          <section className="card settings-card">
            <h2>Media engine</h2>
            <p className="muted">
              Bundled standalone executables. No Python installation needed.
            </p>
            <EnginePanel
              versions={versions}
              busy={checking}
              refresh={refresh}
            />
            <div className="divider" />
            {text(
              "ytDlpPath",
              "Custom yt-dlp executable",
              "Leave empty to use the bundled or updated engine.",
              false,
            )}
            {text("ffmpegPath", "Custom FFmpeg executable", undefined, false)}
            {text("ffprobePath", "Custom FFprobe executable", undefined, false)}
            <p className="field-note">
              Save custom paths before checking versions. FFmpeg and FFprobe
              should be from the same distribution.
            </p>
            <button
              className="secondary"
              disabled={updating}
              onClick={() =>
                void perform(async () => {
                  setUpdating(true);
                  try {
                    const result = await call<string>("update_engine");
                    useApp
                      .getState()
                      .notify(result || "Engine update finished.");
                    refresh();
                  } finally {
                    setUpdating(false);
                  }
                })
              }
            >
              {updating ? (
                <LoaderCircle className="spin" size={15} />
              ) : (
                <RefreshCw size={15} />
              )}{" "}
              {updating ? "Updating yt-dlp…" : "Check & update yt-dlp"}
            </button>
          </section>
          <section className="card settings-card">
            <h2>Advanced</h2>
            {toggle(
              "advancedConcurrency",
              "Allow up to 12 download workers",
              "Higher concurrency can increase throttling. Default: 2.",
            )}
            {number("retries", "Network retries", 0, 20)}
            {number("retryBackoff", "Initial retry backoff (seconds)", 1, 60)}
            {number("fragments", "Concurrent fragments per download", 1, 8)}
            {number("fragmentBudget", "Total fragment budget", 12, 64)}
            {number("diskSafetyMb", "Free disk reserve (MiB)", 64, 4096)}
            {number("progressHz", "Progress refreshes per second", 1, 10)}
            {number("prefetch", "Playlist staging window", 8, 64)}
            {number("socketTimeout", "Network timeout (seconds)", 5, 300)}
            {text(
              "proxy",
              "Proxy URL",
              "Optional http(s) or SOCKS proxy. Credentials are kept locally.",
            )}
            {text(
              "cookiesFile",
              "Cookies file",
              "Optional Netscape-format cookies file supplied by you.",
              false,
            )}
            {select("logging", "Logging level", [
              ["normal", "Normal"],
              ["verbose", "Verbose"],
            ])}
            <div className="button-row">
              <button
                className="secondary"
                onClick={() =>
                  void perform(() => call("open_output", { kind: "logs" }))
                }
              >
                <FolderOpen size={15} />
                Open logs
              </button>
              <button
                className="secondary"
                onClick={() =>
                  void perform(async () => {
                    if (
                      await confirmAction(
                        "Remove temporary data for finished downloads? Failed downloads may need to start over.",
                      )
                    ) {
                      await call("clean_temporary");
                      useApp
                        .getState()
                        .notify("Finished-task temporary data removed.");
                    }
                  })
                }
              >
                Clean temporary data
              </button>
            </div>
          </section>
        </div>
        <div className="settings-footer">
          <button
            className="secondary"
            onClick={() =>
              void perform(async () => {
                if (
                  await confirmAction(
                    "Reset preferences to defaults? Save changes to apply the reset.",
                  )
                )
                  setS(await call<SettingsType>("default_settings"));
              })
            }
          >
            <RotateCcw size={15} />
            Reset to defaults
          </button>
          <button
            className="primary"
            disabled={saving}
            onClick={() => void save()}
          >
            <Save size={16} />
            Save changes
          </button>
        </div>
      </div>
    </>
  );
}
