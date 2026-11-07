import { EQ_FREQUENCIES, EQ_PRESETS } from "../audio/effects";
import { usePlaybackTime } from "../stores/music";
import { useDeferredValue, useMemo, useState, useRef, useEffect } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import {
  Play,
  Heart,
  Plus,
  Search,
  Music2,
  FolderOpen,
  ListMusic,
  Shuffle,
} from "lucide-react";
import { useMusic, importTrack } from "../stores/music";
import type { Track } from "../stores/music";
import { perform, useApp } from "../stores/app";
import { call, confirmAction, desktop } from "../api";
import { Artwork, clock } from "../components/Player";
import { Modal } from "../components/Common";
export async function importAudio() {
  if (!desktop) {
    useApp.getState().fail("Open the desktop app to import local audio.");
    return;
  }
  await perform(async () => {
    const paths = await open({
      multiple: true,
      filters: [
        {
          name: "Audio files",
          extensions: ["mp3", "m4a", "aac", "wav", "flac", "ogg", "opus"],
        },
      ],
    });
    if (!paths) return;
    let count = 0;
    for (const path of Array.isArray(paths) ? paths : [paths]) {
      await importTrack(path);
      count++;
    }
    useApp
      .getState()
      .notify(`Imported ${count} audio file${count === 1 ? "" : "s"}.`);
  });
}
async function importZip() {
  await perform(async () => {
    const path = await open({
      multiple: false,
      filters: [{ name: "Playlist ZIP", extensions: ["zip"] }],
    });
    if (typeof path !== "string") return;
    const paths = await call<string[]>("extract_audio_zip", { path });
    const ids: string[] = [];
    for (const file of paths) {
      try {
        ids.push((await importTrack(file)).id);
      } catch (e) {
        useApp.getState().fail(e);
      }
    }
    if (ids.length)
      useMusic.getState().savePlaylist(
        path
          .split(/[\\/]/)
          .pop()
          ?.replace(/\.zip$/i, "") || "Imported playlist",
        ids,
      );
  });
}
export function LibraryShortcuts() {
  const tracks = useMusic((s) => s.tracks);
  const playlists = useMusic((s) => s.playlists);
  const navigate = useApp((s) => s.navigate);
  return (
    <section className="library-shortcuts">
      <div className="section-head">
        <h2>Your library</h2>
        <button className="text-button" onClick={() => navigate("library")}>
          See all →
        </button>
      </div>
      <div className="shortcut-grid">
        {[
          {
            title: "Liked songs",
            sub: `${tracks.filter((t) => t.favorite).length} favorites`,
            icon: Heart,
            filter: "Favorites",
          },
          {
            title: "Recently added",
            sub: `${tracks.length} songs`,
            icon: Plus,
            filter: "Recently Added",
          },
          {
            title: "Recently played",
            sub: `${tracks.filter((t) => t.played).length} songs`,
            icon: Music2,
            filter: "Recently Played",
          },
          {
            title: "Your playlists",
            sub: `${playlists.length} collections`,
            icon: ListMusic,
            filter: "Playlists",
          },
        ].map(({ title, sub, icon: Icon, filter }) => (
          <button
            className="shortcut-card"
            key={title}
            onClick={() => {
              sessionStorage.setItem("ythub-library-filter", filter);
              navigate(filter === "Playlists" ? "playlists" : "library");
            }}
          >
            <div className="shortcut-art">
              <Icon size={28} />
            </div>
            <strong>{title}</strong>
            <small>{sub}</small>
          </button>
        ))}
      </div>
    </section>
  );
}
export default function Library({
  playlistPage = false,
}: {
  playlistPage?: boolean;
}) {
  const s = useMusic();
  const [filter, setFilter] = useState(
    sessionStorage.getItem("ythub-library-filter") || "Songs",
  );
  const [query, setQuery] = useState("");
  const search = useDeferredValue(query.toLowerCase());
  const [sort, setSort] = useState("added");
  const selected = s.selectedPlaylist;
  const setSelected = (id: string | null) =>
    useMusic.setState({ selectedPlaylist: id });
  const [track, setTrack] = useState<Track | null>(null);
  const [create, setCreate] = useState(false);
  const [name, setName] = useState("");
  const [saveQueue, setSaveQueue] = useState(false);
  const [scroll, setScroll] = useState(0);
  const [groupLimit, setGroupLimit] = useState(200);
  const listRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (listRef.current) listRef.current.scrollTop = 0;
  }, [query, filter, sort, selected]);
  const playlist = s.playlists.find((p) => p.id === selected);
  const rows = useMemo(
    () =>
      s.tracks
        .filter(
          (t) =>
            (!playlist || playlist.tracks.includes(t.id)) &&
            (playlistPage || filter !== "Favorites" || t.favorite) &&
            (playlistPage || filter !== "Recently Played" || t.played) &&
            `${t.title} ${t.artist} ${t.album} ${t.path}`
              .toLowerCase()
              .includes(search),
        )
        .sort((a, b) =>
          playlist
            ? playlist.tracks.indexOf(a.id) - playlist.tracks.indexOf(b.id)
            : sort === "title"
              ? a.title.localeCompare(b.title)
              : filter === "Recently Played"
                ? (b.played || 0) - (a.played || 0)
                : b.added - a.added,
        ),
    [s.tracks, playlist, filter, search, sort, playlistPage],
  );
  const grouped =
    filter === "Albums" || filter === "Artists" || filter === "Folders";
  const groups = useMemo(() => {
    const result = new Map<string, Track[]>();
    for (const t of rows) {
      const key =
        filter === "Artists"
          ? t.artist
          : filter === "Folders"
            ? t.path.replace(/[\\/][^\\/]+$/, "")
            : t.album || "Unknown album";
      result.set(key, [...(result.get(key) || []), t]);
    }
    return [...result.entries()];
  }, [rows, filter]);
  const start = Math.max(0, Math.floor(scroll / 68) - 4);
  const visible = rows.slice(start, start + 22);
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">MADE FOR YOUR OFFLINE MOMENTS</div>
          <h1>
            {playlistPage
              ? playlist?.title || "Your playlists"
              : "Music Library"}
          </h1>
          <p>
            {playlistPage
              ? "Collections with a soundtrack of their own."
              : `${s.tracks.length} songs · Always within reach.`}
          </p>
        </div>
        <button className="primary" onClick={() => void importAudio()}>
          <Plus size={16} />
          Import music
        </button>
      </div>
      {playlistPage ? (
        <>
          <div className="button-row">
            <button className="secondary" onClick={() => void importZip()}>
              Extract & import ZIP
            </button>
            <button
              className="secondary"
              onClick={() => {
                setSaveQueue(false);
                setName("");
                setCreate(true);
              }}
            >
              <Plus size={15} />
              New playlist
            </button>
            <button
              className="secondary"
              disabled={!s.queue.length}
              onClick={() => {
                setSaveQueue(true);
                setName("");
                setCreate(true);
              }}
            >
              Save queue as playlist
            </button>
            {selected && (
              <button className="text-button" onClick={() => setSelected(null)}>
                All playlists
              </button>
            )}
          </div>
          {!selected && (
            <div className="playlist-grid">
              {s.playlists.map((p) => (
                <button
                  className="playlist-card"
                  key={p.id}
                  onClick={() => {
                    setSelected(p.id);
                    setScroll(0);
                  }}
                >
                  <Artwork track={s.tracks.find((t) => t.id === p.tracks[0])} />
                  <strong>{p.title}</strong>
                  <small>
                    {p.tracks.length} songs ·{" "}
                    {clock(
                      p.tracks.reduce(
                        (n, id) =>
                          n +
                          (s.tracks.find((t) => t.id === id)?.duration || 0),
                        0,
                      ),
                    )}
                  </small>
                </button>
              ))}
              {!s.playlists.length && (
                <div className="card empty">
                  <ListMusic size={36} />
                  <h2>A place for every mood</h2>
                  <p>Create a playlist, then add music from your library.</p>
                </div>
              )}
            </div>
          )}
          {playlist && (
            <div className="button-row">
              <button
                className="primary"
                disabled={!rows.length}
                onClick={() =>
                  s.play(
                    rows[0].id,
                    rows.map((t) => t.id),
                  )
                }
              >
                <Play size={15} />
                Play all
              </button>
              <button
                className="secondary"
                disabled={!rows.length}
                onClick={() => {
                  const ids = rows
                    .map((t) => t.id)
                    .sort(() => Math.random() - 0.5);
                  s.play(ids[0], ids);
                }}
              >
                <Shuffle size={15} />
                Shuffle
              </button>
              <button
                className="secondary"
                onClick={() => rows.forEach((t) => s.enqueue(t.id))}
              >
                Add all to queue
              </button>
              <button
                className="text-button"
                onClick={() => {
                  setName(playlist.title);
                  setCreate(true);
                }}
              >
                Rename
              </button>
              <button
                className="text-button danger"
                onClick={() =>
                  void perform(async () => {
                    if (
                      await confirmAction(
                        `Delete playlist “${playlist.title}”? Your files will be kept.`,
                      )
                    ) {
                      useMusic.setState({
                        playlists: s.playlists.filter(
                          (p) => p.id !== playlist.id,
                        ),
                      });
                      setSelected(null);
                    }
                  })
                }
              >
                Delete playlist
              </button>
            </div>
          )}
        </>
      ) : (
        <div
          className="library-tabs"
          role="tablist"
          aria-label="Library category"
        >
          {[
            "Songs",
            "Albums",
            "Artists",
            "Favorites",
            "Recently Added",
            "Recently Played",
            "Folders",
          ].map((tab) => (
            <button
              role="tab"
              aria-selected={filter === tab}
              className={filter === tab ? "selected" : ""}
              key={tab}
              onClick={() => {
                setFilter(tab);
                setScroll(0);
                sessionStorage.setItem("ythub-library-filter", tab);
              }}
            >
              {tab}
            </button>
          ))}
        </div>
      )}
      {(!playlistPage || selected) && (
        <>
          <div className="library-toolbar">
            <label className="search-field">
              <Search size={16} />
              <input
                id="library-search"
                aria-label="Search music"
                placeholder="Search songs, albums, artists…"
                value={query}
                onChange={(e) => {
                  setQuery(e.target.value);
                  setScroll(0);
                }}
              />
            </label>
            <select
              aria-label="Sort music"
              value={sort}
              onChange={(e) => setSort(e.target.value)}
            >
              <option value="added">Recently added</option>
              <option value="title">Title A–Z</option>
            </select>
          </div>
          {grouped && !playlistPage ? (
            <div className="playlist-grid">
              {groups.slice(0, groupLimit).map(([name, tracks]) => (
                <button
                  className="playlist-card"
                  key={name}
                  onClick={() =>
                    s.play(
                      tracks[0].id,
                      tracks.map((t) => t.id),
                    )
                  }
                >
                  <Artwork track={tracks[0]} />
                  <strong>{name}</strong>
                  <small>{tracks.length} songs · Play collection</small>
                </button>
              ))}
              {groups.length > groupLimit && (
                <button
                  className="secondary"
                  onClick={() => setGroupLimit((n) => n + 200)}
                >
                  Show more collections ({groups.length - groupLimit} remaining)
                </button>
              )}
            </div>
          ) : (
            <div
              className="track-table"
              ref={listRef}
              onScroll={(e) => setScroll(e.currentTarget.scrollTop)}
            >
              <div style={{ height: rows.length * 68, position: "relative" }}>
                {visible.map((t, i) => (
                  <div
                    className="track-row"
                    key={t.id}
                    style={{
                      position: "absolute",
                      top: (start + i) * 68,
                      left: 0,
                      right: 0,
                      height: 68,
                    }}
                  >
                    <button
                      className="track-play"
                      aria-label={`Play ${t.title}`}
                      onClick={() =>
                        s.play(
                          t.id,
                          rows.map((t) => t.id),
                        )
                      }
                    >
                      <Artwork track={t} small />
                      <Play size={13} />
                    </button>
                    <button className="track-title" onClick={() => setTrack(t)}>
                      <strong>{t.title}</strong>
                      <small>
                        {t.artist} ·{" "}
                        {t.album || t.path.split(".").pop()?.toUpperCase()}
                      </small>
                    </button>
                    <span className="track-duration">
                      {t.duration ? clock(t.duration) : "—"}
                    </span>
                    <button
                      className={`icon-button ${t.favorite ? "accent" : ""}`}
                      aria-label={`Favorite ${t.title}`}
                      aria-pressed={t.favorite}
                      onClick={() => s.favorite(t.id)}
                    >
                      <Heart
                        size={16}
                        fill={t.favorite ? "currentColor" : "none"}
                      />
                    </button>
                    <button
                      className="icon-button"
                      aria-label={`Actions for ${t.title}`}
                      onClick={() => setTrack(t)}
                    >
                      •••
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}
          {!rows.length && (
            <div className="card empty">
              <Music2 size={36} />
              <h2>
                {search ? "No matching songs" : "Your next favorite is waiting"}
              </h2>
              <p>
                {search
                  ? "Try a different title, artist or album."
                  : "Import local audio or complete an audio download to start listening."}
              </p>
              <button className="secondary" onClick={() => void importAudio()}>
                Import local music
              </button>
            </div>
          )}
        </>
      )}
      {create && (
        <Modal
          title={
            playlist
              ? "Rename playlist"
              : saveQueue
                ? "Save queue as playlist"
                : "Create playlist"
          }
          onClose={() => setCreate(false)}
        >
          <label className="field-label">
            Playlist name
            <input
              autoFocus
              value={name}
              maxLength={100}
              onChange={(e) => setName(e.target.value)}
            />
          </label>
          <button
            className="primary"
            disabled={!name.trim()}
            onClick={() => {
              if (playlist)
                useMusic.setState({
                  playlists: s.playlists.map((p) =>
                    p.id === playlist.id ? { ...p, title: name.trim() } : p,
                  ),
                });
              else s.savePlaylist(name, saveQueue ? s.queue : []);
              setCreate(false);
            }}
          >
            Save playlist
          </button>
        </Modal>
      )}
      {track && (
        <Modal title={track.title} onClose={() => setTrack(null)}>
          <p>
            {track.artist} · {track.album || "Unknown album"} ·{" "}
            {clock(track.duration || 0)}
          </p>
          <p className="wrap">{track.path}</p>
          <div className="track-actions">
            <button
              className="primary"
              onClick={() => {
                s.play(track.id);
                setTrack(null);
              }}
            >
              Play now
            </button>
            <button
              className="secondary"
              onClick={() => {
                s.enqueue(track.id, true);
                setTrack(null);
              }}
            >
              Play next
            </button>
            <button
              className="secondary"
              onClick={() => {
                s.enqueue(track.id);
                setTrack(null);
              }}
            >
              Add to queue
            </button>
            {
              <button
                className="secondary"
                onClick={() =>
                  void perform(() =>
                    call("open_audio_folder", { path: track.path }),
                  )
                }
              >
                <FolderOpen size={16} />
                Open output folder
              </button>
            }
            <label>
              Add to playlist
              <select
                aria-label="Add to playlist"
                value=""
                onChange={(e) => {
                  useMusic.setState({
                    playlists: s.playlists.map((p) =>
                      p.id === e.target.value
                        ? {
                            ...p,
                            tracks: [
                              ...p.tracks.filter((id) => id !== track.id),
                              track.id,
                            ],
                          }
                        : p,
                    ),
                  });
                  setTrack(null);
                }}
              >
                <option value="">Choose a playlist…</option>
                {s.playlists.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.title}
                  </option>
                ))}
              </select>
            </label>
            {playlist && (
              <>
                <button
                  className="secondary"
                  onClick={() => {
                    const ids = [...playlist.tracks];
                    const i = ids.indexOf(track.id);
                    if (i > 0) {
                      [ids[i - 1], ids[i]] = [ids[i], ids[i - 1]];
                      useMusic.setState({
                        playlists: s.playlists.map((p) =>
                          p.id === playlist.id ? { ...p, tracks: ids } : p,
                        ),
                      });
                    }
                  }}
                >
                  Move earlier in playlist
                </button>
                <button
                  className="secondary"
                  onClick={() => {
                    const ids = [...playlist.tracks];
                    const i = ids.indexOf(track.id);
                    if (i >= 0 && i < ids.length - 1) {
                      [ids[i + 1], ids[i]] = [ids[i], ids[i + 1]];
                      useMusic.setState({
                        playlists: s.playlists.map((p) =>
                          p.id === playlist.id ? { ...p, tracks: ids } : p,
                        ),
                      });
                    }
                  }}
                >
                  Move later in playlist
                </button>
                <button
                  className="secondary"
                  onClick={() => {
                    useMusic.setState({
                      playlists: s.playlists.map((p) =>
                        p.id === playlist.id
                          ? {
                              ...p,
                              tracks: p.tracks.filter((id) => id !== track.id),
                            }
                          : p,
                      ),
                    });
                    setTrack(null);
                  }}
                >
                  Remove from playlist
                </button>
              </>
            )}
            <button
              className="secondary"
              onClick={() => {
                s.remove(track.id);
                setTrack(null);
              }}
            >
              Remove from library (keep file)
            </button>
            <button
              className="secondary danger"
              onClick={() =>
                void perform(async () => {
                  if (
                    await confirmAction(
                      `Permanently delete “${track.title}” from disk? This cannot be undone.`,
                    )
                  ) {
                    await call("delete_audio", { path: track.path });
                    s.remove(track.id);
                    setTrack(null);
                  }
                })
              }
            >
              Delete file permanently…
            </button>
          </div>
        </Modal>
      )}
    </>
  );
}
export function PlayerSettings() {
  const s = useMusic();
  const effectsSupported = usePlaybackTime((s) => s.effectsSupported);
  const normalizationStatus = usePlaybackTime((s) => s.normalizationStatus);
  const change = <K extends keyof typeof s.settings>(
    key: K,
    value: (typeof s.settings)[K],
  ) => useMusic.setState({ settings: { ...s.settings, [key]: value } });
  return (
    <section className="card player-settings">
      <div className="section-head">
        <h2>Music & playback</h2>
        <Music2 size={19} />
      </div>
      <p>
        Saved automatically on this device. Playback uses Windows WebView2’s
        audio codecs.
      </p>
      <div className="settings-grid">
        {(
          [
            ["autoAdd", "Add completed audio to library"],
            ["autoPlay", "Play completed downloads automatically"],
            ["autoPlaylist", "Create playlists from downloaded playlists"],
            ["notify", "Show library notifications"],
            ["remember", "Remember playback position"],
            ["autoNext", "Play next track automatically"],
          ] as const
        ).map(([key, label]) => (
          <label className="check-line" key={key}>
            <input
              type="checkbox"
              checked={s.settings[key]}
              onChange={(e) => change(key, e.target.checked)}
            />
            <span>{label}</span>
          </label>
        ))}
        <label>
          Playback speed
          <select
            value={s.settings.speed}
            onChange={(e) => change("speed", +e.target.value)}
          >
            {[0.5, 0.75, 1, 1.25, 1.5, 2].map((n) => (
              <option key={n} value={n}>
                {n}×
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="settings-grid">
        <label>
          Crossfade{" "}
          <select
            aria-label="Crossfade duration"
            value={s.settings.crossfade}
            onChange={(e) => change("crossfade", +e.target.value)}
          >
            {[0, 1, 2, 3, 5, 8, 12].map((n) => (
              <option key={n} value={n}>
                {n ? n + " seconds" : "Off"}
              </option>
            ))}
          </select>
        </label>
        <label className="check-line">
          <input
            type="checkbox"
            checked={s.settings.gapless}
            onChange={(e) => change("gapless", e.target.checked)}
          />
          <span>Preload next track for continuous playback</span>
        </label>
        <label className="check-line">
          <input
            type="checkbox"
            checked={s.settings.resume}
            onChange={(e) => change("resume", e.target.checked)}
          />
          <span>Restore last track and queue after restart</span>
        </label>
        <label>
          Skip interval
          <select
            value={s.settings.skip}
            onChange={(e) => change("skip", +e.target.value)}
          >
            {[5, 10, 15, 30, 60].map((n) => (
              <option key={n} value={n}>
                {n} seconds
              </option>
            ))}
          </select>
        </label>
      </div>
      <h3>Audio effects</h3>
      {!effectsSupported && (
        <p role="status">
          Audio effects require Web Audio support. Basic playback and crossfade
          remain available.
        </p>
      )}
      <fieldset className="audio-effects" disabled={!effectsSupported}>
        <label className="check-line">
          <input
            type="checkbox"
            checked={s.settings.eqEnabled}
            onChange={(e) => change("eqEnabled", e.target.checked)}
          />
          <span>Enable 10-band equalizer</span>
        </label>
        <label>
          EQ preset
          <select
            aria-label="EQ preset"
            value={s.settings.eqPreset}
            onChange={(e) =>
              useMusic.setState({
                settings: {
                  ...s.settings,
                  eqEnabled: true,
                  eqPreset: e.target.value,
                  eq: [...(EQ_PRESETS[e.target.value] || s.settings.eq)],
                },
              })
            }
          >
            {[...Object.keys(EQ_PRESETS), "Custom"].map((p) => (
              <option key={p}>{p}</option>
            ))}
          </select>
        </label>
        <div className="equalizer-bands">
          {EQ_FREQUENCIES.map((hz, i) => (
            <label key={hz}>
              <span>{hz >= 1000 ? hz / 1000 + "k" : hz} Hz</span>
              <input
                aria-label={`EQ ${hz} Hz`}
                type="range"
                min="-12"
                max="12"
                step="1"
                value={s.settings.eq[i] || 0}
                onChange={(e) => {
                  const eq = [...s.settings.eq];
                  eq[i] = +e.target.value;
                  useMusic.setState({
                    settings: {
                      ...s.settings,
                      eq,
                      eqPreset: "Custom",
                      eqEnabled: true,
                    },
                  });
                }}
              />
              <small>
                {s.settings.eq[i] > 0 ? "+" : ""}
                {s.settings.eq[i] || 0} dB
              </small>
            </label>
          ))}
        </div>
        <div className="settings-grid">
          <label>
            Bass boost
            <input
              aria-label="Bass boost"
              type="range"
              min="0"
              max="12"
              step="1"
              value={s.settings.bass}
              onChange={(e) => change("bass", +e.target.value)}
            />
            <small>{s.settings.bass} dB</small>
          </label>
          <label>
            Stereo width
            <input
              aria-label="Stereo width"
              type="range"
              min="0"
              max="1.5"
              step=".1"
              value={s.settings.stereoWidth}
              onChange={(e) => change("stereoWidth", +e.target.value)}
            />
            <small>
              {s.settings.stereoWidth === 0
                ? "Mono"
                : Math.round(s.settings.stereoWidth * 100) + "%"}
            </small>
          </label>
          <label>
            Balance
            <input
              aria-label="Stereo balance"
              type="range"
              min="-1"
              max="1"
              step=".1"
              value={s.settings.balance}
              onChange={(e) => change("balance", +e.target.value)}
            />
            <small>
              {s.settings.balance === 0
                ? "Center"
                : s.settings.balance < 0
                  ? "Left"
                  : "Right"}
            </small>
          </label>
        </div>
        <label className="check-line">
          <input
            type="checkbox"
            checked={s.settings.normalization}
            onChange={(e) => change("normalization", e.target.checked)}
          />
          <span>
            Normalize loudness to −16 LUFS (true-peak limited to −1 dB)
          </span>
        </label>
        {s.settings.normalization && (
          <p role="status">
            {normalizationStatus ||
              "Tracks are measured in the background and results are cached."}
          </p>
        )}
        <p className="field-note">
          EQ boosts reserve output headroom to reduce clipping. Crossfade uses
          two streaming decks with an equal-power mix. Preloading reduces
          transition delays; sample-perfect gapless output depends on the codec
          and system.
        </p>
      </fieldset>
      <p className="field-note">
        Shortcuts: Space — play / pause · Ctrl/Cmd+F — search music · Ctrl/Cmd+,
        — settings · Escape — close dialog.
      </p>
    </section>
  );
}
