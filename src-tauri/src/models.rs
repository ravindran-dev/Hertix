use serde::{Deserialize, Serialize};

#[derive(Clone, Serialize, Deserialize, Debug)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    pub theme: String,
    pub download_dir: String,
    pub temp_dir: String,
    pub zip_dir: String,
    pub auto_start: bool,
    pub concurrency: usize,
    pub filename_template: String,
    pub playlist_template: String,
    pub audio_quality: String,
    pub embed_thumbnail: bool,
    pub embed_metadata: bool,
    pub video_quality: String,
    pub container: String,
    pub codec: String,
    pub preserve_order: bool,
    pub auto_zip: bool,
    pub keep_folder: bool,
    pub zip_partial: bool,
    pub duplicate: String,
    pub yt_dlp_path: String,
    pub ffmpeg_path: String,
    pub ffprobe_path: String,
    pub retries: u32,
    pub fragments: u32,
    pub socket_timeout: u32,
    pub proxy: String,
    pub cookies_file: String,
    pub logging: String,
    pub notifications: bool,
    pub start_with_windows: bool,
    pub start_minimized: bool,
    pub setup_complete: bool,
    pub advanced_concurrency: bool,
    pub ffmpeg_concurrency: usize,
    pub host_concurrency: usize,
    pub bandwidth_limit: u64,
    pub retry_backoff: u64,
    pub adaptive_concurrency: bool,
    pub progress_hz: u32,
    pub disk_safety_mb: u64,
    pub fragment_budget: u32,
    pub prefetch: usize,
}
impl Default for Settings {
    fn default() -> Self {
        let root = dirs::download_dir()
            .unwrap_or_else(|| dirs::home_dir().unwrap_or_default())
            .join("Hertix");
        Self {
            theme: "dark".into(),
            download_dir: root.to_string_lossy().into(),
            temp_dir: root.join("Temp").to_string_lossy().into(),
            zip_dir: root.join("ZIP").to_string_lossy().into(),
            auto_start: true,
            concurrency: 2,
            filename_template: "%(title).150B [%(id)s].%(ext)s".into(),
            playlist_template: "%(playlist_index)03d - %(title).140B [%(id)s].%(ext)s".into(),
            audio_quality: "320".into(),
            embed_thumbnail: true,
            embed_metadata: true,
            video_quality: "best".into(),
            container: "mp4".into(),
            codec: "quality".into(),
            preserve_order: true,
            auto_zip: false,
            keep_folder: true,
            zip_partial: false,
            duplicate: "skip".into(),
            yt_dlp_path: String::new(),
            ffmpeg_path: String::new(),
            ffprobe_path: String::new(),
            retries: 5,
            fragments: 4,
            socket_timeout: 30,
            proxy: String::new(),
            cookies_file: String::new(),
            logging: "normal".into(),
            notifications: true,
            start_with_windows: false,
            start_minimized: false,
            setup_complete: false,
            advanced_concurrency: false,
            ffmpeg_concurrency: 2,
            host_concurrency: 3,
            bandwidth_limit: 0,
            retry_backoff: 2,
            adaptive_concurrency: true,
            progress_hz: 5,
            disk_safety_mb: 256,
            fragment_budget: 24,
            prefetch: 32,
        }
    }
}
#[derive(Clone, Serialize, Deserialize, Debug, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum Status {
    Queued,
    Analyzing,
    Downloading,
    Processing,
    Converting,
    Zipping,
    Completed,
    Failed,
    Cancelled,
    Paused,
    Skipped,
    WaitingForWorker,
    DownloadComplete,
    WaitingForProcessor,
    Merging,
    RetryWaiting,
    Cancelling,
    Interrupted,
}
impl Status {
    pub fn advance(&self, next: Self) -> Result<Self, String> {
        let valid = matches!(
            (self, &next),
            (
                Self::Queued | Self::RetryWaiting | Self::WaitingForWorker,
                Self::WaitingForWorker | Self::Downloading | Self::WaitingForProcessor
            ) | (Self::Downloading, Self::DownloadComplete)
                | (
                    Self::DownloadComplete | Self::WaitingForProcessor,
                    Self::WaitingForProcessor | Self::Converting | Self::Merging | Self::Processing
                )
                | (
                    Self::Converting | Self::Merging | Self::Processing,
                    Self::Completed | Self::Skipped
                )
                | (Self::Cancelling, Self::Cancelled)
        ) || (!self.terminal()
            && !matches!(self, Self::Paused | Self::Interrupted)
            && matches!(next, Self::Failed | Self::RetryWaiting));
        if valid {
            Ok(next)
        } else {
            Err(format!("Illegal task transition: {self:?} -> {next:?}"))
        }
    }
    pub fn transition(&self, action: &str, running: bool) -> Result<Self, String> {
        match action {
            "pause" | "cancel" if !self.terminal() => Ok(if action == "pause" { Self::Paused } else { Self::Cancelled }),
            "resume" | "retry" if !running && matches!(self, Self::Paused | Self::Interrupted | Self::Failed | Self::Cancelled) => Ok(Self::Queued),
            _ => Err("This queue action is not valid for the current state. Wait for a stopping process before retrying.".into()),
        }
    }
    pub fn active(&self) -> bool {
        matches!(
            self,
            Self::Analyzing
                | Self::Downloading
                | Self::Processing
                | Self::Converting
                | Self::Zipping
                | Self::WaitingForWorker
                | Self::DownloadComplete
                | Self::WaitingForProcessor
                | Self::Merging
                | Self::Cancelling
        )
    }
    pub fn terminal(&self) -> bool {
        matches!(
            self,
            Self::Completed | Self::Failed | Self::Cancelled | Self::Skipped
        )
    }
}
#[derive(Clone, Serialize, Deserialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Media {
    pub id: String,
    pub url: String,
    pub title: String,
    pub uploader: String,
    pub thumbnail: Option<String>,
    pub duration: Option<f64>,
    pub entries: Vec<Media>,
    pub qualities: Vec<u32>,
    pub is_playlist: bool,
}
#[derive(Clone, Serialize, Deserialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Job {
    pub id: String,
    pub url: String,
    pub media_type: String,
    pub title: String,
    pub playlist_id: Option<String>,
    pub playlist_index: Option<usize>,
    pub playlist_title: Option<String>,
    pub status: Status,
    pub progress: f64,
    pub speed: Option<f64>,
    pub eta: Option<f64>,
    pub downloaded_bytes: u64,
    pub total_bytes: Option<u64>,
    pub output_path: Option<String>,
    pub error: Option<String>,
    pub created_at: String,
    pub started_at: Option<String>,
    pub completed_at: Option<String>,
    pub duration: Option<f64>,
    pub quality: String,
    pub settings: Settings,
    pub output_dir: String,
    pub temp_dir: String,
    #[serde(default)]
    pub identity: String,
    #[serde(default)]
    pub priority: Priority,
    #[serde(default)]
    pub attempts: u32,
    #[serde(default)]
    pub retry_at: Option<i64>,
    #[serde(default)]
    pub error_code: Option<String>,
    #[serde(default)]
    pub instantaneous_speed: Option<f64>,
    #[serde(default)]
    pub staging: Option<StagedMedia>,
}
#[derive(Clone, Copy, Debug, Default, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "lowercase")]
pub enum Priority {
    Low,
    #[default]
    Normal,
    High,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StagedMedia {
    pub files: Vec<String>,
    pub title: String,
    pub artist: String,
    pub thumbnail: Option<String>,
    pub final_name: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistItem {
    pub url: String,
    pub title: String,
    pub duration: Option<f64>,
    pub index: usize,
    pub source_id: String,
}
#[derive(Clone, Serialize, Deserialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Batch {
    pub id: String,
    pub title: String,
    pub job_ids: Vec<String>,
    pub create_zip: bool,
    pub status: Status,
    pub progress: f64,
    pub output_path: Option<String>,
    pub error: Option<String>,
    pub folder: String,
    pub settings: Settings,
    #[serde(default)]
    pub total_items: usize,
    #[serde(default)]
    pub discovered: usize,
    #[serde(default)]
    pub completed_items: usize,
    #[serde(default)]
    pub failed_items: usize,
    #[serde(default)]
    pub cancelled_items: usize,
    #[serde(default)]
    pub spool: String,
    #[serde(default)]
    pub cursor: u64,
    #[serde(default)]
    pub media_type: String,
    #[serde(default)]
    pub quality: String,
    #[serde(default)]
    pub throughput: f64,
    #[serde(default)]
    pub eta: Option<f64>,
}
#[derive(Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Database {
    pub settings: Settings,
    pub jobs: Vec<Job>,
    pub batches: Vec<Batch>,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EnqueueRequest {
    pub url: String,
    pub media_type: String,
    pub quality: String,
    pub playlist: bool,
    pub create_zip: bool,
    pub preserve_order: bool,
}
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineVersion {
    pub name: String,
    pub path: String,
    pub version: Option<String>,
    pub error: Option<String>,
}
pub fn now() -> String {
    chrono::Utc::now().to_rfc3339()
}
#[cfg(test)]
pub fn sample_job() -> Job {
    Job {
        id: "12345678-1234-1234-1234-123456789abc".into(),
        url: "https://www.youtube.com/watch?v=jNQXAC9IVRw".into(),
        media_type: "audio".into(),
        title: "Test track".into(),
        playlist_id: None,
        playlist_index: None,
        playlist_title: None,
        status: Status::Queued,
        progress: 0.0,
        speed: None,
        eta: None,
        downloaded_bytes: 0,
        total_bytes: None,
        output_path: None,
        error: None,
        created_at: now(),
        started_at: None,
        completed_at: None,
        duration: Some(19.0),
        quality: "320".into(),
        settings: Settings::default(),
        output_dir: "C:\\Test folder\\Audio".into(),
        temp_dir: "C:\\Test folder\\Temp".into(),
        identity: String::new(),
        priority: Priority::Normal,
        attempts: 0,
        retry_at: None,
        error_code: None,
        instantaneous_speed: None,
        staging: None,
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn queue_transitions() {
        assert_eq!(
            Status::Downloading.transition("pause", true).unwrap(),
            Status::Paused
        );
        assert_eq!(
            Status::Paused.transition("resume", false).unwrap(),
            Status::Queued
        );
        assert!(Status::Paused.transition("resume", true).is_err());
        assert!(Status::Completed.transition("retry", false).is_err());
        assert_eq!(
            Status::Failed.transition("retry", false).unwrap(),
            Status::Queued
        );
    }
}
