import { convertFileSrc } from "@tauri-apps/api/core";
import { createPortal } from "react-dom";
import { useEffect, useRef, useState } from "react";
import {
  Play,
  Pause,
  SkipBack,
  SkipForward,
  Shuffle,
  Repeat,
  Heart,
  Volume2,
  X,
  Timer,
  AlignLeft,
  ChevronUp,
  ChevronDown,
  LoaderCircle,
} from "lucide-react";
import {
  useMusic,
  seek,
  startAudioService,
  importTrack,
  usePlaybackTime,
  togglePlayerPanel,
  toggleShuffle,
} from "../stores/music";
import type { Track } from "../stores/music";
import { useApp } from "../stores/app";
import { call, desktop } from "../api";
export const clock = (n: number) =>
  `${Math.floor((n || 0) / 60)}:${String(Math.floor((n || 0) % 60)).padStart(2, "0")}`;
export function Artwork({
  track,
  small = false,
}: {
  track?: Track;
  small?: boolean;
}) {
  return (
    <div
      className={`artwork ${small ? "small-art" : ""}`}
      data-color={(track?.title.charCodeAt(0) || 0) % 4}
    >
      {track?.artwork ? (
        <img
          src={convertFileSrc(track.artwork)}
          alt="Album artwork"
          onError={(e) => {
            if (e.currentTarget.dataset.fallback) e.currentTarget.style.display = "none";
            else {
              e.currentTarget.dataset.fallback = "true";
              e.currentTarget.src = "/hertix-symbol.png";
              e.currentTarget.classList.add("artwork-fallback");
            }
          }}
        />
      ) : (
        <img className="artwork-fallback" src="/hertix-symbol.png" alt="Hertix" />
      )}
      <span>{!small && (track?.album || "YOUR OFFLINE SOUNDTRACK")}</span>
    </div>
  );
}
export function MusicService() {
  const jobs = useApp((s) => s.db?.jobs);
  const [hydrated, setHydrated] = useState(useMusic.persist.hasHydrated());
  useEffect(
    () => useMusic.persist.onFinishHydration(() => setHydrated(true)),
    [],
  );
  const importing = useRef(new Set<string>());
  useEffect(() => {
    if (hydrated) {
      if (!useMusic.getState().settings.resume)
        useMusic.setState({ queue: [], index: 0 });
      return startAudioService();
    }
  }, [hydrated]);
  useEffect(() => {
    if (!desktop || !jobs || !hydrated) return;
    let disposed = false;
    const run = async () => {
      for (const job of jobs) {
        const s = useMusic.getState();
        if (disposed || !s.settings.autoAdd) return;
        if (
          s.excludedPaths.includes(job.outputPath?.toLowerCase() || "") ||
          job.status !== "completed" ||
          job.mediaType !== "audio" ||
          !job.outputPath ||
          importing.current.has(job.id) ||
          s.tracks.some((t) => t.jobId === job.id)
        )
          continue;
        importing.current.add(job.id);
        try {
          const track = await importTrack(job.outputPath, job);
          if (job.playlistId && s.settings.autoPlaylist) {
            const id = `download-${job.playlistId}`;
            const music = useMusic.getState();
            const p = music.playlists.find((p) => p.id === id);
            const ids = Array.from(new Set([...(p?.tracks || []), track.id]));
            ids.sort((a, b) => {
              const tracks = useMusic.getState().tracks;
              const index = (id: string) =>
                jobs.find(
                  (j) => j.id === tracks.find((t) => t.id === id)?.jobId,
                )?.playlistIndex ?? 0;
              return index(a) - index(b);
            });
            useMusic.setState({
              playlists: [
                ...music.playlists.filter((p) => p.id !== id),
                {
                  id,
                  title: job.playlistTitle || "Downloaded playlist",
                  tracks: ids,
                },
              ],
            });
          }
          if (s.settings.autoPlay) useMusic.getState().play(track.id);
          if (s.settings.notify)
            useApp.getState().notify(`Added to Music Library: ${track.title}`);
        } catch {
          /* ZIP cleanup may have removed the original. Never register a missing file. */
        }
      }
    };
    void run();
    return () => {
      disposed = true;
    };
  }, [jobs, hydrated]);
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (
        (e.target as HTMLElement)?.closest(
          "input,textarea,select,[contenteditable=true]",
        )
      )
        return;
      if (e.code === "Space") {
        e.preventDefault();
        useMusic.getState().toggle();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, []);
  return null;
}
function Controls() {
  const s = useMusic();
  return (
    <div className="transport">
      <button
        className={`icon-button ${s.shuffle ? "accent" : ""}`}
        title="Shuffle"
        aria-label="Shuffle"
        aria-pressed={s.shuffle}
        onClick={toggleShuffle}
      >
        <Shuffle size={17} />
      </button>
      <button
        className="icon-button"
        aria-label="Previous track"
        onClick={() => s.next(-1)}
      >
        <SkipBack size={20} />
      </button>
      <button
        className="play-button"
        aria-label={s.playing ? "Pause" : "Play"}
        disabled={!s.queue.length}
        onClick={s.toggle}
      >
        {s.playing ? <Pause size={23} /> : <Play size={23} />}
      </button>
      <button
        className="icon-button"
        aria-label="Next track"
        onClick={() => s.next()}
      >
        <SkipForward size={20} />
      </button>
      <button
        className={`icon-button ${s.repeat !== "off" ? "accent" : ""}`}
        aria-label={`Repeat: ${s.repeat}`}
        title={`Repeat: ${s.repeat}`}
        onClick={() =>
          useMusic.setState({
            repeat:
              s.repeat === "off" ? "all" : s.repeat === "all" ? "one" : "off",
          })
        }
      >
        <Repeat size={17} />
        {s.repeat === "one" && <small>1</small>}
      </button>
    </div>
  );
}
function Progress() {
  const s = usePlaybackTime();
  return (
    <div className="play-progress">
      <input
        aria-label="Playback position"
        type="range"
        min="0"
        max={s.duration || 1}
        step=".1"
        value={Math.min(s.position, s.duration || 1)}
        onChange={(e) => seek(+e.target.value)}
      />
      <div>
        <span>{clock(s.position)}</span>
        <span>{clock(s.duration)}</span>
      </div>
    </div>
  );
}
export function Player({ mini = false }: { mini?: boolean }) {
  const s = useMusic();
  const track = s.tracks.find((t) => t.id === s.queue[s.index]);
  const [lyrics, setLyrics] = useState(false);
  const [queueScroll, setQueueScroll] = useState(0);
  const [sleep, setSleep] = useState(0);
  const queueRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    setQueueScroll(0);
    if (queueRef.current) queueRef.current.scrollTop = 0;
  }, [s.index]);
  const queueStart = Math.max(0, Math.floor(queueScroll / 60) - 3);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  useEffect(() => {
    if (!s.overlay) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") useMusic.setState({ overlay: false });
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [s.overlay]);
  if (mini && !track) return null;
  if (!mini && !s.overlay) return null;
  const content = (
    <aside
      className={
        mini
          ? "floating-player"
          : `now-playing ${s.overlay ? "expanded-player" : ""}`
      }
      aria-label={mini ? "Mini player" : "Now Playing"}
    >
      {!mini && (
        <div className="section-head">
          <span className="eyebrow">NOW PLAYING</span>
          <button
            className="icon-button player-collapse"
            aria-label="Minimize player"
            onClick={() => useMusic.setState({ panel: true, overlay: false })}
          >
            <ChevronDown size={19} />
          </button>
        </div>
      )}
      {mini ? <button className="mini-track-hit" onClick={togglePlayerPanel} aria-label="Open Now Playing"><Artwork track={track} small /><span className="playing-meta"><strong>{track?.title || "A little quiet, for now."}</strong><small>{track?.artist || "Play a track from your library"}</small></span></button> : <><Artwork track={track} /><div className="playing-meta"><strong>{track?.title || "A little quiet, for now."}</strong><p>{track?.artist || "Play a track from your library"}</p></div></>}
      {track && !mini && (
        <button
          className={`icon-button favorite-playing ${track.favorite ? "accent" : ""}`}
          aria-label="Favorite current track"
          aria-pressed={track.favorite}
          onClick={() => s.favorite(track.id)}
        >
          <Heart size={21} fill={track.favorite ? "currentColor" : "none"} />
        </button>
      )}
      {mini ? <div className="mini-player-controls">
        <button className="icon-button" aria-label={s.playing ? "Pause" : "Play"} title={s.playing ? "Pause" : "Play"} onClick={s.toggle}>{s.playing ? <Pause size={18} /> : <Play size={18} />}</button>
        <button className="icon-button" aria-label="Next track" title="Next track" onClick={() => s.next()}><SkipForward size={19} /></button>
      </div> : <div className="player-center"><Progress /><Controls /></div>}
      {!mini && <label className="volume">
        <Volume2 size={17} />
        <input
          aria-label={mini ? "Mini player volume" : "Volume"}
          type="range"
          min="0"
          max="1"
          step=".01"
          value={s.volume}
          onChange={(e) => useMusic.setState({ volume: +e.target.value })}
        />
      </label>}
      {mini ? (
        null
      ) : (
        <>
          <div className="skip-controls">
            <button
              className="text-button"
              aria-label="Skip backward"
              onClick={() =>
                seek(usePlaybackTime.getState().position - s.settings.skip)
              }
            >
              −{s.settings.skip}s
            </button>
            <PlaybackStatus />
            <button
              className="text-button"
              aria-label="Skip forward"
              onClick={() =>
                seek(usePlaybackTime.getState().position + s.settings.skip)
              }
            >
              +{s.settings.skip}s
            </button>
          </div>
          <div className="player-extras">
            <button className="secondary" onClick={() => setLyrics(!lyrics)}>
              <AlignLeft size={15} />
              Lyrics
            </button>
            <label className="sleep-timer">
              <Timer size={16} />
              <select
                aria-label="Sleep timer"
                value={sleep}
                onChange={(e) => {
                  const minutes = +e.target.value;
                  setSleep(minutes);
                  clearTimeout(timer.current);
                  if (minutes)
                    timer.current = setTimeout(() => {
                      useMusic.setState({ playing: false });
                      setSleep(0);
                    }, minutes * 60000);
                }}
              >
                <option value="0">Sleep timer</option>
                {[15, 30, 60, 90].map((n) => (
                  <option key={n} value={n}>
                    {n} minutes
                  </option>
                ))}
              </select>
            </label>
          </div>
          {lyrics ? (
            <Lyrics track={track} />
          ) : (
            <>
              <div className="section-head queue-heading">
                <h3>
                  Up next{" "}
                  <small>({Math.max(0, s.queue.length - s.index - 1)})</small>
                </h3>
                <button
                  className="text-button"
                  onClick={() =>
                    useMusic.setState({ queue: s.queue.slice(0, s.index + 1) })
                  }
                >
                  Clear
                </button>
              </div>
              <div
                className="play-queue"
                ref={queueRef}
                onScroll={(e) => setQueueScroll(e.currentTarget.scrollTop)}
                style={{ maxHeight: 240, overflow: "auto" }}
              >
                <div
                  style={{
                    height: Math.max(0, s.queue.length - s.index - 1) * 60,
                    position: "relative",
                  }}
                >
                  {s.queue
                    .slice(
                      s.index + 1 + queueStart,
                      s.index + 1 + queueStart + 12,
                    )
                    .map((id, i) => {
                      const t = s.tracks.find((t) => t.id === id);
                      const index = s.index + 1 + queueStart + i;
                      return (
                        t && (
                          <div
                            className="queue-row"
                            style={{
                              position: "absolute",
                              top: (queueStart + i) * 60,
                              left: 0,
                              right: 0,
                              height: 60,
                            }}
                            key={`${id}-${index}`}
                            draggable
                            onDragStart={(e) =>
                              e.dataTransfer.setData(
                                "text/plain",
                                String(index),
                              )
                            }
                            onDragOver={(e) => e.preventDefault()}
                            onDrop={(e) => {
                              e.preventDefault();
                              s.move(
                                +e.dataTransfer.getData("text/plain"),
                                index,
                              );
                            }}
                          >
                            <Artwork track={t} small />
                            <button
                              className="queue-title"
                              onClick={() => {
                                useMusic.setState({
                                  index,
                                  request: s.request + 1,
                                  playing: true,
                                });
                              }}
                            >
                              <strong>{t.title}</strong>
                              <small>
                                {t.artist} · {clock(t.duration || 0)}
                              </small>
                            </button>
                            <button
                              className="icon-button"
                              aria-label={`Move ${t.title} up`}
                              disabled={index === s.index + 1}
                              onClick={() => s.move(index, index - 1)}
                            >
                              <ChevronUp size={13} />
                            </button>
                            <button
                              className="icon-button"
                              aria-label={`Move ${t.title} down`}
                              disabled={index === s.queue.length - 1}
                              onClick={() => s.move(index, index + 1)}
                            >
                              <ChevronDown size={13} />
                            </button>
                            <button
                              className="icon-button"
                              aria-label={`Remove ${t.title} from queue`}
                              onClick={() =>
                                useMusic.setState({
                                  queue: s.queue.filter((_, n) => n !== index),
                                })
                              }
                            >
                              <X size={13} />
                            </button>
                          </div>
                        )
                      );
                    })}
                </div>
                {s.queue.length <= s.index + 1 && (
                  <p className="queue-empty">
                    Your queue is clear. Add music from your library.
                  </p>
                )}
              </div>
              <button
                className="text-button"
                disabled={!s.queue.length}
                onClick={() => useApp.getState().navigate("playlists")}
              >
                Save queue in Playlists →
              </button>
            </>
          )}
        </>
      )}
    </aside>
  );
  if (mini) return createPortal(content, document.body);
  return createPortal(<div className="player-overlay"><button className="player-overlay-scrim" aria-label="Close player" onClick={() => useMusic.setState({ overlay: false })} />{content}</div>, document.body);
}

