import { useEffect, useState } from "react";
import { ArrowDownToLine, ArrowUpRight, Heart, ListPlus, LoaderCircle, Music2, Play, Search as SearchIcon } from "lucide-react";
import { call, desktop } from "../api";
import { requireCloud } from "../account/client";
import { perform, useApp } from "../stores/app";
import type { Media } from "../types";
import { time } from "../utils/media";
import { Artwork } from "../components/Player";
import { useMusic } from "../stores/music";

type Result = {
  id: string;
  type: string;
  title: string;
  artist: string;
  channel: string;
  thumbnail: string;
  source_url: string;
  duration_seconds: number | null;
};
type SearchResponse = { items: Result[]; nextPageToken: string | null };
const filters = ["all", "songs", "videos", "playlists", "artists", "channels"];
const desktopFilters = ["all", "songs", "videos", "playlists"];

export default function DiscoverySearch() {
  const { db, navigate, notify } = useApp();
  const music = useMusic();
  const [query, setQuery] = useState("");
  const [source, setSource] = useState<"youtube" | "library">("youtube");
  const [filter, setFilter] = useState("all");
  const [items, setItems] = useState<Result[]>([]);
  const [nextToken, setNextToken] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [moreBusy, setMoreBusy] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    const normalized = query.trim();
    if (source === "library") {
      setItems([]);
      setError("");
      setBusy(false);
      setNextToken(null);
      return;
    }
    let current = true;
    setNextToken(null);
    if (normalized.length < 2) {
      setItems([]);
      setError("");
      setBusy(false);
      return () => { current = false; };
    }
    const timer = window.setTimeout(async () => {
      setBusy(true);
      setError("");
      try {
        const result = desktop
          ? await call<SearchResponse>("search_youtube", { query: normalized, kind: filter })
          : await (async () => {
              const { data, error: invokeError } = await requireCloud().functions.invoke<SearchResponse>("youtube-discovery", { body: { q: normalized, type: filter } });
              if (invokeError) throw invokeError;
              return data;
            })();
        if (current) {
          setItems(result?.items || []);
          setNextToken(result?.nextPageToken || null);
        }
      } catch (cause) {
        if (current) {
          setItems([]);
          setError(cause instanceof Error ? cause.message : "Search could not be completed.");
        }
      } finally {
        if (current) setBusy(false);
      }
    }, 300);
    return () => { current = false; window.clearTimeout(timer); };
  }, [query, filter, source]);

  const localResults = source === "library" && query.trim().length >= 1
    ? music.tracks.filter((track) => [track.title, track.artist, track.album].some((field) => field.toLowerCase().includes(query.trim().toLowerCase())))
    : [];

  async function loadMore() {
    if (!nextToken || moreBusy) return;
    setMoreBusy(true);
    try {
      const { data, error: invokeError } = await requireCloud().functions.invoke<SearchResponse>("youtube-discovery", { body: { q: query.trim(), type: filter, pageToken: nextToken } });
      if (invokeError) throw invokeError;
      setItems((current) => [...current, ...(data?.items || []).filter((item) => !current.some((old) => old.id === item.id))]);
      setNextToken(data?.nextPageToken || null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "More results could not be loaded.");
    } finally {
      setMoreBusy(false);
    }
  }

  function enqueue(result: Result) {
    setBusyId(result.id);
    void perform(async () => {
      const playlist = result.type === "playlist";
      const media = await call<Media>("analyze_url", { url: result.source_url, playlist });
      const settings = useApp.getState().db?.settings;
      await call("enqueue", { request: {
        url: result.source_url,
        mediaType: "audio",
        quality: settings?.audioQuality || "320",
        playlist: playlist && media.isPlaylist,
        createZip: playlist && Boolean(settings?.autoZip),
        preserveOrder: settings?.preserveOrder ?? true,
      } });
      notify("Added to your local download queue.");
      navigate("downloads");
    }).finally(() => setBusyId(null));
  }

  return (
    <section className="discovery-page">
      <div className="page-heading">
        <div>
          <div className="eyebrow">MUSIC AND VIDEO DISCOVERY</div>
          <h1>Find something to play</h1>
          <p>{desktop ? "Live YouTube search works without a cloud account. Downloads are checked by the local media engine." : "Search YouTube, then check eligible items with the local media engine."}</p>
        </div>
        {db?.settings && <span className="local-pill"><span /> Downloads stay on this device</span>}
      </div>
      <form className="discovery-search" onSubmit={(event) => event.preventDefault()}>
        <SearchIcon size={21} />
        <input id="youtube-discovery" autoComplete="off" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search songs, artists, videos, playlists and channels" />
        {query && <button className="icon-button" type="button" aria-label="Clear search" onClick={() => setQuery("")}>×</button>}
        <span className="search-shortcut">Ctrl F</span>
      </form>
      <div className="desktop-search-sources" role="tablist" aria-label="Search source">
        <button role="tab" aria-selected={source === "youtube"} className={source === "youtube" ? "selected" : ""} onClick={() => setSource("youtube")}><ArrowUpRight size={16} /> YouTube</button>
        <button role="tab" aria-selected={source === "library"} className={source === "library" ? "selected" : ""} onClick={() => setSource("library")}><Music2 size={16} /> Library</button>
      </div>
      {source === "youtube" && <>
      <div className="library-tabs discovery-filters" role="group" aria-label="Search category">
        {(desktop ? desktopFilters : filters).map((item) => <button key={item} className={filter === item ? "selected" : ""} aria-pressed={filter === item} onClick={() => setFilter(item)}>{item[0].toUpperCase() + item.slice(1)}</button>)}
      </div>
      <div className="discovery-heading">
        <h2>{query.trim().length >= 2 ? `Results for “${query.trim()}”` : "Start with a song, artist or video"}</h2>
        {items.length > 0 && <span>{items.length} results</span>}
      </div>
      {busy && <div className="discovery-status"><LoaderCircle className="spin" size={19} /> Searching YouTube…</div>}
      {error && <div className="alert" role="alert"><p>{error}</p></div>}
      {!busy && !error && query.trim().length > 0 && query.trim().length < 2 && <p className="muted">Enter at least two characters to search.</p>}
      {!busy && !error && query.trim().length >= 2 && items.length === 0 && <div className="card empty"><Music2 size={24} /><h2>No results yet</h2><p>Try another title or switch the category.</p></div>}
      <div className="discovery-results">
        {items.map((result) => <article className="discovery-row" key={`${result.type}-${result.id}`}>
          <div className="discovery-art">
            {result.thumbnail ? <img src={result.thumbnail} alt="" referrerPolicy="no-referrer" /> : <Music2 size={22} />}
          </div>
          <div className="discovery-meta">
            <strong title={result.title}>{result.title}</strong>
            <span>{result.artist || result.channel || result.type} · {result.type}{result.duration_seconds ? ` · ${time(result.duration_seconds)}` : ""}</span>
            <small>YouTube metadata only · download availability is checked on request</small>
          </div>
          <div className="discovery-actions">
            {result.type === "playlist" || result.type === "video" ? <button className="secondary" disabled={busyId === result.id} onClick={() => enqueue(result)}>
              {busyId === result.id ? <LoaderCircle className="spin" size={15} /> : <ArrowDownToLine size={15} />} {result.type === "playlist" ? "Download playlist" : "Save audio"}
            </button> : null}
            <a className="icon-button" href={result.source_url} target="_blank" rel="noreferrer" aria-label={`Open ${result.title} on YouTube`} title="Open on YouTube"><ArrowUpRight size={17} /></a>
          </div>
        </article>)}
      </div>
      {nextToken && <button className="secondary discovery-more" disabled={moreBusy} onClick={() => void loadMore()}>{moreBusy ? <LoaderCircle className="spin" size={16} /> : null} Load more</button>}
      <p className="discovery-note">Search results provide metadata and links. The local engine checks whether a source can be processed when you request a download. Only download content you have permission to use.</p>
      </>}
      {source === "library" && <>
        <div className="discovery-heading"><h2>{query.trim() ? `Library results for “${query.trim()}”` : "Search your music library"}</h2>{localResults.length > 0 && <span>{localResults.length} tracks</span>}</div>
        {!query.trim() && <div className="card empty"><Music2 size={24} /><h2>Your local music</h2><p>Search by track, artist, or album. Local playback remains available offline.</p></div>}
        {query.trim() && localResults.length === 0 && <div className="card empty"><Music2 size={24} /><h2>No matching tracks</h2><p>Try a different title, artist, or album.</p></div>}
        <div className="discovery-results">{localResults.map((track) => <article className="discovery-row local-search-row" key={track.id}>
          <Artwork track={track} small />
          <div className="discovery-meta"><strong title={track.title}>{track.title}</strong><span>{track.artist || "Unknown artist"}{track.album ? ` · ${track.album}` : ""}</span></div>
          <div className="discovery-actions">
            <button className="icon-button" title="Play" aria-label={`Play ${track.title}`} onClick={() => { music.play(track.id); }}><Play size={17} /></button>
            <button className={`icon-button ${track.favorite ? "accent" : ""}`} title={track.favorite ? "Remove favorite" : "Add favorite"} aria-label={track.favorite ? "Remove favorite" : "Add favorite"} onClick={() => music.favorite(track.id)}><Heart size={17} fill={track.favorite ? "currentColor" : "none"} /></button>
            <button className="icon-button" title="Add to queue" aria-label={`Add ${track.title} to queue`} onClick={() => music.enqueue(track.id)}><ListPlus size={17} /></button>
          </div>
        </article>)}</div>
      </>}
    </section>
  );
}
