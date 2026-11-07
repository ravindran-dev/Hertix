import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import { afterEach } from "vitest";
import { useMusic, importTrack } from "../src/stores/music";
import type { Track } from "../src/stores/music";
import Library, { PlayerSettings } from "../src/pages/Library";
import { Player } from "../src/components/Player";
import { call } from "../src/api";
vi.mock("../src/api", () => ({
  call: vi.fn(),
  desktop: true,
  confirmAction: vi.fn(async () => true),
}));
const tracks: Track[] = ["Alpha", "Beta", "Gamma"].map((title, i) => ({
  id: String(i),
  path: `C:/music/${title}.mp3`,
  title,
  artist: "Test artist",
  album: "Test album",
  duration: 120,
  added: i,
  favorite: false,
}));
beforeEach(() => {
  useMusic.setState({
    tracks,
    queue: [],
    index: 0,
    playing: false,
    playlists: [],
    panel: true,
  });
  sessionStorage.clear();
  vi.clearAllMocks();
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute("open");
  };
});
afterEach(cleanup);
describe("playback queue", () => {
  it("selects a real track and advances without touching download state", () => {
    const s = useMusic.getState();
    s.play("1");
    expect(useMusic.getState()).toMatchObject({
      queue: ["0", "1", "2"],
      index: 1,
      playing: true,
    });
    s.next();
    expect(useMusic.getState().index).toBe(2);
    s.next();
    expect(useMusic.getState().playing).toBe(false);
  });
  it("inserts Play Next and preserves current track during reordering", () => {
    useMusic.getState().play("0");
    useMusic.getState().enqueue("2", true);
    expect(useMusic.getState().queue).toEqual(["0", "2", "1", "2"]);
    useMusic.getState().move(1, 2);
    expect(useMusic.getState().queue).toEqual(["0", "1", "2", "2"]);
    expect(useMusic.getState().index).toBe(0);
    useMusic.getState().move(0, 2);
    expect(useMusic.getState().queue[0]).toBe("0");
  });
  it("repeat all wraps and single-item queues stop normally", () => {
    useMusic.setState({ repeat: "all" });
    useMusic.getState().play("2");
    useMusic.getState().next();
    expect(useMusic.getState().index).toBe(0);
  });
  it("removing a track cleans playlists and preserves the current selection", () => {
    useMusic.getState().savePlaylist("Mix", ["0", "1", "2"]);
    useMusic.getState().play("1");
    useMusic.getState().remove("0");
    const s = useMusic.getState();
    expect(s.queue[s.index]).toBe("1");
    expect(s.playlists[0].tracks).toEqual(["1", "2"]);
    expect(s.playing).toBe(true);
  });
  it("rejects missing files rather than registering playable tracks", async () => {
    vi.mocked(call).mockRejectedValueOnce(new Error("missing file"));
    await expect(importTrack("C:/missing.mp3")).rejects.toThrow("missing file");
    expect(useMusic.getState().tracks).toHaveLength(3);
  });
  it("deduplicates validated imported paths", async () => {
    vi.mocked(call).mockResolvedValue({
      path: tracks[0].path,
      title: "Alpha",
      artist: "A",
      album: "",
      duration: 120,
    });
    await importTrack(tracks[0].path);
    expect(useMusic.getState().tracks).toHaveLength(3);
  });
});
describe("library and player integration", () => {
  it("searches actual metadata and plays the filtered list", () => {
    render(<Library />);
    fireEvent.change(screen.getByRole("textbox", { name: "Search music" }), {
      target: { value: "Beta" },
    });
    expect(screen.queryByText("Alpha")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Play Beta" }));
    expect(useMusic.getState().queue).toEqual(["1"]);
  });
  it("toggles favorites and filters the result", () => {
    render(<Library />);
    fireEvent.click(screen.getByRole("button", { name: "Favorite Alpha" }));
    fireEvent.click(screen.getByRole("tab", { name: "Favorites" }));
    expect(screen.getByText("Alpha")).toBeInTheDocument();
    expect(screen.queryByText("Beta")).not.toBeInTheDocument();
  });
  it("uses the same playback state in mini and main panels", () => {
    useMusic.getState().play("0");
    render(
      <>
        <Player />
        <Player mini />
      </>,
    );
    expect(screen.getAllByText("Alpha")).toHaveLength(2);
    fireEvent.click(screen.getAllByRole("button", { name: "Pause" })[0]);
    expect(screen.getAllByRole("button", { name: "Play" })).toHaveLength(2);
  });
  it("creates named playlists from a queue", () => {
    useMusic.getState().play("0");
    render(<Library playlistPage />);
    fireEvent.click(
      screen.getByRole("button", { name: "Save queue as playlist" }),
    );
    fireEvent.change(screen.getByLabelText("Playlist name"), {
      target: { value: "Commute" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save playlist" }));
    expect(useMusic.getState().playlists[0]).toMatchObject({
      title: "Commute",
      tracks: ["0", "1", "2"],
    });
  });
  it("retains music when navigating away from a library view", () => {
    const { unmount } = render(<Library />);
    fireEvent.click(screen.getByRole("button", { name: "Play Alpha" }));
    unmount();
    expect(useMusic.getState().playing).toBe(true);
    expect(useMusic.getState().queue[useMusic.getState().index]).toBe("0");
  });
  it("persists real playback preferences", () => {
    render(<PlayerSettings />);
    fireEvent.change(screen.getByLabelText("Playback speed"), {
      target: { value: "1.5" },
    });
    expect(useMusic.getState().settings.speed).toBe(1.5);
    expect(
      JSON.parse(localStorage.getItem("ythub-music-v1")!).state.settings.speed,
    ).toBe(1.5);
  });
  it("shows a useful empty library", () => {
    useMusic.setState({ tracks: [] });
    render(<Library />);
    expect(
      screen.getByText("Your next favorite is waiting"),
    ).toBeInTheDocument();
  });
});
