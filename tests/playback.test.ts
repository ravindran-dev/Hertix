vi.mock("@tauri-apps/api/core", () => ({
  isTauri: () => false,
  convertFileSrc: (path: string) => `asset://${path}`,
}));
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { waitFor } from "@testing-library/react";
import {
  startAudioService,
  useMusic,
  usePlaybackTime,
} from "../src/stores/music";
import { call } from "../src/api";
import { useApp } from "../src/stores/app";
vi.mock("../src/api", () => ({ call: vi.fn(), desktop: true }));
class AudioStub {
  src = "";
  preload = "";
  volume = 1;
  playbackRate = 1;
  currentTime = 0;
  duration = 180;
  play = vi.fn(async () => {});
  pause = vi.fn();
  load = vi.fn();
  removeAttribute = vi.fn(() => {
    this.src = "";
  });
  onloadedmetadata: (() => void) | null = null;
  ontimeupdate: (() => void) | null = null;
  onended: (() => void) | null = null;
  onerror: (() => void) | null = null;
}
let player: AudioStub;
let stop: (() => void) | undefined;
beforeEach(() => {
  vi.clearAllMocks();
  player = undefined as unknown as AudioStub;
  vi.stubGlobal(
    "Audio",
    class extends AudioStub {
      constructor() {
        super();
        // eslint-disable-next-line @typescript-eslint/no-this-alias -- Capture the single test audio instance.
        if (!player) player = this;
      }
    },
  );
  useMusic.setState({
    tracks: ["Alpha", "Beta"].map((title, i) => ({
      id: String(i),
      title,
      path: `C:/Music/${title}.mp3`,
      artist: "Artist",
      album: "",
      duration: 180,
      added: 0,
      favorite: false,
    })),
    queue: [],
    index: 0,
    playing: false,
    request: 0,
    repeat: "off",
  });
  useApp.setState({ error: null });
  vi.mocked(call).mockImplementation(async (_cmd, args) => ({
    path: args?.path,
    lyrics: null,
    artwork: null,
  }));
  stop = startAudioService();
});
afterEach(() => {
  stop?.();
  vi.unstubAllGlobals();
});
describe("single playback service", () => {
  it("loads validated local sources and synchronizes play, pause, volume and speed", async () => {
    useMusic.getState().play("0");
    await waitFor(() => expect(player.play).toHaveBeenCalled());
    expect(call).toHaveBeenCalledWith("prepare_audio", {
      path: "C:/Music/Alpha.mp3",
      lyrics: true,
    });
    expect(player.src).toContain("Alpha.mp3");
    useMusic.getState().toggle();
    expect(player.pause).toHaveBeenCalled();
    useMusic.setState({
      volume: 0.3,
      settings: { ...useMusic.getState().settings, speed: 1.5 },
    });
    expect(player.volume).toBe(0.3);
    expect(player.playbackRate).toBe(1.5);
  });
  it("ignores stale file preparation when a newer track is selected", async () => {
    let finish: (value: unknown) => void = () => {};
    vi.mocked(call).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    useMusic.getState().play("0");
    useMusic.getState().play("1");
    await waitFor(() => expect(player.src).toContain("Beta.mp3"));
    finish({ path: "C:/Music/Alpha.mp3" });
    await Promise.resolve();
    expect(player.src).toContain("Beta.mp3");
  });
  it("does not resume after pausing during file validation", async () => {
    let finish: (value: unknown) => void = () => {};
    vi.mocked(call).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    useMusic.getState().play("0");
    useMusic.getState().toggle();
    finish({ path: "C:/Music/Alpha.mp3" });
    await waitFor(() => expect(player.src).toContain("Alpha.mp3"));
    expect(player.play).not.toHaveBeenCalled();
  });
  it("advances after an actual ended event and reports a missing file", async () => {
    useMusic.getState().play("0");
    await waitFor(() => expect(player.src).toContain("Alpha.mp3"));
    player.onended?.();
    await waitFor(() => expect(player.src).toContain("Beta.mp3"));
    vi.mocked(call).mockRejectedValueOnce(new Error("File is missing"));
    useMusic.getState().play("0");
    await waitFor(() =>
      expect(useApp.getState().error).toContain("File is missing"),
    );
    expect(useMusic.getState().playing).toBe(false);
  });
  it("updates playback time without rewriting the library on each tick", async () => {
    useMusic.getState().play("0");
    await waitFor(() => expect(player.src).toContain("Alpha.mp3"));
    const write = vi.spyOn(Storage.prototype, "setItem");
    write.mockClear();
    player.currentTime = 15;
    player.ontimeupdate?.();
    player.currentTime = 16;
    player.ontimeupdate?.();
    expect(usePlaybackTime.getState().position).toBe(16);
    expect(
      write.mock.calls.filter(([key]) => key === "ythub-music-v1"),
    ).toHaveLength(0);
    write.mockRestore();
  });
});