function Lyrics({ track }: { track?: Track }) {
  const position = usePlaybackTime((s) => s.position);
  const [follow, setFollow] = useState(true);
  const [language, setLanguage] = useState("auto");
  const [transcribing, setTranscribing] = useState(false);
  const [error, setError] = useState("");
  const ref = useRef<HTMLDivElement>(null);
  const transcribe = async () => {
    if (!track?.path || transcribing) return;
    setTranscribing(true);
    setError("");
    try {
      const lyrics = await call<string>("transcribe_lyrics", { path: track.path, language });
      useMusic.setState((s) => ({ tracks: s.tracks.map((t) => t.id === track.id ? { ...t, lyrics } : t) }));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setTranscribing(false);
    }
  };
  const lines = (track?.lyrics || "").split("\n").map((line) => {
    const match = line.match(/^\[(\d+):(\d+(?:\.\d+)?)\](.*)/);
    return {
      time: match ? +match[1] * 60 + +match[2] : null,
      text: match ? match[3] : line,
    };
  });
  const current = lines.reduce(
    (index, line, i) =>
      line.time !== null && line.time <= position ? i : index,
    -1,
  );
  useEffect(() => {
    if (follow)
      ref.current?.querySelector(".current-lyric")?.scrollIntoView({
        block: "nearest",
        behavior: window.matchMedia("(prefers-reduced-motion:reduce)").matches
          ? "instant"
          : "smooth",
      });
  }, [current, follow]);
  return (
    <div className="lyrics" ref={ref} onWheel={() => setFollow(false)}>
      <h3>Lyrics</h3>
      <div className="lyrics-tools">
        <label htmlFor="lyrics-language">Language</label>
        <select id="lyrics-language" value={language} onChange={(e) => setLanguage(e.target.value)} disabled={transcribing}>
          <option value="auto">Auto detect</option>
          <option value="en">English</option>
          <option value="ta">Tamil</option>
        </select>
        <button className="text-button" onClick={() => void transcribe()} disabled={!track?.path || transcribing}>
          {transcribing ? <><LoaderCircle size={15} className="spin" /> Transcribing…</> : "Transcribe on this device"}
        </button>
      </div>
      <small>First use downloads the speech model (~190 MB). Audio stays on this device.</small>
      {error && <p role="alert">{error}</p>}
      {track?.lyrics ? (
        <>
          <button className="text-button" onClick={() => setFollow(true)}>
            Return to current line
          </button>
          {lines.map((line, i) => (
            <p key={i} className={i === current ? "current-lyric" : ""}>
              {line.text}
            </p>
          ))}
        </>
      ) : (
        <p>
          No local lyrics found. Place an .lrc file beside the audio file with
          the same filename, then play the track again.
        </p>
      )}
    </div>
  );
}

function PlaybackStatus() {
  const loading = usePlaybackTime((s) => s.loading);
  const fading = usePlaybackTime((s) => s.transitioning);
  return (
    <small role="status">
      {loading ? "Preparing audio…" : fading ? "Crossfading" : ""}
    </small>
  );
}
