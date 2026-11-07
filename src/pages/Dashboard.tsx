import { useState } from "react";
import { LibraryShortcuts } from "./Library";
import {
  ArrowRight,
  Link,
  LoaderCircle,
  Music2,
  Film,
  FolderOpen,
  ListVideo,
  Download,
  ArrowDownToLine,
} from "lucide-react";
import { call } from "../api";
import { useApp, perform } from "../stores/app";
import type { Media } from "../types";
import { validateUrl, time } from "../utils/media";
import { JobList } from "../components/Common";
export default function Dashboard({
  onSelect,
}: {
  onSelect: (id: string) => void;
}) {
  const { db, navigate } = useApp();
  const settings = db?.settings;
  const [url, setUrl] = useState("");
  const [media, setMedia] = useState<Media | null>(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [starting, setStarting] = useState(false);
  const [kind, setKind] = useState<"audio" | "video">("audio");
  const [audio, setAudio] = useState(settings?.audioQuality || "320");
  const [video, setVideo] = useState(settings?.videoQuality || "best");
  const [playlist, setPlaylist] = useState(true);
  const [zip, setZip] = useState(settings?.autoZip || false);
  const [order, setOrder] = useState(settings?.preserveOrder ?? true);
  const jobs = db?.jobs || [];
  const analyze = async () => {
    const error = validateUrl(url);
    if (error) {
      useApp.getState().fail(error);
      return;
    }
    setAnalyzing(true);
    setMedia(null);
    await perform(async () => {
      const m = await call<Media>("analyze_url", { url: url.trim(), playlist });
      setMedia(m);
    });
    setAnalyzing(false);
  };
  const start = async () => {
    const error = validateUrl(url);
    if (error) {
      useApp.getState().fail(error);
      return;
    }
    setStarting(true);
    await perform(async () => {
      await call("enqueue", {
        request: {
          url: url.trim(),
          mediaType: kind,
          quality: kind === "audio" ? audio : video,
          playlist,
          createZip: playlist && zip,
          preserveOrder: order,
        },
      });
      useApp.getState().notify("Added to your local download queue.");
      navigate("downloads");
    });
    setStarting(false);
  };
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">YOUR LIBRARY STARTS HERE</div>
          <h1>Download YouTube content</h1>
          <p>Videos, playlists, or just audio. High quality. Fast. Simple.</p>
        </div>
        <span className="local-pill">
          <span />
          Runs on your device
        </span>
      </div>
      <div className="dashboard-grid">
        <section className="card download-card">
          <div className="section-head">
            <h2>
              <ArrowDownToLine size={19} /> Download media
            </h2>
            <span className="subtle-tag">YOUTUBE</span>
          </div>
          <label className="field-label" htmlFor="youtube-url">
            Video or playlist URL
          </label>
          <div className="url-input">
            <Link size={19} />
            <input
              id="youtube-url"
              value={url}
              placeholder="Paste a YouTube link here…"
              onChange={(e) => {
                setUrl(e.target.value);
                setMedia(null);
              }}
              onKeyDown={(e) => {
                if (e.key === "Enter") void analyze();
              }}
            />
            <button
              className="secondary"
              disabled={analyzing || starting || !url.trim()}
              onClick={() => void analyze()}
            >
              {analyzing ? <LoaderCircle size={16} className="spin" /> : null}
              {analyzing ? "Analyzing…" : "Analyze"}
              {!analyzing && <ArrowRight size={15} />}
            </button>
            <button
              className="icon-button"
              aria-label="Paste URL"
              title="Paste URL"
              onClick={() =>
                void perform(async () =>
                  setUrl(await navigator.clipboard.readText()),
                )
              }
            >
              Paste
            </button>
            <button
              className="icon-button"
              aria-label="Clear URL"
              title="Clear URL"
              onClick={() => {
                setUrl("");
                setMedia(null);
              }}
            >
              X
            </button>
          </div>
          {media && (
            <div className="analysis-result">
              {media.thumbnail && (
                <img
                  src={media.thumbnail}
                  alt="Media thumbnail"
                  referrerPolicy="no-referrer"
                  onError={(e) => {
                    e.currentTarget.style.display = "none";
                  }}
                />
              )}
              <div>
                <strong>{media.title}</strong>
                <p>
                  {media.isPlaylist
                    ? `${media.entries.length} preview items · Playlist`
                    : `${media.uploader} · ${time(media.duration)}`}
                </p>
                {media.qualities.length > 0 && (
                  <small>
                    Available: {media.qualities.map((q) => `${q}p`).join(" · ")}
                  </small>
                )}
                {media.isPlaylist && (
                  <ol>
                    {media.entries.slice(0, 5).map((item, i) => (
                      <li key={`${item.id}-${i}`}>{item.title}</li>
                    ))}
                    {media.entries.length > 5 && (
                      <li className="muted">
                        + {media.entries.length - 5} more items
                      </li>
                    )}
                  </ol>
                )}
                {media.isPlaylist && (
                  <small className="field-note">
                    Preview is limited to 100 items. Adding the playlist
                    discovers and queues its full contents, up to 10,000 items.
                  </small>
                )}
              </div>
            </div>
          )}
          <div className="field-label spaced">Choose your format</div>
          <div className="format-grid">
            <button
              className={`format-option ${kind === "audio" ? "selected" : ""}`}
              aria-pressed={kind === "audio"}
              onClick={() => setKind("audio")}
            >
              <span className="format-symbol">
                <Music2 size={22} />
              </span>
              <span>
                <strong>Audio</strong>
                <small>MP3 · Just the sound</small>
              </span>
              <span className="radio-dot" />
            </button>
            <button
              className={`format-option ${kind === "video" ? "selected" : ""}`}
              aria-pressed={kind === "video"}
              onClick={() => setKind("video")}
            >
              <span className="format-symbol">
                <Film size={22} />
              </span>
              <span>
                <strong>Video</strong>
                <small>
                  {settings?.container.toUpperCase() || "MP4"} · The full
                  picture
                </small>
              </span>
              <span className="radio-dot" />
            </button>
          </div>
          <div className="download-configuration">
            <div className="download-quality">
          <label className="field-label spaced" htmlFor="quality">
            Quality
          </label>
          <select
            id="quality"
            value={kind === "audio" ? audio : video}
            onChange={(e) =>
              kind === "audio"
                ? setAudio(e.target.value)
                : setVideo(e.target.value)
            }
          >
            {(kind === "audio"
              ? [
                  ["320", "320 kbps — high quality"],
                  ["256", "256 kbps"],
                  ["192", "192 kbps"],
                  ["128", "128 kbps"],
                  ["best", "Best available (VBR)"],
                ]
              : [
                  ["best", "Best available"],
                  ["2160", "2160p / 4K"],
                  ["1440", "1440p"],
                  ["1080", "1080p"],
                  ["720", "720p"],
                  ["480", "480p"],
                ]
            ).map(([v, l]) => (
              <option key={v} value={v}>
                {l}
              </option>
            ))}
          </select>
          <p className="field-note">
            {kind === "audio"
              ? "MP3 conversion preserves the source’s limits. A higher bitrate cannot add missing detail."
              : "Resolution is capped at your choice. Separate audio and video streams are merged locally."}
          </p>
            </div>
          <div className="playlist-options">
            <label className="check-line">
              <input
                type="checkbox"
                checked={playlist}
                onChange={(e) => {
                  setPlaylist(e.target.checked);
                  setMedia(null);
                }}
              />
              <span>
                <ListVideo size={17} />
                Include playlist when available
              </span>
            </label>
            <div className="indented-options">
              <label className="check-line">
                <input
                  type="checkbox"
                  disabled={!playlist}
                  checked={zip}
                  onChange={(e) => setZip(e.target.checked)}
                />
                <span>Create a ZIP archive</span>
              </label>
              <label className="check-line">
                <input
                  type="checkbox"
                  disabled={!playlist}
                  checked={order}
                  onChange={(e) => setOrder(e.target.checked)}
                />
                <span>Preserve playlist order</span>
              </label>
            </div>
          </div>
          <div className="destination">
            <FolderOpen size={19} />
            <div>
              <small>DOWNLOAD LOCATION</small>
              <span title={settings?.downloadDir}>
                {settings?.downloadDir || "Your Downloads folder / YTHub"}
              </span>
            </div>
            <button
              className="text-button"
              onClick={() => navigate("settings")}
            >
              Change
            </button>
          </div>
          <button
            className="primary start-button"
            disabled={!url.trim() || analyzing || starting}
            onClick={() => void start()}
          >
            {starting ? (
              <LoaderCircle size={18} className="spin" />
            ) : (
              <Download size={18} />
            )}{" "}
            {starting ? "Preparing your download…" : "Start download"}
            <ArrowRight size={17} />
          </button>
          </div>
        </section>
      </div>
      <LibraryShortcuts />
      <section className="card recent">
        <div className="section-head">
          <h2>Recently downloaded</h2>
          <button className="text-button" onClick={() => navigate("downloads")}>
            View all <ArrowRight size={14} />
          </button>
        </div>
        {jobs.length ? (
          <JobList jobs={[...jobs].reverse().slice(0, 3)} onSelect={onSelect} />
        ) : (
          <div className="recent-empty">
            <div className="media-icon">
              <Download size={19} />
            </div>
            <div>
              <strong>A fresh start for your library</strong>
              <p>
                Your downloads will appear here. Add a link above to get
                started.
              </p>
            </div>
          </div>
        )}
      </section>
    </>
  );
}
