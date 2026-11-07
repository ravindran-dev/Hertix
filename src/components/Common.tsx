import { importTrack, useMusic } from "../stores/music";
import {
  X,
  FolderOpen,
  Copy,
  Music2,
  Film,
  Pause,
  Play,
  RotateCcw,
  Trash2,
  Ban,
  Check,
  LoaderCircle,
  AlertCircle,
} from "lucide-react";
import type { ReactNode } from "react";
import { useEffect, useRef, useState } from "react";
import type { Job } from "../types";
import { bytes, quality, time, terminal } from "../utils/media";
import { call, copyUrl, confirmAction } from "../api";
import { perform, useApp } from "../stores/app";
export function Modal({
  title,
  children,
  onClose,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const error = useApp((s) => s.error);
  const clearError = useApp((s) => s.clearError);
  useEffect(() => {
    const d = ref.current;
    d?.showModal();
    return () => d?.close();
  }, []);
  return (
    <dialog
      ref={ref}
      onCancel={onClose}
      onClick={(e) => {
        if (e.target === ref.current) onClose();
      }}
      aria-labelledby="dialog-title"
    >
      <div className="modal">
        <div className="section-head">
          <h2 id="dialog-title">{title}</h2>
          <button
            className="icon-button"
            aria-label="Close dialog"
            onClick={onClose}
          >
            <X size={18} />
          </button>
        </div>
        {children}
        {error && (
          <div className="alert modal-feedback" role="alert">
            <p>{error}</p>
            <button
              className="icon-button"
              aria-label="Dismiss action error"
              onClick={clearError}
            >
              <X size={17} />
            </button>
          </div>
        )}
      </div>
    </dialog>
  );
}
export function Empty({
  title,
  body,
  children,
}: {
  title: string;
  body: string;
  children?: ReactNode;
}) {
  return (
    <div className="empty">
      <div className="empty-icon">
        <FolderOpen size={28} />
      </div>
      <h3>{title}</h3>
      <p>{body}</p>
      {children}
    </div>
  );
}
export function Progress({ value, label }: { value: number; label: string }) {
  return (
    <div
      className="progress"
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(value)}
    >
      <span style={{ width: `${Math.max(0, Math.min(100, value))}%` }} />
    </div>
  );
}
export function StatusBadge({ status }: { status: Job["status"] }) {
  const Icon = ["completed", "skipped"].includes(status)
    ? Check
    : status === "failed"
      ? AlertCircle
      : [
            "downloading",
            "processing",
            "converting",
            "zipping",
            "analyzing",
            "merging",
            "cancelling",
          ].includes(status)
        ? LoaderCircle
        : null;
  return (
    <span className={`badge ${status}`}>
      {Icon && (
        <Icon size={12} className={Icon === LoaderCircle ? "spin" : ""} />
      )}{" "}
      {(
        {
          waitingforworker: "Waiting",
          downloadcomplete: "Download complete",
          waitingforprocessor: "Waiting for processing",
          retrywaiting: "Retry scheduled",
          interrupted: "Interrupted",
        } as Record<string, string>
      )[status] || status}
    </span>
  );
}
export function JobActions({
  job,
  onDetails,
}: {
  job: Job;
  onDetails: () => void;
}) {
  const action = (name: string) =>
    perform(() => call("control_job", { id: job.id, action: name }));
  return (
    <div className="row-actions">
      {[
        "queued",
        "downloading",
        "processing",
        "converting",
        "analyzing",
        "waitingforworker",
        "downloadcomplete",
        "waitingforprocessor",
        "merging",
        "retrywaiting",
      ].includes(job.status) && (
        <button
          title="Pause download"
          aria-label={`Pause ${job.title}`}
          onClick={() => void action("pause")}
        >
          <Pause size={16} />
        </button>
      )}
      {["paused", "interrupted"].includes(job.status) && (
        <button
          title="Resume download"
          aria-label={`Resume ${job.title}`}
          onClick={() => void action("resume")}
        >
          <Play size={16} />
        </button>
      )}
      {["failed", "cancelled"].includes(job.status) && (
        <button
          title="Retry download"
          aria-label={`Retry ${job.title}`}
          onClick={() => void action("retry")}
        >
          <RotateCcw size={16} />
        </button>
      )}
      {!terminal(job.status) && job.status !== "cancelling" && (
        <button
          title="Cancel download"
          aria-label={`Cancel ${job.title}`}
          onClick={() => void action("cancel")}
        >
          <Ban size={16} />
        </button>
      )}
      {job.status === "completed" &&
        job.mediaType === "audio" &&
        job.outputPath && (
          <button
            title="Play now"
            aria-label={`Play ${job.title}`}
            onClick={() =>
              void perform(async () => {
                const track = await importTrack(job.outputPath!, job);
                useMusic.getState().play(track.id);
              })
            }
          >
            <Play size={16} />
          </button>
        )}
      {job.outputPath && (
        <button
          title="Open folder"
          aria-label={`Open folder for ${job.title}`}
          onClick={() =>
            void perform(() =>
              call("open_output", { id: job.id, kind: "folder" }),
            )
          }
        >
          <FolderOpen size={16} />
        </button>
      )}
      <button
        title="Download details"
        aria-label={`Details for ${job.title}`}
        onClick={onDetails}
      >
        •••
      </button>
    </div>
  );
}
export function JobList({
  jobs,
  onSelect,
}: {
  jobs: Job[];
  onSelect: (id: string) => void;
}) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    if (!jobs.some((j) => j.status === "retrywaiting")) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [jobs]);
  return (
    <div className="job-list">
      {jobs.map((job) => (
        <div className="job-row" key={job.id}>
          <div className={`media-icon ${job.mediaType}`}>
            {job.mediaType === "audio" ? (
              <Music2 size={20} />
            ) : (
              <Film size={20} />
            )}
          </div>
          <div className="job-info">
            <button className="title-button" onClick={() => onSelect(job.id)}>
              {job.playlistIndex && (
                <span className="muted">
                  {String(job.playlistIndex).padStart(2, "0")} ·{" "}
                </span>
              )}
              {job.title}
            </button>
            <div className="job-meta">
              <span>
                {job.mediaType === "audio"
                  ? "MP3"
                  : job.settings.container.toUpperCase()}{" "}
                · {quality(job)}
              </span>
              <span>
                {bytes(job.downloadedBytes)}
                {job.totalBytes ? ` / ${bytes(job.totalBytes)}` : ""}
              </span>
              {job.speed != null && (
                <span>
                  {bytes(job.speed)}/s · {time(job.eta)} left
                </span>
              )}
            </div>
            {!terminal(job.status) && (
              <Progress
                value={job.progress}
                label={`Download progress for ${job.title}`}
              />
            )}{" "}
            {job.error && <p className="inline-error">{job.error}</p>}
            {job.status === "retrywaiting" && (
              <p className="field-note">
                {job.errorCode === "rate_limited"
                  ? "Rate limited — backing off. "
                  : ""}
                Retrying in{" "}
                {Math.max(0, Math.ceil(((job.retryAt || now) - now) / 1000))}s ·
                attempt {job.attempts}
              </p>
            )}
          </div>
          <div className="job-status">
            <StatusBadge status={job.status} />
            {!terminal(job.status) && <small>{job.progress.toFixed(1)}%</small>}
          </div>
          <JobActions job={job} onDetails={() => onSelect(job.id)} />
        </div>
      ))}
    </div>
  );
}
export function JobDetails({
  job,
  onClose,
}: {
  job: Job;
  onClose: () => void;
}) {
  const [copied, setCopied] = useState(false);
  return (
    <Modal title="Download details" onClose={onClose}>
      <h3 className="wrap">{job.title}</h3>
      <StatusBadge status={job.status} />
      {!terminal(job.status) && (
        <label className="setting-field">
          Priority
          <select
            aria-label="Download priority"
            value={job.priority || "normal"}
            onChange={(e) =>
              void perform(() =>
                call("control_job", { id: job.id, action: e.target.value }),
              )
            }
          >
            <option value="high">High — start sooner</option>
            <option value="normal">Normal</option>
            <option value="low">Low</option>
          </select>
          <small>Changes start order; playlist numbering stays the same.</small>
        </label>
      )}
      <dl className="details">
        {[
          ["URL", job.url],
          [
            "Format",
            `${job.mediaType === "audio" ? "MP3" : job.settings.container.toUpperCase()} · ${quality(job)}`,
          ],
          ["Progress", `${job.progress.toFixed(1)}%`],
          ["Speed", job.speed == null ? "—" : `${bytes(job.speed)}/s`],
          ["ETA", time(job.eta)],
          ["Output", job.outputPath || job.outputDir],
          ["Created", new Date(job.createdAt).toLocaleString()],
          [
            "Started",
            job.startedAt ? new Date(job.startedAt).toLocaleString() : "—",
          ],
          [
            "Completed",
            job.completedAt ? new Date(job.completedAt).toLocaleString() : "—",
          ],
        ].map(([k, v]) => (
          <div key={k}>
            <dt>{k}</dt>
            <dd>{v}</dd>
          </div>
        ))}
      </dl>
      {job.error && <pre className="error-detail">{job.error}</pre>}
      <div className="button-row">
        <button
          className="secondary"
          onClick={() =>
            void perform(async () => {
              await copyUrl(job.id, job.url);
              setCopied(true);
            })
          }
        >
          <Copy size={16} />
          {copied ? "Copied!" : "Copy URL"}
        </button>
        <button
          className="secondary"
          onClick={() =>
            void perform(() =>
              call("open_output", { id: job.id, kind: "folder" }),
            )
          }
        >
          <FolderOpen size={16} />
          Open folder
        </button>
        {job.outputPath && (
          <button
            className="primary"
            onClick={() =>
              void perform(() =>
                call("open_output", { id: job.id, kind: "file" }),
              )
            }
          >
            <Play size={16} />
            Open file
          </button>
        )}
      </div>
      {(terminal(job.status) ||
        ["paused", "interrupted"].includes(job.status)) && (
        <button
          className="danger-link"
          onClick={() =>
            void perform(async () => {
              if (
                await confirmAction(
                  "Remove this history entry? Downloaded files are kept.",
                )
              ) {
                await call("control_job", { id: job.id, action: "remove" });
                onClose();
              }
            })
          }
        >
          <Trash2 size={14} />
          Remove entry
        </button>
      )}
    </Modal>
  );
}
