export type Status =
  | "queued"
  | "analyzing"
  | "downloading"
  | "processing"
  | "converting"
  | "zipping"
  | "completed"
  | "failed"
  | "cancelled"
  | "paused"
  | "skipped";
export type EngineStatus =
  | "waitingforworker"
  | "downloadcomplete"
  | "waitingforprocessor"
  | "merging"
  | "retrywaiting"
  | "cancelling"
  | "interrupted";
export interface Settings {
  theme: string;
  downloadDir: string;
  tempDir: string;
  zipDir: string;
  autoStart: boolean;
  concurrency: number;
  filenameTemplate: string;
  playlistTemplate: string;
  audioQuality: string;
  embedThumbnail: boolean;
  embedMetadata: boolean;
  videoQuality: string;
  container: string;
  codec: string;
  preserveOrder: boolean;
  autoZip: boolean;
  keepFolder: boolean;
  zipPartial: boolean;
  duplicate: string;
  ytDlpPath: string;
  ffmpegPath: string;
  ffprobePath: string;
  retries: number;
  fragments: number;
  socketTimeout: number;
  proxy: string;
  cookiesFile: string;
  logging: string;
  notifications: boolean;
  startWithWindows: boolean;
  startMinimized: boolean;
  setupComplete: boolean;
  advancedConcurrency: boolean;
  ffmpegConcurrency: number;
  hostConcurrency: number;
  bandwidthLimit: number;
  retryBackoff: number;
  adaptiveConcurrency: boolean;
  progressHz: number;
  diskSafetyMb: number;
  fragmentBudget: number;
  prefetch: number;
}
export interface Media {
  id: string;
  url: string;
  title: string;
  uploader: string;
  thumbnail: string | null;
  duration: number | null;
  entries: Media[];
  qualities: number[];
  isPlaylist: boolean;
}
export interface Job {
  id: string;
  url: string;
  mediaType: "audio" | "video";
  title: string;
  playlistId: string | null;
  playlistIndex: number | null;
  playlistTitle: string | null;
  status: Status | EngineStatus;
  progress: number;
  speed: number | null;
  eta: number | null;
  downloadedBytes: number;
  totalBytes: number | null;
  outputPath: string | null;
  error: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  duration: number | null;
  quality: string;
  settings: Settings;
  outputDir: string;
  tempDir: string;
  priority?: "high" | "normal" | "low";
  attempts?: number;
  retryAt?: number | null;
  errorCode?: string | null;
  instantaneousSpeed?: number | null;
}
export interface Batch {
  id: string;
  title: string;
  jobIds: string[];
  createZip: boolean;
  status: Status | EngineStatus;
  progress: number;
  outputPath: string | null;
  error: string | null;
  folder: string;
  settings: Settings;
  totalItems?: number;
  discovered?: number;
  completedItems?: number;
  failedItems?: number;
  cancelledItems?: number;
  throughput?: number;
  eta?: number | null;
}
export interface Diagnostics {
  activeWorkers: number;
  processingWorkers: number;
  zipJobs: number;
  queuedTasks: number;
  configuredConcurrency: number;
  currentConcurrency: number;
  activeYtDlp: number;
  activeFfmpeg: number;
  throughput: number;
  peakSpeed: number;
  retryCount: number;
  throttledHosts: number;
  totalTasks: number;
  completedTasks: number;
  failedTasks: number;
  cancelledTasks: number;
  bytesDownloaded: number;
  resources: {
    cpuPercent: number;
    memoryAvailable: number;
    memoryUsed: number;
    pressure: boolean;
  };
  diskWriteThroughput: number | null;
  processes: {
    taskId: string;
    pid: number | null;
    command: string;
    startedAt: string;
  }[];
}
export interface Database {
  settings: Settings;
  jobs: Job[];
  batches: Batch[];
}
export interface EngineVersion {
  name: string;
  path: string;
  version: string | null;
  error: string | null;
}
export type Page =
  | "dashboard"
  | "explore"
  | "search"
  | "downloads"
  | "library"
  | "playlists"
  | "history"
  | "settings"
   | "account"
  | "about";
