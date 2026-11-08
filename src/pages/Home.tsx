import { ArrowRight, Disc3, Heart, ListMusic, Music2, Play } from "lucide-react";
import { Artwork } from "../components/Player";
import { useMusic } from "../stores/music";
import { useApp } from "../stores/app";

export default function HomePage() {
  const { navigate } = useApp();
  const { tracks, playlists, play } = useMusic();
  const recent = [...tracks].sort((a, b) => (b.played || b.added) - (a.played || a.added)).slice(0, 8);
  const favorites = tracks.filter((track) => track.favorite);
  return <section className="desktop-home">
    <div className="desktop-home-hero">
      <div className="eyebrow">YOUR MUSIC, BEAUTIFULLY IN SYNC</div>
      <h1>Good music, right where you left it.</h1>
      <p>Your downloads, playlists and listening history in one place.</p>
      <div className="desktop-home-actions">
        <button className="primary" onClick={() => navigate("search")}><Music2 size={17} /> Find music</button>
        <button className="secondary" onClick={() => navigate("explore")}><ArrowRight size={17} /> Explore downloads</button>
      </div>
    </div>
    <div className="desktop-home-stats">
      <button onClick={() => navigate("library")}><Disc3 /><span><strong>{tracks.length}</strong><small>Library tracks</small></span></button>
      <button onClick={() => navigate("playlists")}><ListMusic /><span><strong>{playlists.length}</strong><small>Playlists</small></span></button>
      <button onClick={() => { sessionStorage.setItem("ythub-library-filter", "Favorites"); navigate("library"); }}><Heart /><span><strong>{favorites.length}</strong><small>Favorites</small></span></button>
    </div>
    <section className="desktop-home-section">
      <div className="section-head"><div><h2>Recently played</h2><p>Pick up where you left off.</p></div><button className="text-button" onClick={() => navigate("history")}>Listening history <ArrowRight size={15} /></button></div>
      {recent.length ? <div className="desktop-recent-grid">{recent.map((track) => <button className="desktop-recent-card" key={track.id} onClick={() => play(track.id)}><Artwork track={track} small /><span><strong>{track.title}</strong><small>{track.artist || "Unknown artist"}</small></span><Play size={17} /></button>)}</div> : <div className="desktop-home-empty"><Music2 /><div><strong>Your listening starts here</strong><span>Search for music or import audio saved on this computer.</span></div><button className="text-button" onClick={() => navigate("library")}>Open library <ArrowRight size={15} /></button></div>}
    </section>
    <section className="desktop-home-section">
      <div className="section-head"><div><h2>Your playlists</h2><p>Collections from your local music library.</p></div><button className="text-button" onClick={() => navigate("playlists")}>See all <ArrowRight size={15} /></button></div>
      {playlists.length ? <div className="desktop-playlist-grid">{playlists.slice(0, 6).map((list) => { const track = tracks.find((t) => list.tracks.includes(t.id)); return <button className="desktop-playlist-card" key={list.id} onClick={() => navigate("playlists")}><Artwork track={track} /><strong>{list.title}</strong><small>{list.tracks.length} songs</small></button>; })}</div> : <div className="desktop-home-empty"><ListMusic /><div><strong>No playlists yet</strong><span>Create a playlist from your library or save a download collection.</span></div><button className="text-button" onClick={() => navigate("library")}>Browse library <ArrowRight size={15} /></button></div>}
    </section>
  </section>;
}
