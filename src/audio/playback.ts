import { convertFileSrc } from "@tauri-apps/api/core";
import { call } from "../api";
import { useApp } from "../stores/app";
import { useMusic, usePlaybackTime } from "../stores/music";
import { AudioEffects, equalPower } from "./effects";
type Info = {
  path: string;
  duration: number | null;
  lyrics: string | null;
  artwork: string | null;
};
let service: PlaybackEngine | undefined;
export function startAudioService() {
  if (service) return () => {};
  const engine = (service = new PlaybackEngine());
  engine.start();
  return () => {
    engine.dispose();
    if (service === engine) service = undefined;
  };
}
export function seek(position: number) {
  service?.seek(position);
}
/** A single transport owns two streaming decks, preloading and mixing the next track. */
export class PlaybackEngine {
  private decks = [new Audio(), new Audio()];
  private active = 0;
  private graph: AudioEffects | undefined;
  private generation = 0;
  private preloadGeneration = 0;
  private loaded = "";
  private request = -1;
  private staged: { id: string; index: number; info: Info } | undefined;
  private fade:
    { outgoing: number; started: number; seconds: number } | undefined;
  private transitioning = false;
  private timer: ReturnType<typeof setInterval> | undefined;
  private off: (() => void) | undefined;
  private checkpoint = 0;
  private disposed = false;
  private graphTried = false;
  private get player() {
    return this.decks[this.active];
  }
  private nextIndex() {
    const s = useMusic.getState();
    if (s.repeat === "one") return -1;
    return s.index + 1 < s.queue.length
      ? s.index + 1
      : s.repeat === "all" && s.queue.length > 1
        ? 0
        : -1;
  }
  start() {
    this.decks.forEach((deck, i) => {
      deck.preload = "auto";
      deck.crossOrigin = "anonymous";
      deck.onloadedmetadata = () => {
        if (i !== this.active) return;
        usePlaybackTime.setState({
          duration: Number.isFinite(deck.duration) ? deck.duration : 0,
        });
      };
      deck.ontimeupdate = () => {
        if (i === this.active) this.tick();
      };
      deck.onended = () => {
        if (i === this.active) void this.ended();
      };
      deck.onerror = () => {
        if (i === this.active) {
          useMusic.setState({ playing: false });
          useApp
            .getState()
            .fail(
              "This file cannot be played. Check that it exists and that its codec is supported by Windows WebView2.",
            );
        }
      };
    });
    this.off = useMusic.subscribe((s, p) => {
      const trackChanged =
        s.queue[s.index] !== p.queue[p.index] || s.request !== p.request;
      if (trackChanged && !this.transitioning) void this.load();
      else if (s.playing !== p.playing) void this.transport();
      if (s.volume !== p.volume || s.settings !== p.settings) this.configure();
      if (
        s.queue !== p.queue ||
        s.repeat !== p.repeat ||
        s.settings.gapless !== p.settings.gapless ||
        s.settings.crossfade !== p.settings.crossfade
      )
        void this.preload();
      if (s.settings.normalization !== p.settings.normalization)
        void this.normalize(this.active);
    });
    this.configure();
    void this.load();
    this.timer = setInterval(() => this.tick(), 50);
  }
  private ensureGraph() {
    if (this.graphTried) return;
    this.graphTried = true;
    if (typeof AudioContext !== "undefined") {
      try {
        this.graph = new AudioEffects();
        this.decks.forEach((d) => this.graph!.attach(d));
        usePlaybackTime.setState({ effectsSupported: true });
      } catch (e) {
        usePlaybackTime.setState({ effectsSupported: false });
        useApp.getState().fail(`Audio effects unavailable: ${String(e)}`);
      }
    } else usePlaybackTime.setState({ effectsSupported: false });
  }
  private configure() {
    const s = useMusic.getState();
    if (s.playing) {this.ensureGraph();void this.graph?.resume().catch(e=>useApp.getState().fail(e));}
    this.graph?.configure(s.settings, s.volume);
    this.decks.forEach((d) => {
      d.playbackRate = s.settings.speed;
      d.volume = this.graph ? 1 : s.volume;
    });
    if (this.fade) this.mix();
    else {
      this.gain(this.active, 1);
      this.gain(1 - this.active, 0);
    }
  }
  private gain(index: number, gain: number) {
    if (this.graph) this.graph.gain(this.decks[index], gain);
    else this.decks[index].volume = useMusic.getState().volume * gain;
  }
  private cancelFade() {
    if (this.fade) this.decks[this.fade.outgoing].pause();
    this.fade = undefined;
    this.gain(this.active, 1);
    this.gain(1 - this.active, 0);
    usePlaybackTime.setState({ transitioning: false });
  }
  private async play(deck: HTMLAudioElement) {
    try {
      await this.graph?.resume();
      await deck.play();
    } catch (e) {
      if (
        (e as Error).name !== "AbortError" &&
        !this.disposed &&
        deck === this.player
      ) {
        useMusic.setState({ playing: false });
        useApp.getState().fail(`Playback failed: ${String(e)}`);
      }
    }
  }
  private async transport() {
    const s = useMusic.getState();
    this.configure();
    if (s.playing) {
      if (this.player.src) await this.play(this.player);
    } else {
      this.cancelFade();
      this.decks.forEach((d) => d.pause());
      this.savePosition();
      void this.preload();
    }
  }
  private async load() {
    const s = useMusic.getState(),
      track = s.tracks.find((t) => t.id === s.queue[s.index]);
    if (!track) {
      ++this.generation;
      this.loaded = "";
      this.cancelFade();
      this.decks.forEach((d) => {
        d.pause();
        d.removeAttribute("src");
      });
      return;
    }
    if (this.loaded === track.id && this.request === s.request) return;
    const token = ++this.generation;
    this.request = s.request;
    this.loaded = track.id;
    this.cancelFade();
    this.decks.forEach((d) => d.pause());
    this.configure();
    this.player.removeAttribute("src");
    this.staged = undefined;
    ++this.preloadGeneration;
    usePlaybackTime.setState({ position: 0, duration: 0, loading: true });
    try {
      const info = await call<Info>("prepare_audio", {
        path: track.path,
        lyrics: true,
      });
      if (token !== this.generation || this.disposed) return;
      const deck = this.player;
      deck.src = convertFileSrc(info.path);
      deck.load();
      const saved = this.position(track.id);
      if (s.settings.remember && saved > 0) {
        const restore = () => {
          if (deck === this.player && saved < deck.duration)
            deck.currentTime = saved;
        };
        deck.addEventListener?.("loadedmetadata", restore, { once: true });
      }
      this.mark(track.id, info);
      usePlaybackTime.setState({ loading: false });
      void this.normalize(this.active);
      await this.transport();
      void this.preload();
    } catch (e) {
      if (token === this.generation && !this.disposed) {
        this.loaded = "";
        usePlaybackTime.setState({ loading: false });
        useMusic.setState({ playing: false });
        useApp.getState().fail(e);
      }
    }
  }
  private mark(id: string, info: Info) {
    useMusic.setState({
      tracks: useMusic.getState().tracks.map((t) =>
        t.id === id
          ? {
              ...t,
              played: Date.now(),
              duration: info.duration ?? t.duration,
              lyrics: info.lyrics ?? t.lyrics,
              artwork: info.artwork,
            }
          : t,
      ),
    });
  }
  private async preload() {
    const s = useMusic.getState(),
      index = this.nextIndex(),
      id = s.queue[index];
    if (
      this.fade ||
      (!s.settings.gapless && s.settings.crossfade <= 0) ||
      index < 0 ||
      !id
    ) {
      if (!this.fade) this.staged = undefined;
      return;
    }
    if (this.staged?.id === id && this.staged.index === index) return;
    const track = s.tracks.find((t) => t.id === id);
    if (!track) return;
    const token = ++this.preloadGeneration,
      deck = this.decks[1 - this.active];
    this.staged = undefined;
    deck.pause();
    try {
      const info = await call<Info>("prepare_audio", {
        path: track.path,
        lyrics: true,
      });
      if (token !== this.preloadGeneration || this.disposed || this.fade)
        return;
      deck.src = convertFileSrc(info.path);
      deck.currentTime = 0;
      deck.load();
      this.staged = { id, index, info };
      void this.normalize(1 - this.active, track.path);
    } catch {
      /* Report errors only if this file becomes the active selection. */
    }
  }
  private async normalize(index: number, path?: string) {
    const s = useMusic.getState();
    this.graph?.normalize(this.decks[index], 0);
    if (!this.graph || !s.settings.normalization) return;
    const track = path
      ? undefined
      : s.tracks.find((t) => t.id === s.queue[s.index]);
    const file = path || track?.path;
    if (!file) return;
    const src = this.decks[index].src;
    if (index === this.active)
      usePlaybackTime.setState({ normalizationStatus: "Measuring loudness…" });
    try {
      const db = await call<number>("audio_gain", { path: file });
      if (
        this.disposed ||
        this.decks[index].src !== src ||
        !useMusic.getState().settings.normalization
      )
        return;
      this.graph?.normalize(this.decks[index], db);
      if (index === this.active)
        usePlaybackTime.setState({
          normalizationStatus: `Loudness gain ${db.toFixed(1)} dB`,
        });
    } catch (e) {
      if (index === this.active)
        usePlaybackTime.setState({
          normalizationStatus: `Loudness analysis unavailable: ${String(e)}`,
        });
    }
  }
  private position(id: string) {
    try {
      return (
        JSON.parse(localStorage.getItem("ythub-position") || "{}")[id] || 0
      );
    } catch {
      return 0;
    }
  }
  private savePosition(ended = false) {
    const s = useMusic.getState();
    if (!s.settings.remember || !s.queue[s.index]) return;
    let value: Record<string, number> = {};
    try {
      value = JSON.parse(localStorage.getItem("ythub-position") || "{}");
    } catch {
      /* damaged checkpoint */
    }
    value[s.queue[s.index]] = ended ? 0 : this.player.currentTime;
    try {
      localStorage.setItem("ythub-position", JSON.stringify(value));
    } catch {
      /* playback must survive a storage failure */
    }
  }
  private tick() {
    if (this.disposed) return;
    const s = useMusic.getState(),
      deck = this.player;
    usePlaybackTime.setState({
      position: deck.currentTime,
      duration: Number.isFinite(deck.duration) ? deck.duration : 0,
    });
    if (s.playing && Date.now() - this.checkpoint >= 5000) {
      this.checkpoint = Date.now();
      this.savePosition();
    }
    if (this.fade) {
      this.mix();
      return;
    }
    const next = this.decks[1 - this.active];
    const seconds = Math.min(
      s.settings.crossfade,
      deck.duration / (2 * s.settings.speed),
      next.duration / (2 * s.settings.speed),
    );
    if (
      s.playing &&
      s.settings.autoNext &&
      seconds > 0 &&
      this.staged &&
      next.readyState >= 2 &&
      deck.duration > 0 &&
      deck.duration - deck.currentTime <= seconds * s.settings.speed
    )
      void this.advance(seconds);
  }
  private mix() {
    if (!this.fade) return;
    const progress =
      (Date.now() - this.fade.started) / (this.fade.seconds * 1000);
    const [outgoing, incoming] = equalPower(progress);
    this.gain(this.fade.outgoing, outgoing);
    this.gain(this.active, incoming);
    if (progress >= 1) {
      this.decks[this.fade.outgoing].pause();
      this.fade = undefined;
      usePlaybackTime.setState({ transitioning: false });
      void this.preload();
    }
  }
  private async advance(seconds: number) {
    const staged = this.staged;
    if (!staged || this.fade) return;
    const s = useMusic.getState();
    if (s.queue[this.nextIndex()] !== staged.id) return;
    const outgoing = this.active,
      incoming = 1 - outgoing,
      deck = this.decks[incoming];
    this.staged = undefined;
    this.savePosition(true);
    this.active = incoming;
    this.loaded = staged.id;
    this.transitioning = true;
    useMusic.setState({ index: staged.index, request: s.request + 1 });
    this.request = useMusic.getState().request;
    this.transitioning = false;
    this.mark(staged.id, staged.info);
    deck.currentTime = 0;
    if (seconds > 0) {
      this.fade = { outgoing, started: Date.now(), seconds };
      this.gain(outgoing, 1);
      this.gain(incoming, 0);
      usePlaybackTime.setState({ transitioning: true });
    } else {
      this.decks[outgoing].pause();
      this.gain(outgoing, 0);
      this.gain(incoming, 1);
    }
    usePlaybackTime.setState({
      position: 0,
      duration: Number.isFinite(deck.duration) ? deck.duration : 0,
    });
    await this.play(deck);
    if (!seconds) void this.preload();
  }
  private async ended() {
    if (this.fade) return;
    this.savePosition(true);
    const s = useMusic.getState();
    if (s.repeat === "one") {
      this.player.currentTime = 0;
      await this.play(this.player);
    } else if (!s.settings.autoNext) useMusic.setState({ playing: false });
    else if (this.staged && this.decks[1 - this.active].readyState >= 2)
      await this.advance(0);
    else s.next();
  }
  seek(position: number) {
    this.cancelFade();
    if (Number.isFinite(this.player.duration)) {
      this.player.currentTime = Math.max(
        0,
        Math.min(position, this.player.duration),
      );
      usePlaybackTime.setState({ position: this.player.currentTime });
    }
  }
  dispose() {
    this.savePosition();
    this.disposed = true;
    ++this.generation;
    ++this.preloadGeneration;
    this.off?.();
    clearInterval(this.timer);
    this.decks.forEach((d) => {
      d.pause();
      d.removeAttribute("src");
      d.onended = null;
      d.ontimeupdate = null;
      d.onerror = null;
      d.onloadedmetadata = null;
    });
    void this.graph?.close();
  }
}
