import { useEffect, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import type { Job } from "../types";
import {
  Search,
  Pause,
  Play,
  Ban,
  FolderOpen,
  Archive,
  RotateCcw,
  Trash2,
} from "lucide-react";
import { useApp, perform } from "../stores/app";
import { call, confirmAction, desktop } from "../api";
import { active, terminal } from "../utils/media";
import { Empty, JobList, Progress, StatusBadge } from "../components/Common";
export default function Downloads({
  history = false,
  onSelect,
}: {
  history?: boolean;
  onSelect: (id: string) => void;
}) {
  const { db, navigate } = useApp();
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState(
    sessionStorage.getItem("ythub-download-filter") || "all",
  );
  const [saved, setSaved] = useState<Job[]>([]);
  const [offset, setOffset] = useState(0);
  const [more, setMore] = useState(true);
  useEffect(() => {
    if (!history) return;
    let disposed = false;
    let generation = 0;
    let off: (() => void) | undefined;
    const refresh = async () => {
      const ticket = ++generation;
      await perform(async () => {
        const page = await call<Job[]>("get_history", { offset });
        if (!disposed && ticket === generation) {
          setSaved(page);
          setMore(page.length === 200);
        }
      });
    };
    void refresh();
    if (desktop)
      void listen("state:changed", () => void refresh())
        .then((unlisten) => {
          if (disposed) unlisten();
          else off = unlisten;
        })
        .catch((e) => useApp.getState().fail(e));
    return () => {
      disposed = true;
      generation++;
      off?.();
    };
  }, [history, offset]);
  const jobs = history ? saved : db?.jobs || [];
  const items = (history ? [...jobs] : [...jobs].reverse()).filter(
    (j) =>
      (history ? terminal(j.status) : !terminal(j.status)) &&
      `${j.title} ${j.url} ${j.playlistTitle || ""}`
        .toLowerCase()
        .includes(query.toLowerCase()) &&
      (filter === "all" || j.mediaType === filter || j.status === filter),
  );
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">
            {history ? "YOUR LOCAL LIBRARY" : "FROM LINK TO LIBRARY"}
          </div>
          <h1>
            {history ? "Download history" : "Your downloads"}
            <span className="accent">.</span>
          </h1>
          <p>
            {history
              ? "Everything you’ve saved, right where you left it."
              : "Manage your queue and follow every download in real time."}
          </p>
        </div>
        <button
          className="secondary"
          onClick={() =>
            void perform(() => call("open_output", { kind: "downloads" }))
          }
        >
          <FolderOpen size={16} />
          Open downloads
        </button>
      </div>
      <div className="library-tabs" role="tablist" aria-label="Download status">
        {(["active", "completed", "failed"] as const).map((tab) => (
          <button
            key={tab}
            role="tab"
            aria-selected={
              tab === "active" ? !history : history && filter === tab
            }
            onClick={() => {
              setFilter(tab === "active" ? "all" : tab);
              sessionStorage.setItem(
                "ythub-download-filter",
                tab === "active" ? "all" : tab,
              );
              navigate(tab === "active" ? "downloads" : "history");
            }}
          >
            {tab[0].toUpperCase() + tab.slice(1)}
          </button>
        ))}
      </div>
      <div className="toolbar">
        <div className="search">
          <Search size={17} />
          <input
            aria-label="Search downloads"
            placeholder={
              history ? "Search this history page…" : "Search your downloads…"
            }
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
        <select
          aria-label="Filter downloads"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        >
          <option value="all">All formats</option>
          <option value="audio">Audio</option>
          <option value="video">Video</option>
          {history ? (
            <>
              <option value="failed">Failed</option>
              <option value="completed">Completed</option>
              <option value="cancelled">Cancelled</option>
            </>
          ) : (
            <option value="paused">Paused</option>
          )}
        </select>
        {history ? (
          <button
            className="secondary"
            onClick={() =>
              void perform(async () => {
                if (
                  await confirmAction(
                    "Clear finished history entries? Your downloaded files will be kept.",
                  )
                ) {
                  await call("clear_history");
                  setSaved([]);
                  setOffset(0);
                  setMore(false);
                }
              })
            }
          >
            <Trash2 size={15} />
            Clear history
          </button>
        ) : (
          <div className="button-row">
            <button
              className="secondary"
              onClick={() =>
                void perform(() => call("control_all", { action: "pause" }))
              }
            >
              <Pause size={15} />
              Pause all
            </button>
            <button
              className="secondary"
              onClick={() =>
                void perform(() => call("control_all", { action: "resume" }))
              }
            >
              <Play size={15} />
              Resume all
            </button>
            <button
              className="icon-button"
              title="Cancel all downloads"
              aria-label="Cancel all downloads"
              onClick={() =>
                void perform(async () => {
                  if (
                    await confirmAction(
                      "Cancel every queued and active download? Partial media files are retained for retry.",
                    )
                  )
                    await call("control_all", { action: "cancel" });
                })
              }
            >
              <Ban size={17} />
            </button>
          </div>
        )}
      </div>
      {(db?.batches || [])
        .filter((b) => (history ? terminal(b.status) : !terminal(b.status)))
        .map((batch) => {
          const batchJobs = jobs.filter((j) => batch.jobIds.includes(j.id));
          const done =
            batch.completedItems ??
            batchJobs.filter((j) => j.status === "completed").length;
          const failed =
            batch.failedItems ??
            batchJobs.filter((j) => j.status === "failed").length;
          return (
            <section className="card batch-card" key={batch.id}>
              <div className="section-head">
                <h2>
                  <Archive size={18} />
                  {batch.title}
                </h2>
                <StatusBadge status={batch.status} />
              </div>
              <p>
                {done} successful · {failed} failed ·{" "}
                {batchJobs.filter((j) => j.status === "skipped").length} skipped
                · {batch.totalItems ?? batchJobs.length} items
              </p>
              {batch.status === "zipping" && (
                <>
                  <Progress
                    value={batch.progress}
                    label={`ZIP progress for ${batch.title}`}
                  />
                  <small>Creating ZIP · {batch.progress.toFixed(1)}%</small>
                </>
              )}
              {batch.error && <p className="inline-error">{batch.error}</p>}
              <div className="button-row">
                {!terminal(batch.status) && (
                  <>
                    <button
                      className="secondary"
                      onClick={() =>
                        void perform(() =>
                          call("control_playlist", {
                            id: batch.id,
                            action: ["paused", "interrupted"].includes(
                              batch.status,
                            )
                              ? "resume"
                              : "pause",
                          }),
                        )
                      }
                    >
                      {["paused", "interrupted"].includes(batch.status) ? (
                        <Play size={15} />
                      ) : (
                        <Pause size={15} />
                      )}{" "}
                      {["paused", "interrupted"].includes(batch.status)
                        ? "Resume playlist"
                        : "Pause playlist"}
                    </button>
                    <button
                      className="secondary"
                      onClick={() =>
                        void perform(async () => {
                          if (
                            await confirmAction(
                              "Cancel this playlist and stop its active workers?",
                            )
                          )
                            await call("control_playlist", {
                              id: batch.id,
                              action: "cancel",
                            });
                        })
                      }
                    >
                      <Ban size={15} />
                      Cancel playlist
                    </button>
                  </>
                )}
                <button
                  className="secondary"
                  onClick={() =>
                    void perform(() =>
                      call("open_output", {
                        id: batch.id,
                        kind: "playlist-folder",
                      }),
                    )
                  }
                >
                  <FolderOpen size={15} />
                  Open folder
                </button>
                {batch.outputPath && (
                  <button
                    className="secondary"
                    onClick={() =>
                      void perform(() =>
                        call("open_output", { id: batch.id, kind: "zip" }),
                      )
                    }
                  >
                    <Archive size={15} />
                    Open ZIP
                  </button>
                )}
                {(failed > 0 ||
                  (batch.cancelledItems ?? 0) > 0 ||
                  batch.status === "cancelled") && (
                  <button
                    className="secondary"
                    onClick={() =>
                      void perform(async () => {
                        await call("control_playlist", {
                          id: batch.id,
                          action: "retry",
                        });
                        navigate("downloads");
                      })
                    }
                  >
                    <RotateCcw size={15} />
                    Retry unfinished items
                  </button>
                )}
                {batch.status === "failed" &&
                  batch.createZip &&
                  failed === 0 && (
                    <button
                      className="secondary"
                      onClick={() =>
                        void perform(() =>
                          call("retry_archive", { id: batch.id }),
                        )
                      }
                    >
                      <RotateCcw size={15} />
                      Retry ZIP
                    </button>
                  )}
              </div>
            </section>
          );
        })}
      <section className="card queue-card">
        <div className="section-head">
          <h2>{history ? "Saved activity" : "Download queue"}</h2>
          <span className="muted">
            {items.length} {items.length === 1 ? "item" : "items"}
            {!history &&
              ` · ${jobs.filter((j) => active(j.status)).length} active`}
          </span>
        </div>
        {items.length ? (
          <JobList jobs={items} onSelect={onSelect} />
        ) : (
          <Empty
            title={
              query
                ? "No matching downloads"
                : history
                  ? "No download history yet"
                  : "Your queue is clear"
            }
            body={
              query
                ? "Try a different title or filter."
                : history
                  ? "Completed, failed, and cancelled downloads are saved here."
                  : "Paste a YouTube URL on the dashboard to start your next download."
            }
          >
            <button className="secondary" onClick={() => navigate("dashboard")}>
              Add a download
            </button>
          </Empty>
        )}
      </section>
      {history && (
        <div className="button-row">
          <button
            className="secondary"
            disabled={offset === 0}
            onClick={() => setOffset(Math.max(0, offset - 200))}
          >
            Previous 200
          </button>
          <span className="muted">Page {offset / 200 + 1}</span>
          <button
            className="secondary"
            disabled={!more}
            onClick={() => setOffset(offset + 200)}
          >
            Next 200
          </button>
        </div>
      )}
    </>
  );
}
