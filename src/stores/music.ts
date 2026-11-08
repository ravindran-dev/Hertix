import { create } from "zustand";
import { musicStorage } from "../audio/persistence";
import { createJSONStorage, persist } from "zustand/middleware";
import { DEFAULT_EFFECTS } from "../audio/effects";
import type { EffectSettings } from "../audio/effects";
export { startAudioService, seek } from "../audio/playback";
import { call } from "../api";
export interface Track {
  id: string;
  path: string;
  title: string;
  artist: string;
  album: string;
  duration: number | null;
  lyrics?: string | null;
  artwork?: string | null;
  added: number;
  favorite: boolean;
  played?: number;
  jobId?: string;
}
export interface Playlist {
  id: string;
  title: string;
  tracks: string[];
}
export interface PlayerSettings extends EffectSettings {
  autoAdd: boolean;
  autoPlay: boolean;
  autoPlaylist: boolean;
  notify: boolean;
  remember: boolean;
  autoNext: boolean;
  speed: number;
  skip: number;
}
export const usePlaybackTime = create<{
  position: number;
  duration: number;
  loading: boolean;
  transitioning: boolean;
  effectsSupported: boolean;
  normalizationStatus: string;
}>(() => ({
  position: 0,
  duration: 0,
  loading: false,
  transitioning: false,
  effectsSupported: typeof AudioContext !== "undefined",
  normalizationStatus: "",
}));
interface MusicState {
  tracks: Track[];
  excludedPaths: string[];
  playlists: Playlist[];
  queue: string[];
  index: number;
  playing: boolean;
  request: number;
  overlay: boolean;
  volume: number;
  shuffle: boolean;
  repeat: "off" | "all" | "one";
  panel: boolean;
  selectedPlaylist: string | null;
  settings: PlayerSettings;
  add: (track: Track) => void;
  play: (id: string, ids?: string[]) => void;
  toggle: () => void;
  next: (direction?: number) => void;
  enqueue: (id: string, next?: boolean) => void;
  move: (from: number, to: number) => void;
  favorite: (id: string) => void;
  remove: (id: string) => void;
  savePlaylist: (title: string, ids: string[]) => void;
}
export const useMusic = create<MusicState>()(
  persist(
    (set, get) => ({
      tracks: [],
      excludedPaths: [],
      playlists: [],
      queue: [],
      index: 0,
      playing: false,
      request: 0,
      overlay: false,
      volume: 0.8,
      shuffle: false,
      repeat: "off",
      panel: true,
      selectedPlaylist: null,
      settings: {
        ...DEFAULT_EFFECTS,
        autoAdd: true,
        autoPlay: false,
        autoPlaylist: true,
        notify: true,
        remember: true,
        autoNext: true,
        speed: 1,
        skip: 10,
      },
      add: (track) =>
        set((s) => ({
          tracks: s.tracks.some(
            (t) => t.path.toLowerCase() === track.path.toLowerCase(),
          )
            ? s.tracks
            : [...s.tracks, track],
        })),
      play: (id, ids) => {
        const s = get();
        let queue = ids || s.tracks.map((t) => t.id);
        const index = queue.indexOf(id);
        if (s.shuffle) queue = shuffledUpcoming(queue, index);
        if (index >= 0)
          set({ queue, index, request: s.request + 1, playing: true });
      },
      toggle: () => {
        if (get().queue.length) set((s) => ({ playing: !s.playing }));
      },
      next: (direction = 1) => {
        const s = get();
        let index = s.index + direction;
        if (index >= s.queue.length && s.repeat === "all") index = 0;
        if (index < 0) index = 0;
        if (index >= s.queue.length) {
          set({ playing: false });
          return;
        }
        set({ index, request: s.request + 1, playing: true });
      },
      enqueue: (id, next = false) =>
        set((s) => {
          const queue = [...s.queue];
          queue.splice(next ? s.index + 1 : queue.length, 0, id);
          return { queue };
        }),
      move: (from, to) =>
        set((s) => {
          if (from <= s.index || to <= s.index || to >= s.queue.length)
            return {};
          const queue = [...s.queue];
          queue.splice(to, 0, queue.splice(from, 1)[0]);
          return { queue };
        }),
      favorite: (id) =>
        set((s) => ({
          tracks: s.tracks.map((t) =>
            t.id === id ? { ...t, favorite: !t.favorite } : t,
          ),
        })),
      remove: (id) =>
        set((s) => {
          const before = s.queue
            .slice(0, s.index)
            .filter((t) => t === id).length;
          const current = s.queue[s.index] === id;
          return {
            excludedPaths: [
              ...s.excludedPaths,
              ...s.tracks
                .filter((t) => t.id === id)
                .map((t) => t.path.toLowerCase()),
            ],
            tracks: s.tracks.filter((t) => t.id !== id),
            queue: s.queue.filter((t) => t !== id),
            index: Math.max(0, s.index - before),
            playing: current ? false : s.playing,
            playlists: s.playlists.map((p) => ({
              ...p,
              tracks: p.tracks.filter((t) => t !== id),
            })),
          };
        }),
      savePlaylist: (title, tracks) =>
        set((s) => ({
          playlists: [
            ...s.playlists,
            {
              id: crypto.randomUUID(),
              title: title.trim() || "Untitled playlist",
              tracks,
            },
          ],
        })),
    }),
    {
      name: "ythub-music-v1",
      storage: createJSONStorage(() => musicStorage),
      merge: (saved, current) => ({
        ...current,
        ...(saved as Partial<MusicState>),
        settings: {
          ...current.settings,
          ...(saved as Partial<MusicState>)?.settings,
        },
      }),
      partialize: (s) => ({
        tracks: s.tracks.map((t) => ({ ...t, lyrics: undefined })),
        excludedPaths: s.excludedPaths,
        playlists: s.playlists,
        queue: s.queue,
        index: s.index,
        volume: s.volume,
        repeat: s.repeat,
        shuffle: s.shuffle,
        settings: s.settings,
        panel: s.panel,
      }),
    },
  ),
);
export async function importTrack(
  path: string,
  job?: { id: string; title: string; duration: number | null },
) {
  const info = await call<{
    path: string;
    title: string;
    artist: string;
    album: string;
    duration: number | null;
    lyrics: string | null;
    artwork: string | null;
  }>("prepare_audio", { path });
  const old = useMusic
    .getState()
    .tracks.find((t) => t.path.toLowerCase() === info.path.toLowerCase());
  if (old) return old;
  const track: Track = {
    ...info,
    id: crypto.randomUUID(),
    title: info.title || job?.title || "Untitled",
    duration: info.duration || job?.duration || null,
    added: Date.now(),
    favorite: false,
    jobId: job?.id,
  };
  useMusic.setState({
    excludedPaths: useMusic
      .getState()
      .excludedPaths.filter((p) => p !== info.path.toLowerCase()),
  });
  useMusic.getState().add(track);
  return track;
}
export function togglePlayerPanel() {
  const s = useMusic.getState();
  useMusic.setState({ panel: true, overlay: !s.overlay });
}

function shuffledUpcoming(queue: string[], index: number) {
  const result = [...queue];
  for (let i = result.length - 1; i > index + 1; i--) {
    const j = index + 1 + Math.floor(Math.random() * (i - index));
    [result[i], result[j]] = [result[j], result[i]];
  }
  return result;
}
export function toggleShuffle() {
  const s = useMusic.getState();
  useMusic.setState({
    shuffle: !s.shuffle,
    queue: s.shuffle ? s.queue : shuffledUpcoming(s.queue, s.index),
  });
}
